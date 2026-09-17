package com.powercess.mbrain.mcp

import io.droidmcp.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class McpPeer(val id: String, private val connection: RpcConnection) : Closeable {
    private val sequence = AtomicInteger()
    fun initialize(): List<McpTool> {
        val init = request("initialize", buildJsonObject {
            put("protocolVersion", "2025-03-26")
            put("capabilities", buildJsonObject {})
            putJsonObject("clientInfo") { put("name", "MBrain"); put("version", com.powercess.mbrain.BuildConfig.VERSION_NAME) }
        })
        require(init["protocolVersion"] != null && init["capabilities"]?.jsonObject?.containsKey("tools") == true) {
            "上游没有声明 MCP tools 能力"
        }
        connection.exchange(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") })
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            page["tools"]?.jsonArray?.forEach { tools += RemoteTool(it.jsonObject) }
            require(tools.size <= 1000) { "上游工具超过 1000 个" }
            cursor = page["nextCursor"]?.jsonPrimitive?.contentOrNull
            if (cursor != null) require(seen.add(cursor!!)) { "上游分页 cursor 循环" }
        } while (cursor != null)
        require(tools.map { it.name }.distinct().size == tools.size) { "上游工具名称重复" }
        return tools
    }

    fun request(method: String, params: JsonObject): JsonObject {
        val message = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", sequence.incrementAndGet()); put("method", method); put("params", params)
        }
        val response = connection.exchange(message) ?: error("上游没有返回响应")
        require(response["id"] == message["id"]) { "上游响应 ID 不匹配" }
        response["error"]?.let { error("上游 MCP 错误：$it") }
        return response["result"]?.jsonObject ?: error("上游没有返回 result")
    }
    override fun close() = connection.close()

    private inner class RemoteTool(definition: JsonObject) : McpTool {
        private val originalName = definition.getValue("name").jsonPrimitive.content
        override val name = remoteToolName(id, originalName)
        override val description = definition["description"]?.jsonPrimitive?.contentOrNull ?: originalName
        override val parameters = emptyList<ToolParameter>()
        override val inputSchema = definition["inputSchema"]?.jsonObject ?: buildJsonObject { put("type", "object") }
        override val outputSchema = definition["outputSchema"]?.jsonObject
        override val annotations = definition["annotations"]?.jsonObject.let { a -> ToolAnnotations(
            readOnlyHint = a?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull ?: false,
            destructiveHint = a?.get("destructiveHint")?.jsonPrimitive?.booleanOrNull ?: true,
            openWorldHint = a?.get("openWorldHint")?.jsonPrimitive?.booleanOrNull ?: true,
            idempotentHint = a?.get("idempotentHint")?.jsonPrimitive?.booleanOrNull ?: false,
            title = a?.get("title")?.jsonPrimitive?.contentOrNull,
        ) }
        override suspend fun execute(params: Map<String, Any>): ToolResult = executeJson(toJson(params).jsonObject)
        override suspend fun executeJson(params: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val result = request("tools/call", buildJsonObject { put("name", originalName); put("arguments", params) })
            val failed = result["isError"]?.jsonPrimitive?.booleanOrNull == true
            ToolResult(!failed, null, if (failed) "上游工具执行失败" else null, mcpResult = result)
        }
    }
}

fun remoteToolName(id: String, name: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    return "mcp_${id.take(8)}_${name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(36)}_$hash"
}

fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
    is Iterable<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}
