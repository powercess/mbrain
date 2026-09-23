package com.powercess.mbrain.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RemoteConfig(val tunnels: List<TunnelConfig> = emptyList(), val certificates: List<TunnelCertificate> = emptyList(),
    val servers: List<TunnelServer> = emptyList()) {
    // The list is retained only for reading older multi-server configurations.
    val server: TunnelServer? get() = servers.firstOrNull()

    fun resolve(tunnel: TunnelConfig): TunnelConfig = if (tunnel.serverId.isEmpty()) tunnel else
        requireNotNull(servers.find { it.id == tunnel.serverId }) { "请选择已配置的服务器" }.applyTo(tunnel)

    fun migrateServers(): RemoteConfig {
        val migrated = servers.toMutableList()
        val next = tunnels.map { tunnel ->
            if (tunnel.serverId.isNotEmpty()) tunnel else {
                val legacy = TunnelServer.from(tunnel)
                val candidate = if (migrated.any { it.id == legacy.id }) legacy.copy(id = newId()) else legacy
                val server = migrated.find { it.copy(id = candidate.id, name = candidate.name) == candidate }
                    ?: run {
                        var name = candidate.name
                        var suffix = 2
                        while (migrated.any { it.name == name }) { name = "${candidate.name.take(70)} (${suffix++})" }
                        candidate.copy(name = name).also { migrated.add(it) }
                    }
                tunnel.referenceServer(server.id)
            }
        }
        return copy(tunnels = next, servers = migrated)
    }

    fun saveServer(server: TunnelServer): RemoteConfig {
        require(this.server == null || this.server?.id == server.id) { "当前只支持配置一台服务器" }
        server.applyTo(TunnelConfig(name = server.name, remotePort = 1)).requireBasicTcp()
        require(tunnels.none { it.serverId == server.id && it.enabled }) { "请先关闭使用此服务器的隧道再编辑" }
        return copy(servers = if (this.server == null) listOf(server) else servers.map { if (it.id == server.id) server else it }).also { it.validate() }
    }

    fun removeServer(id: String): RemoteConfig {
        require(tunnels.none { it.serverId == id }) { "此服务器仍被隧道使用，请先修改或删除相关隧道" }
        return copy(servers = servers.filterNot { it.id == id })
    }

    fun validate() {
        require(servers.map { it.id }.distinct().size == servers.size) { "服务器 ID 重复" }
        servers.forEach { it.validate() }
        require(tunnels.map { it.id }.distinct().size == tunnels.size && certificates.map { it.id }.distinct().size == certificates.size)
        val resolved = tunnels.map(::resolve)
        resolved.forEach { it.validate(resolved) }
    }
}

class TunnelStore(private val secrets: SecretStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): RemoteConfig = (secrets.read("remote_config_v2")?.let { json.decodeFromString<RemoteConfig>(it) }
        ?: secrets.read("tunnels")?.let { RemoteConfig(tunnels = json.decodeFromString<List<TunnelConfig>>(it)) }
        ?: RemoteConfig()).also { it.validate() }
        .migrateServers().also { it.validate() }
    fun save(config: RemoteConfig) { config.validate(); secrets.write("remote_config_v2", json.encodeToString(config)) }
}
