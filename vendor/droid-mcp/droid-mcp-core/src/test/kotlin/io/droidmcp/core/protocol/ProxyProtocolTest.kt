package io.droidmcp.core.protocol

import io.droidmcp.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProxyProtocolTest {
    @Test fun `wire protocol preserves raw schema arguments and result`() = runTest {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"x":{"type":["string","null"]}},"additionalProperties":false}""").jsonObject
        val result = Json.parseToJsonElement("""{"content":[{"type":"text","text":"failed"}],"structuredContent":{"x":null},"isError":true}""").jsonObject
        var arguments: JsonObject? = null
        val registry = ToolRegistry()
        registry.register(object : McpTool {
            override val name = "remote"
            override val description = "test"
            override val parameters = emptyList<ToolParameter>()
            override val inputSchema = schema
            override suspend fun execute(params: Map<String, Any>) = error("raw path required")
            override suspend fun executeJson(params: JsonObject): ToolResult {
                arguments = params
                return ToolResult(false, null, "upstream error", result)
            }
        })
        val protocol = McpProtocolImpl(registry)
        val catalog = Json.parseToJsonElement(protocol.handleMessage("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")).jsonObject
        assertEquals(schema, catalog.getValue("result").jsonObject.getValue("tools").jsonArray.single().jsonObject["inputSchema"])
        val response = Json.parseToJsonElement(protocol.handleMessage("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"remote","arguments":{"x":null}}}""")).jsonObject
        assertEquals(JsonNull, arguments!!["x"])
        assertEquals(result, response["result"])
        registry.replaceAll(emptyList())
        assertTrue(registry.listTools().isEmpty())
    }
}
