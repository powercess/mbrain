package com.powercess.mbrain.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TunnelConfigTest {
    private val base = TunnelConfig(name = "phone", server = "frp.example.com", remotePort = 18080)

    @Test fun `MCP forwards gateway port and advertises public port independently of server port`() {
        val tunnel = base.copy(serverPort = 5080, localHost = "old.example.com", localPort = 9999)
        val endpoint = tunnel.endpoint(8765)!!
        assertEquals("127.0.0.1" to 8765, endpoint)
        val config = Json.parseToJsonElement(tunnel.frpcConfig(endpoint)).jsonObject
        assertEquals(5080, config["serverPort"]!!.jsonPrimitive.int)
        val proxy = config["proxies"]!!.jsonArray.single().jsonObject
        assertEquals(8765, proxy["localPort"]!!.jsonPrimitive.int)
        assertEquals(18080, proxy["remotePort"]!!.jsonPrimitive.int)
        assertEquals("http://frp.example.com:18080/mcp", tunnel.accessUrl)
        assertEquals("http://frp.example.com:19000/mcp", tunnel.copy(remotePort = 19000).accessUrl)
        assertEquals("http://[::1]:18080/mcp", tunnel.copy(server = "::1").accessUrl)
        assertEquals("http://external.example.com:80/mcp", tunnel.copy(publicUrl = "http://external.example.com:80/mcp").accessUrl)
        assertNull(tunnel.endpoint(null))
    }

    @Test fun `TCP forwards the local endpoint with transport TLS and without HTTPS plugins`() {
        val tunnel = base.copy(target = TunnelTarget.CUSTOM, localPort = 8080)
        assertEquals("127.0.0.1" to 8080, tunnel.endpoint(null))
        assertNull(base.endpoint(null))
        val json = Json.parseToJsonElement(tunnel.frpcConfig("127.0.0.1" to 8787)).jsonObject
        assertTrue(json["transport"]!!.jsonObject["tls"]!!.jsonObject["enable"]!!.jsonPrimitive.boolean)
        val proxy = json["proxies"]!!.jsonArray.single().jsonObject
        assertEquals("tcp", proxy["type"]!!.jsonPrimitive.content)
        assertEquals(8787, proxy["localPort"]!!.jsonPrimitive.int)
        assertEquals(18080, proxy["remotePort"]!!.jsonPrimitive.int)
        assertFalse(proxy.containsKey("plugin"))
        assertFalse(proxy.containsKey("customDomains"))
    }

    @Test fun `legacy HTTPS plugins and TLS never silently become plain TCP`() {
        val id = "c".repeat(32)
        listOf(base.copy(type = ProxyType.HTTPS, target = TunnelTarget.CUSTOM, domains = listOf("phone.example.com")),
            base.copy(plugin = TunnelPlugin.HTTPS2HTTP, certificateId = id),
            base.copy(plugin = TunnelPlugin.TLS2RAW, certificateId = id), base.copy(tlsCaId = id),
            base.copy(tlsClientCertificateId = id), base.copy(tlsServerName = "frps.example.com"),
            base.copy(publicUrl = "https://phone.example.com/mcp")).forEach { legacy ->
            assertThrows(IllegalArgumentException::class.java) { legacy.frpcConfig("127.0.0.1" to 8765) }
            legacy.asBasicTcp().copy(remotePort = 18080).frpcConfig("127.0.0.1" to 8765)
        }
    }

    @Test fun `invalid and conflicting TCP configurations fail before launching`() {
        listOf(base.copy(server = "https://frp.example.com"), base.copy(serverPort = 0), base.copy(localPort = 65536),
            base.copy(remotePort = 0), base.copy(token = "{{ .Envs.SECRET }}"),
            base.copy(publicUrl = "http://phone.example.com/wrong")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { bad.frpcConfig("127.0.0.1" to 8765) }
        }
        assertThrows(IllegalArgumentException::class.java) { base.validate(listOf(base.copy(id = "b".repeat(32)))) }
    }

    @Test fun `legacy and saved configurations never restart automatically`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """[{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"legacy","server":"frp.example.com","remotePort":18080,"enabled":true}]"""
        assertFalse(json.decodeFromString<List<TunnelConfig>>(legacy).single().enabled)
        val text = json.encodeToString(RemoteConfig(tunnels = listOf(base.copy(enabled = true))))
        assertFalse(json.decodeFromString<RemoteConfig>(text).tunnels.single().enabled)
    }

    @Test fun `untrusted log messages cannot leak into visible errors`() {
        assertNull(classifyFrpcLine("arbitrary secret value"))
        assertEquals("服务器认证失败", classifyFrpcLine("token [private] doesn't match")!!.second)
        assertEquals(TunnelPhase.CONNECTED, classifyFrpcLine("start proxy success")!!.first)
    }
}
