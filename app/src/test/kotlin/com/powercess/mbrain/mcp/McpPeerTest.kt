package com.powercess.mbrain.mcp

import com.powercess.mbrain.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpPeerTest {
    @Test fun `nested schemas null arguments and rich results survive proxy`() = runTest {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"nested":{"type":"array","items":{"type":["string","null"]}}},"additionalProperties":false}""").jsonObject
        val rich = Json.parseToJsonElement("""{"content":[{"type":"text","text":"done"},{"type":"image","data":"AA==","mimeType":"image/png"}],"structuredContent":{"ok":true},"isError":false}""").jsonObject
        var arguments: JsonObject? = null
        val transport = object : RpcConnection {
            override fun close() {}
            override fun exchange(message: JsonObject): JsonObject? {
                val method = message.getValue("method").jsonPrimitive.content
                if (method == "notifications/initialized") return null
                val result = when (method) {
                    "initialize" -> Json.parseToJsonElement("""{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}""")
                    "tools/list" -> buildJsonObject { putJsonArray("tools") { addJsonObject {
                        put("name", "nested"); put("inputSchema", schema)
                    } } }
                    else -> { arguments = message.getValue("params").jsonObject.getValue("arguments").jsonObject; rich }
                }
                return buildJsonObject { put("jsonrpc", "2.0"); put("id", message.getValue("id")); put("result", result) }
            }
        }
        val tool = McpPeer("abcd1234", transport).initialize().single()
        assertEquals(schema, tool.inputSchema)
        val input = Json.parseToJsonElement("""{"nested":[null,"a"],"explicit":null}""").jsonObject
        assertEquals(rich, tool.executeJson(input).mcpResult)
        assertEquals(input, arguments)
    }

    @Test fun `names stay unique after sanitization and stay within MCP limit`() {
        assertNotEquals(remoteToolName("abcd1234", "a/b"), remoteToolName("abcd1234", "a_b"))
        assertTrue(remoteToolName("abcd1234", "x".repeat(500)).length <= 64)
    }

    @Test fun `HTTP configuration rejects external and self endpoints`() {
        listOf("http://example.com/mcp", "http://127.0.0.1:8765/mcp", "file:///tmp/a", "http://user@localhost:8787/mcp").forEach {
            assertThrows(IllegalArgumentException::class.java) { McpConnectionConfig(name = "Test server", endpoint = it).validate(8765) }
        }
        McpConnectionConfig(name = "Test server", endpoint = "http://localhost:9000/mcp").validate()
        assertThrows(IllegalArgumentException::class.java) { McpConnectionConfig().validate() }
        assertThrows(IllegalArgumentException::class.java) { McpConnectionConfig(name = "Test server").validate() }
    }

    @Test fun `legacy omitted connection fields migrate without replacing user configuration`() {
        val stored = """{"connections":[{"id":"11111111111111111111111111111111","enabled":true},{"id":"22222222222222222222222222222222","name":"Other server","endpoint":"http://localhost:9000/rpc"}]}"""
        val restored = decodeStoredConfig(stored, legacy = true)
        assertEquals("MT 管理器", restored.connections[0].name)
        assertEquals("http://127.0.0.1:8787/mcp", restored.connections[0].endpoint)
        assertTrue(restored.connections[0].enabled)
        assertEquals("Other server", restored.connections[1].name)
        assertEquals("http://localhost:9000/rpc", restored.connections[1].endpoint)
        val current = decodeStoredConfig(stored, legacy = false)
        assertEquals("", current.connections[0].name)
        assertEquals("", current.connections[0].endpoint)
    }

    @Test fun `SSE ignores notifications and joins multiline data`() {
        val stream = ": ping\n\ndata: {\"method\":\"notifications/progress\"}\n\ndata: {\"jsonrpc\":\"2.0\",\n" +
            "data: \"id\":7,\"result\":{\"ok\":true}}\n\n"
        assertEquals(true, readSseResponse(stream.byteInputStream(), JsonPrimitive(7)).getValue("result").jsonObject.getValue("ok").jsonPrimitive.boolean)
    }

    @Test fun `unbounded line is rejected`() {
        assertThrows(IllegalStateException::class.java) { readBoundedLine("x".repeat(MAX_MESSAGE_BYTES + 1).byteInputStream()) }
    }
}
