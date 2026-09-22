package com.powercess.mbrain.remote

import com.powercess.mbrain.data.McpConnectionConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TunnelConfigTest {
    private fun tunnel() = TunnelConfig(name = "测试", server = "frp.example.com", remotePort = 18080)

    @Test fun `config escapes credentials and always targets actual loopback port`() {
        val config = tunnel().copy(token = "quotes\"and\\slashes")
        val json = Json.parseToJsonElement(config.frpcConfig(41234)).jsonObject
        assertEquals(config.token, json["auth"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        val proxy = json["proxies"]!!.jsonArray.single().jsonObject
        assertEquals(41234, proxy["localPort"]!!.jsonPrimitive.int)
        assertEquals("127.0.0.1", proxy["localIP"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("webServer"))
    }
    @Test fun `invalid ports and duplicate server mapping are rejected`() {
        val original = tunnel()
        listOf(original.copy(remotePort = 0), original.copy(serverPort = 65536),
            original.copy(publicUrl = "http://example.com/mcp"), original.copy(token = "{{ .Envs.TEST }}"),
            original.copy(server = "https://example.com")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { tunnel().validate(listOf(original)) }
        original.copy(remotePort = 18081).validate(listOf(original))
    }
    @Test fun `self connection follows runtime port not historical default`() {
        val local = McpConnectionConfig(name = "local", endpoint = "http://127.0.0.1:41234/mcp")
        assertThrows(IllegalArgumentException::class.java) { local.validate(41234) }
        local.copy(endpoint = "http://127.0.0.1:8765/mcp").validate(41234)
    }
    @Test fun `only proxy success marks connected and logs do not expose server text`() {
        assertNull(classifyFrpcLine("login to server success secret-value"))
        assertEquals(TunnelPhase.CONNECTED, classifyFrpcLine("[proxy] start proxy success")!!.first)
        assertEquals(TunnelPhase.ERROR, classifyFrpcLine("start error: port already used secret-value")!!.first)
        assertFalse(classifyFrpcLine("login to server failed secret-value")!!.second!!.contains("secret-value"))
        assertEquals(TunnelPhase.RETRYING, classifyFrpcLine("try to connect to server...")!!.first)
    }
    @Test fun `log line consumption is bounded and continues after oversized line`() {
        val reader = ("x".repeat(20_000) + "\nnext\n").reader()
        assertEquals(4096, reader.boundedLine()!!.length)
        assertEquals("next", reader.boundedLine())
        assertNull(reader.boundedLine())
    }
}
