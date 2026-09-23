package com.powercess.mbrain.remote

import kotlinx.serialization.Serializable

@Serializable
data class TunnelServer(
    val id: String = newId(),
    val name: String = "",
    val host: String = "",
    val port: Int = 7000,
    val token: String = "",
    val tlsServerName: String = "",
    val tlsCaId: String = "",
    val tlsClientCertificateId: String = "",
) {
    fun applyTo(tunnel: TunnelConfig) = tunnel.copy(server = host, serverPort = port, token = token,
        tlsServerName = tlsServerName, tlsCaId = tlsCaId, tlsClientCertificateId = tlsClientCertificateId)

    fun validate() {
        require(validId(id)) { "服务器 ID 无效" }
        require(name.isNotBlank() && name.length <= 80) { "请填写服务器名称（最多 80 字）" }
        applyTo(TunnelConfig(name = name, remotePort = 1)).validate()
    }


    companion object {
        fun from(tunnel: TunnelConfig) = TunnelServer(id = tunnel.id, name = tunnel.server.take(80), host = tunnel.server,
            port = tunnel.serverPort, token = tunnel.token, tlsServerName = tunnel.tlsServerName,
            tlsCaId = tunnel.tlsCaId, tlsClientCertificateId = tunnel.tlsClientCertificateId)
    }
}

/** Inline fields are retained only to read old files and feed the existing frpc controller. */
fun TunnelConfig.referenceServer(id: String) = copy(serverId = id, server = "", serverPort = 7000,
    token = "", tlsServerName = "", tlsCaId = "", tlsClientCertificateId = "")
