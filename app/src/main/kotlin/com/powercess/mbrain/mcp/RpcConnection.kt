package com.powercess.mbrain.mcp

import com.powercess.mbrain.data.McpConnectionConfig
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024

interface RpcConnection : Closeable {
    fun exchange(message: JsonObject): JsonObject?
}

/** POST Streamable HTTP, including JSON or SSE replies and server-assigned sessions. */
class HttpRpcConnection(private val config: McpConnectionConfig) : RpcConnection {
    private var session: String? = null
    private var protocol: String? = null
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var closed = false
    init { config.validate() }

    @Synchronized override fun exchange(message: JsonObject): JsonObject? {
        check(!closed) { "连接已关闭" }
        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 5000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (config.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.token}")
            session?.let { setRequestProperty("Mcp-Session-Id", it) }
            protocol?.let { setRequestProperty("MCP-Protocol-Version", it) }
        }
        active = connection
        try {
            if (closed) error("连接已关闭")
            connection.outputStream.use { it.write(message.toString().toByteArray()) }
            val status = connection.responseCode
            check(status in 200..299) { "MCP HTTP $status（检查服务、Token；会话失效请重新连接）" }
            connection.getHeaderField("Mcp-Session-Id")?.let { session = it }
            if (status == 202 || message["id"] == null) return null
            val reply = connection.inputStream.use { input ->
                if (connection.contentType.orEmpty().startsWith("text/event-stream")) {
                    readSseResponse(input, message["id"]!!)
                } else Json.parseToJsonElement(readLimited(input)).jsonObject
            }
            require(reply["id"] == message["id"]) { "MCP 响应 ID 不匹配" }
            if (message["method"]?.jsonPrimitive?.content == "initialize") {
                protocol = reply["result"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.content
            }
            return reply
        } finally { active = null; connection.disconnect() }
    }

    override fun close() { closed = true; active?.disconnect() }
}

fun readLimited(input: InputStream, limit: Int = MAX_MESSAGE_BYTES): String {
    val output = java.io.ByteArrayOutputStream()
    val bytes = ByteArray(8192)
    while (true) {
        val count = input.read(bytes)
        if (count < 0) break
        check(output.size() + count <= limit) { "MCP 消息超过 $limit 字节" }
        output.write(bytes, 0, count)
    }
    return output.toString("UTF-8")
}

/** Enforce a byte cap even when a peer never sends a newline. */
fun readBoundedLine(input: InputStream): String? {
    val output = java.io.ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) return if (output.size() == 0) null else output.toString("UTF-8")
        if (next == 10) return output.toString("UTF-8").removeSuffix("\r")
        check(output.size() < MAX_MESSAGE_BYTES) { "MCP 行超过 2MiB" }
        output.write(next)
    }
}

fun readSseResponse(input: InputStream, id: JsonElement): JsonObject {
    val data = StringBuilder()
    var total = 0
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (true) {
        check(System.nanoTime() < deadline) { "MCP SSE 等待超时" }
        val line = readBoundedLine(input) ?: error("SSE 在响应前结束")
        total += line.toByteArray().size
        check(total <= MAX_MESSAGE_BYTES) { "MCP SSE 超过 2MiB" }
        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                val event = Json.parseToJsonElement(data.toString()).jsonObject
                if (event["id"] == id && (event.containsKey("result") || event.containsKey("error"))) return event
                data.clear()
            }
        } else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.removePrefix("data:").removePrefix(" "))
        }
    }
}

/** One in-flight request per child. stderr is drained separately; stdout is JSON-RPC only. */
class StdioRpcConnection(private val process: Process, private val release: (Process) -> Unit) : RpcConnection {
    private val replies = LinkedBlockingQueue<String>(64)
    @Volatile private var failure: String? = null
    @Volatile private var closed = false
    private val stderr = StringBuilder()
    private val output = process.outputStream.bufferedWriter(Charsets.UTF_8)
    init {
        thread(name = "mbrain-mcp-stdout", isDaemon = true) {
            try {
                val input = process.inputStream.buffered()
                while (!closed) {
                    val line = readBoundedLine(input) ?: break
                    if (line.isNotBlank()) check(replies.offer(line)) { "MCP 消息队列已满" }
                }
                if (!closed) failure = "MCP 子进程已退出"
            } catch (e: Exception) { failure = e.message ?: "MCP 读取失败"; release(process) }
        }
        thread(name = "mbrain-mcp-stderr", isDaemon = true) {
            runCatching {
                val buffer = CharArray(1024)
                process.errorStream.reader().use { reader ->
                    while (!closed) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(stderr) {
                            if (stderr.length < 4096) stderr.append(buffer, 0, minOf(count, 4096 - stderr.length))
                        }
                    }
                }
            }
        }
    }

    @Synchronized override fun exchange(message: JsonObject): JsonObject? {
        check(!closed) { "stdio 连接已关闭" }
        try {
            output.write(message.toString()); output.newLine(); output.flush()
            val id = message["id"] ?: return null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                val line = replies.poll(100, TimeUnit.MILLISECONDS)
                if (line == null) {
                    failure?.let { error(it + ": " + synchronized(stderr) { stderr.toString() }) }
                    continue
                }
                val reply = Json.parseToJsonElement(line).jsonObject
                if (reply["id"] == id && (reply.containsKey("result") || reply.containsKey("error"))) return reply
                if (reply.containsKey("method") && reply.containsKey("id")) {
                    // Sampling/elicitation are not advertised by this gateway client.
                    val rejected = buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", reply["id"]!!)
                        putJsonObject("error") { put("code", -32601); put("message", "Unsupported server request") }
                    }
                    output.write(rejected.toString()); output.newLine(); output.flush()
                }
            }
            error("stdio 请求超时（30秒）")
        } catch (e: Exception) { close(); throw e }
    }
    override fun close() { closed = true; release(process) }
}
