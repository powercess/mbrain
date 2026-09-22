package com.powercess.mbrain.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RemoteConfig(val tunnels: List<TunnelConfig> = emptyList(), val certificates: List<TunnelCertificate> = emptyList()) {
    fun validate() {
        require(tunnels.map { it.id }.distinct().size == tunnels.size && certificates.map { it.id }.distinct().size == certificates.size)
        tunnels.forEach { tunnel ->
            tunnel.validate(tunnels)
            tunnel.certificateIds().forEach { id -> require(certificates.any { it.id == id }) { "引用的证书不存在" } }
            if (tunnel.plugin != TunnelPlugin.NONE) require(certificates.first { it.id == tunnel.certificateId }.keyPem.isNotBlank()) { "服务证书需要私钥" }
            if (tunnel.tlsClientCertificateId.isNotEmpty()) require(certificates.first { it.id == tunnel.tlsClientCertificateId }.keyPem.isNotBlank()) { "客户端证书需要私钥" }
        }
    }
}

class TunnelStore(private val secrets: SecretStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): RemoteConfig = secrets.read("remote_config_v2")?.let { json.decodeFromString<RemoteConfig>(it) }
        ?.also { it.validate(); it.certificates.forEach(TunnelCertificate::validate) }
        ?: secrets.read("tunnels")?.let { RemoteConfig(tunnels = json.decodeFromString<List<TunnelConfig>>(it)).also(RemoteConfig::validate) }
        ?: RemoteConfig()
    fun save(config: RemoteConfig) { config.validate(); secrets.write("remote_config_v2", json.encodeToString(config)) }
}
