package com.powercess.mbrain.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TunnelConfigTest {
    @Test fun `legacy tunnels retain configuration without restarting automatically`() {
        val legacy = """[{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"legacy","server":"frp.example.com","remotePort":18080,"enabled":true}]"""
        val tunnels = Json { ignoreUnknownKeys = true }.decodeFromString<List<TunnelConfig>>(legacy)
        RemoteConfig(tunnels = tunnels).validate()
        assertEquals("legacy", tunnels.single().name)
        assertEquals(TunnelTarget.MCP, tunnels.single().target)
        assertEquals(18080, tunnels.single().remotePort)
        assertFalse(tunnels.single().enabled)
    }

    private val base = TunnelConfig(name = "phone", server = "frp.example.com", remotePort = 18080)
    private val certificateId = "a".repeat(32)
    private val paths = mapOf(certificateId to CertificatePaths("/private/service.crt", "/private/service.key"))
    private fun proxy(config: TunnelConfig) = Json.parseToJsonElement(config.frpcConfig("127.0.0.1" to 8787, paths))
        .jsonObject["proxies"]!!.jsonArray.single().jsonObject

    @Test fun `tcp forwards arbitrary local port and does not require running MCP`() {
        val tunnel = base.copy(target = TunnelTarget.CUSTOM, localPort = 8080)
        assertEquals("127.0.0.1" to 8080, tunnel.endpoint(null))
        assertNull(base.endpoint(null))
        assertEquals("127.0.0.1" to 41234, base.endpoint(41234))
        assertEquals(8787, proxy(tunnel)["localPort"]!!.jsonPrimitive.int)
        assertFalse(proxy(tunnel).containsKey("plugin"))
    }

    @Test fun `https routes domains with passthrough for custom targets`() {
        val tunnel = base.copy(type = ProxyType.HTTPS, target = TunnelTarget.CUSTOM, domains = listOf("phone.example.com"))
        val generated = proxy(tunnel)
        assertEquals("https", generated["type"]!!.jsonPrimitive.content)
        assertFalse(generated.containsKey("remotePort"))
        assertEquals("phone.example.com", generated["customDomains"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `both TLS plugins replace the raw local endpoint with plugin options`() {
        for (plugin in listOf(TunnelPlugin.HTTPS2HTTP, TunnelPlugin.TLS2RAW)) {
            val generated = proxy(base.copy(plugin = plugin, certificateId = certificateId))
            assertFalse(generated.containsKey("localIP"))
            assertFalse(generated.containsKey("localPort"))
            val options = generated["plugin"]!!.jsonObject
            assertEquals(plugin.wireName, options["type"]!!.jsonPrimitive.content)
            assertEquals("127.0.0.1:8787", options["localAddr"]!!.jsonPrimitive.content)
            assertEquals(paths.getValue(certificateId).key, options["keyPath"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `frps client TLS is independent from service certificate`() {
        val tunnel = base.copy(tlsCaId = certificateId, tlsClientCertificateId = certificateId, tlsServerName = "frps.example.com")
        val generated = Json.parseToJsonElement(tunnel.frpcConfig("::1" to 8080, paths)).jsonObject
        val tls = generated["transport"]!!.jsonObject["tls"]!!.jsonObject
        assertEquals("frps.example.com", tls["serverName"]!!.jsonPrimitive.content)
        assertEquals("/private/service.crt", tls["trustedCaFile"]!!.jsonPrimitive.content)
        assertEquals("/private/service.key", tls["keyFile"]!!.jsonPrimitive.content)
        assertFalse(generated["proxies"]!!.jsonArray.single().jsonObject.containsKey("plugin"))
    }

    @Test fun `invalid or conflicting configurations fail before launching`() {
        val bad = listOf(base.copy(server = "https://frp.example.com"), base.copy(serverPort = 0),
            base.copy(localPort = 65536), base.copy(token = "{{ .Envs.SECRET }}"),
            base.copy(type = ProxyType.HTTPS, domains = listOf("phone.example.com")),
            base.copy(plugin = TunnelPlugin.TLS2RAW), base.copy(publicUrl = "https://phone.example.com/wrong"),
            base.copy(type = ProxyType.HTTPS, target = TunnelTarget.CUSTOM, domains = listOf("phone.example.com:443")))
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { it.validate() } }
        assertThrows(IllegalArgumentException::class.java) { base.validate(listOf(base.copy(id = "b".repeat(32)))) }
        val https = base.copy(type = ProxyType.HTTPS, target = TunnelTarget.CUSTOM, domains = listOf("phone.example.com"))
        assertThrows(IllegalArgumentException::class.java) { https.validate(listOf(https.copy(id = "b".repeat(32), domains = listOf("PHONE.example.com")))) }
    }

    @Test fun `saved configuration never silently re-enables tunnels`() {
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(RemoteConfig(tunnels = listOf(base.copy(enabled = true))))
        assertFalse(json.decodeFromString<RemoteConfig>(text).tunnels.single().enabled)
    }

    @Test fun `certificate references and private keys are required`() {
        val tunnel = base.copy(plugin = TunnelPlugin.HTTPS2HTTP, certificateId = certificateId)
        assertThrows(IllegalArgumentException::class.java) { RemoteConfig(listOf(tunnel)).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConfig(listOf(tunnel), listOf(TunnelCertificate(id = certificateId, name = "CA", pem = "placeholder"))).validate()
        }
    }

    @Test fun `untrusted log messages cannot leak into visible errors`() {
        assertNull(classifyFrpcLine("arbitrary secret value"))
        assertEquals("服务器认证失败", classifyFrpcLine("token [private] doesn't match")!!.second)
        assertEquals(TunnelPhase.CONNECTED, classifyFrpcLine("start proxy success")!!.first)
    }
}
