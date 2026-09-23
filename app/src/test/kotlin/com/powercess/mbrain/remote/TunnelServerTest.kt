package com.powercess.mbrain.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TunnelServerTest {
    private val first = TunnelConfig(name = "one", server = "frp.example.com", remotePort = 18080, token = "test-token")
    private val second = first.copy(id = "b".repeat(32), name = "two", remotePort = 18081)

    @Test fun `migration shares identical connections and preserves frpc output`() {
        val original = RemoteConfig(tunnels = listOf(first, second))
        val migrated = original.migrateServers()
        migrated.validate()
        assertEquals(1, migrated.servers.size)
        assertEquals(migrated.tunnels[0].serverId, migrated.tunnels[1].serverId)
        assertTrue(migrated.tunnels.all { it.token.isEmpty() && it.server.isEmpty() })
        original.tunnels.zip(migrated.tunnels).forEach { (before, after) ->
            assertEquals(before.frpcConfig("127.0.0.1" to 8765), migrated.resolve(after).frpcConfig("127.0.0.1" to 8765))
        }
        assertEquals(migrated, migrated.migrateServers())
    }

    @Test fun `migration does not merge different credentials ports or TLS settings`() {
        listOf(second.copy(token = "other"), second.copy(serverPort = 7001), second.copy(tlsServerName = "tls.example.com"),
            second.copy(tlsCaId = "c".repeat(32)), second.copy(tlsClientCertificateId = "d".repeat(32))).forEach { different ->
            val migrated = RemoteConfig(tunnels = listOf(first, different)).migrateServers()
            assertEquals(2, migrated.servers.size)
            val restored = migrated.resolve(migrated.tunnels[1])
            assertEquals(different.server, restored.server)
            assertEquals(different.token, restored.token)
            assertEquals(different.tlsCaId, restored.tlsCaId)
            assertEquals(different.tlsClientCertificateId, restored.tlsClientCertificateId)

        }
    }

    @Test fun `legacy migration is stable and accepts long valid hostnames`() {
        val legacy = """[{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"legacy","server":"frp.example.com","remotePort":18080,"enabled":true}]"""
        val json = Json { ignoreUnknownKeys = true }
        val config = RemoteConfig(tunnels = json.decodeFromString<List<TunnelConfig>>(legacy))
        assertEquals(config.migrateServers(), config.migrateServers())
        assertFalse(config.migrateServers().tunnels.single().enabled)
        val longHost = "a".repeat(50) + "." + "b".repeat(50) + ".example.com"
        RemoteConfig(tunnels = listOf(first.copy(server = longHost))).migrateServers().validate()
    }

    @Test fun `single server rejects extra servers and retired TLS settings`() {
        val server = TunnelServer(name = "server", host = "frp.example.com")
        val config = RemoteConfig().saveServer(server)
        assertThrows(IllegalArgumentException::class.java) { config.saveServer(server.copy(id = "c".repeat(32))) }
        listOf(server.copy(host = "https://frp.example.com"), server.copy(port = 0), server.copy(token = "{{ .Envs.SECRET }}"),
            server.copy(tlsCaId = "c".repeat(32))).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { config.saveServer(bad) }
        }
    }

    @Test fun `server edits affect every reference and serialized reload keeps tunnels off`() {
        val migrated = RemoteConfig(tunnels = listOf(first, second)).migrateServers()
        val updated = migrated.saveServer(migrated.servers.single().copy(host = "new.example.com", token = "new-token"))
        updated.tunnels.forEach { tunnel ->
            val json = Json.parseToJsonElement(updated.resolve(tunnel).frpcConfig("127.0.0.1" to 8765)).jsonObject
            assertEquals("new.example.com", json["serverAddr"]!!.jsonPrimitive.content)
            assertEquals("new-token", json["auth"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        }
        val running = updated.copy(tunnels = updated.tunnels.map { it.copy(enabled = true) })
        val restored = Json.decodeFromString<RemoteConfig>(Json.encodeToString(running))
        restored.validate()
        assertEquals(updated, restored)
    }

    @Test fun `referenced and active servers are protected`() {
        val config = RemoteConfig(tunnels = listOf(first)).migrateServers()
        val server = config.servers.single()
        assertThrows(IllegalArgumentException::class.java) { config.removeServer(server.id) }
        val running = config.copy(tunnels = config.tunnels.map { it.copy(enabled = true) })
        assertThrows(IllegalArgumentException::class.java) { running.saveServer(server.copy(host = "new.example.com")) }
        assertTrue(config.copy(tunnels = emptyList()).removeServer(server.id).servers.isEmpty())
    }

    @Test fun `missing references retired TLS and conflicting mappings fail validation`() {
        val config = RemoteConfig(tunnels = listOf(first, second)).migrateServers()
        assertThrows(IllegalArgumentException::class.java) { config.copy(servers = emptyList()).validate() }
        assertThrows(IllegalArgumentException::class.java) { config.saveServer(config.servers.single().copy(tlsCaId = "c".repeat(32))) }
        assertThrows(IllegalArgumentException::class.java) {
            config.copy(tunnels = config.tunnels.map { it.copy(remotePort = 18080) }).validate()
        }
        val separate = RemoteConfig(tunnels = listOf(first, second.copy(server = "other.example.com", remotePort = 18080))).migrateServers()
        assertThrows(IllegalArgumentException::class.java) { separate.saveServer(separate.servers[1].copy(host = first.server)) }
    }

    @Test fun `editing primary server preserves inactive legacy servers and certificate payloads`() {
        val certificate = TunnelCertificate(name = "legacy", pem = "legacy-payload", keyPem = "legacy-key")
        val migrated = RemoteConfig(tunnels = listOf(first, second.copy(server = "other.example.com")),
            certificates = listOf(certificate)).migrateServers()
        val primary = migrated.server!!
        val edited = migrated.saveServer(primary.copy(name = "renamed"))
        assertEquals(primary.id, edited.server!!.id)
        assertEquals(migrated.servers[1], edited.servers[1])
        val restored = Json.decodeFromString<RemoteConfig>(Json.encodeToString(edited))
        assertEquals(listOf(certificate), restored.certificates)
        assertEquals(migrated.tunnels, restored.tunnels)
    }
}
