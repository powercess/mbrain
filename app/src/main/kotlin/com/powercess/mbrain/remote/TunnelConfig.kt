package com.powercess.mbrain.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.util.UUID

@Serializable
data class TunnelConfig(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val name: String = "",
    val server: String = "",
    val serverPort: Int = 7000,
    val token: String = "",
    val remotePort: Int = 0,
    val publicUrl: String = "",
    val enabled: Boolean = false,
) {
    val proxyName: String get() = "mbrain-$id"

    fun validate(others: List<TunnelConfig> = emptyList()) {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "隧道 ID 无效" }
        require(name.isNotBlank() && name.length <= 80) { "请填写隧道名称（最多 80 字）" }
        require(server.isNotBlank() && server.length <= 253 && server.none { it.isWhitespace() || it in "/\\?#@\"" }) { "请填写服务器域名或 IP" }
        require(serverPort in 1..65535 && remotePort in 1..65535) { "端口应为 1–65535" }
        require(token.length <= 4096 && token.none { it.isISOControl() }) { "服务器 Token 格式无效" }
        require(listOf(server, token).none { "{{" in it }) { "配置不能包含模板表达式" }
        if (publicUrl.isNotBlank()) {
            val uri = runCatching { URI(publicUrl) }.getOrNull()
            require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null && (uri.port == -1 || uri.port in 1..65535)) { "请填写 HTTPS MCP 地址" }
        }
        require(others.none { it.id != id && it.server.equals(server, true) && it.remotePort == remotePort }) { "此服务器映射端口已被其他隧道使用" }
    }

    // JSON is a native frpc config format; encoding prevents config/template injection.
    fun frpcConfig(localPort: Int): String {
        validate()
        require(localPort in 1..65535)
        return buildJsonObject {
            put("serverAddr", server)
            put("serverPort", serverPort)
            put("loginFailExit", false)
            putJsonObject("auth") { put("method", "token"); put("token", token) }
            putJsonObject("transport") { putJsonObject("tls") { put("enable", true) } }
            putJsonObject("log") { put("to", "console"); put("level", "info"); put("disablePrintColor", true) }
            putJsonArray("proxies") {
                addJsonObject {
                    put("name", proxyName); put("type", "tcp")
                    put("localIP", "127.0.0.1"); put("localPort", localPort); put("remotePort", remotePort)
                }
            }
        }.toString()
    }
}

enum class TunnelPhase(val label: String) {
    OFF("已关闭"), WAITING("等待网关"), CONNECTING("连接中"), CONNECTED("隧道已建立"), RETRYING("重连中"), ERROR("连接失败")
}

data class TunnelStatus(val phase: TunnelPhase = TunnelPhase.OFF, val error: String? = null, val events: List<String> = emptyList())

// Store only classified messages, never arbitrary server text, config paths or credentials.
internal fun classifyFrpcLine(line: String): Pair<TunnelPhase, String?>? = when {
    "start proxy success" in line -> TunnelPhase.CONNECTED to null
    "token" in line && ("mismatch" in line || "invalid" in line || "not match" in line || "doesn't match" in line) -> TunnelPhase.ERROR to "服务器认证失败"
    "port already used" in line || "port already in use" in line -> TunnelPhase.ERROR to "服务器映射端口被占用"
    "start error" in line || "start proxy error" in line -> TunnelPhase.ERROR to "端口映射失败，请检查服务器配置"
    "login to server failed" in line || "connect to server error" in line -> TunnelPhase.RETRYING to "无法连接服务器，正在重试"
    "try to connect to server" in line || "reconnect" in line || "heartbeat timeout" in line || "control writer is closing" in line || "read from control" in line -> TunnelPhase.RETRYING to null
    else -> null
}
