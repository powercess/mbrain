package com.powercess.mbrain.mcp

import com.powercess.mbrain.data.McpConnectionConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HttpRpcConnectionTest {
    @Test fun `real HTTP sends auth and negotiated session and supports paginated catalog`() {
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable>()
        val worker = thread(isDaemon = true) {
            try {
                repeat(4) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().buffered()
                        check(readBoundedLine(input)!!.startsWith("POST /mcp "))
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = readBoundedLine(input) ?: error("EOF in headers")
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val bytes = ByteArray(headers.getValue("content-length").toInt())
                        java.io.DataInputStream(input).readFully(bytes)
                        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        val method = request.getValue("method").jsonPrimitive.content
                        assertEquals("Bearer secret", headers["authorization"])
                        if (method != "initialize") {
                            assertEquals("session-123", headers["mcp-session-id"])
                            assertEquals("2025-03-26", headers["mcp-protocol-version"])
                        }
                        val notification = method == "notifications/initialized"
                        val result = when {
                            method == "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}"""
                            request["params"]?.jsonObject?.containsKey("cursor") == true -> """{"tools":[{"name":"two","inputSchema":{"type":"object"}}]}"""
                            else -> """{"tools":[{"name":"one","inputSchema":{"type":"object"}}],"nextCursor":"page2"}"""
                        }
                        val body = if (notification) "" else "data: {\"jsonrpc\":\"2.0\",\"id\":${request["id"]},\"result\":$result}\n\n"
                        val response = "HTTP/1.1 ${if (notification) "202 Accepted" else "200 OK"}\r\n" +
                            "Content-Type: text/event-stream\r\nMcp-Session-Id: session-123\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        socket.getOutputStream().write(response.toByteArray())
                    }
                }
            } catch (e: Throwable) { failure.set(e) }
        }
        try {
            McpPeer("1234abcd", HttpRpcConnection(McpConnectionConfig(name = "Test server", endpoint = "http://127.0.0.1:${server.localPort}/mcp", token = "secret"))).use {
                assertEquals(2, it.initialize().size)
            }
            worker.join(5000)
            failure.get()?.let { throw it }
        } finally { server.close() }
    }
}
