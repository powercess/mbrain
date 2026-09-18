package com.powercess.mbrain.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*
import java.net.URI
import java.util.UUID

internal fun newId(): String = UUID.randomUUID().toString().replace("-", "")
internal fun validId(value: String) = value.matches(Regex("[a-f0-9]{32}"))
internal fun validHost(value: String): Boolean = value.isNotBlank() && value.length <= 253 &&
    value.none { it.isWhitespace() || it.isISOControl() || it in "/\\?#@\"{}[]" } &&
    runCatching { URI("tcp://${if (':' in value) "[$value]" else value}:1").host != null }.getOrDefault(false)

@Serializable enum class ProxyType { TCP, HTTPS }
@Serializable enum class TunnelPlugin(val label: String, val wireName: String) {
    NONE("不使用插件", ""), HTTPS2HTTP("HTTPS → HTTP", "https2http"), TLS2RAW("TLS → TCP", "tls2raw")
}
@Serializable enum class TunnelTarget { MCP, CUSTOM }

@Serializable
data class TunnelConfig(
    val id: String = newId(),
    val name: String = "",
    val server: String = "",
    val serverPort: Int = 7000,
    val token: String = "",
    val type: ProxyType = ProxyType.TCP,
    val plugin: TunnelPlugin = TunnelPlugin.NONE,
    val target: TunnelTarget = TunnelTarget.MCP,
    val localHost: String = "127.0.0.1",
    val localPort: Int = 8765,
    val remotePort: Int = 0,
    val domains: List<String> = emptyList(),
    val publicUrl: String = "",
    val certificateId: String = "",
    val hostHeaderRewrite: String = "",
    val tlsServerName: String = "",
    val tlsCaId: String = "",
    val tlsClientCertificateId: String = "",
    // Starting tunnels is an explicit action after app process death, never a restore side effect.
    @Transient val enabled: Boolean = false,
) {
    val proxyName: String get() = "mbrain-$id"
    fun endpoint(mcpPort: Int?): Pair<String, Int>? = if (target == TunnelTarget.MCP)
        mcpPort?.let { "127.0.0.1" to it } else localHost to localPort

    fun validate(others: List<TunnelConfig> = emptyList()) {
        require(validId(id)) { "隧道 ID 无效" }
        require(name.isNotBlank() && name.length <= 80) { "请填写隧道名称（最多 80 字）" }
        require(validHost(server)) { "服务器地址应为域名或 IP，不包含协议与端口" }
        require(serverPort in 1..65535) { "服务器端口应为 1–65535" }
        require(token.length <= 4096 && token.none { it.isISOControl() } && "{{" !in token) { "服务器 Token 格式无效" }
        require(validHost(localHost) && localPort in 1..65535) { "请填写有效的本地地址和端口" }
        require(type != ProxyType.HTTPS || plugin != TunnelPlugin.TLS2RAW) { "TLS → TCP 插件需要 TCP 代理" }
        require(target != TunnelTarget.MCP || type != ProxyType.HTTPS || plugin == TunnelPlugin.HTTPS2HTTP) { "MBrain 使用 HTTP，请选择 HTTPS → HTTP 插件" }
        if (type == ProxyType.TCP) require(remotePort in 1..65535) { "映射端口应为 1–65535" }
        else require(domains.isNotEmpty() && domains.size <= 20 && domains.distinctBy { it.lowercase() }.size == domains.size &&
            domains.all { validHost(it) && ':' !in it && it.contains('.') }) { "请填写有效且不重复的域名（不含协议或端口）" }
        require(plugin == TunnelPlugin.NONE || validId(certificateId)) { "请选择包含私钥的服务证书" }
        require(listOf(tlsCaId, tlsClientCertificateId).all { it.isEmpty() || validId(it) }) { "证书引用无效" }
        require(tlsServerName.isEmpty() || validHost(tlsServerName)) { "TLS 服务器名称无效" }
        require(hostHeaderRewrite.length <= 253 && hostHeaderRewrite.none { it.isISOControl() } && "{{" !in hostHeaderRewrite) { "Host 请求头无效" }
        if (publicUrl.isNotBlank()) {
            val uri = runCatching { URI(publicUrl) }.getOrNull()
            require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.fragment == null && uri.rawQuery == null && (uri.port == -1 || uri.port in 1..65535)) { "请填写完整的 HTTP / HTTPS 访问地址" }
            require(target != TunnelTarget.MCP || uri!!.path == "/mcp") { "MBrain 公网地址路径应为 /mcp" }
        }
        require(others.none { other -> other.id != id && other.server.equals(server, true) && other.type == type &&
            (if (type == ProxyType.TCP) other.remotePort == remotePort else other.serverPort == serverPort &&
                other.domains.any { domain -> domains.any { it.equals(domain, true) } }) }) { "此服务器的映射端口或域名已被其他隧道使用" }
    }

    fun frpcConfig(endpoint: Pair<String, Int>, certificatePaths: Map<String, CertificatePaths> = emptyMap()): String {
        validate()
        require(validHost(endpoint.first) && endpoint.second in 1..65535)
        fun certificate(id: String) = requireNotNull(certificatePaths[id]) { "证书不可用" }
        return buildJsonObject {
            put("serverAddr", server); put("serverPort", serverPort); put("loginFailExit", false)
            putJsonObject("auth") { put("method", "token"); put("token", token) }
            putJsonObject("transport") { putJsonObject("tls") {
                put("enable", true)
                if (tlsServerName.isNotEmpty()) put("serverName", tlsServerName)
                if (tlsCaId.isNotEmpty()) put("trustedCaFile", certificate(tlsCaId).certificate)
                if (tlsClientCertificateId.isNotEmpty()) {
                    put("certFile", certificate(tlsClientCertificateId).certificate)
                    put("keyFile", requireNotNull(certificate(tlsClientCertificateId).key))
                }
            } }
            putJsonObject("log") { put("to", "console"); put("level", "info"); put("disablePrintColor", true) }
            putJsonArray("proxies") { addJsonObject {
                put("name", proxyName); put("type", type.name.lowercase())
                if (type == ProxyType.TCP) put("remotePort", remotePort)
                else putJsonArray("customDomains") { domains.forEach { add(it) } }
                if (plugin == TunnelPlugin.NONE) {
                    put("localIP", endpoint.first); put("localPort", endpoint.second)
                } else putJsonObject("plugin") {
                    put("type", plugin.wireName)
                    put("localAddr", "${if (':' in endpoint.first) "[${endpoint.first}]" else endpoint.first}:${endpoint.second}")
                    put("crtPath", certificate(certificateId).certificate)
                    put("keyPath", requireNotNull(certificate(certificateId).key))
                    if (plugin == TunnelPlugin.HTTPS2HTTP && hostHeaderRewrite.isNotEmpty()) put("hostHeaderRewrite", hostHeaderRewrite)
                }
            } }
        }.toString()
    }
    fun certificateIds(): Set<String> = setOf(
        certificateId.takeIf { plugin != TunnelPlugin.NONE }.orEmpty(), tlsCaId, tlsClientCertificateId
    ).filter { it.isNotEmpty() }.toSet()
}

data class CertificatePaths(val certificate: String, val key: String?)
enum class TunnelPhase(val label: String) {
    OFF("已关闭"), WAITING("等待 MCP 网关"), CONNECTING("连接中"), CONNECTED("隧道已注册"), RETRYING("重连中"), ERROR("连接失败")
}
enum class LocalHealth(val label: String) { UNKNOWN("尚未检测"), REACHABLE("端口可连接"), UNREACHABLE("端口不可连接") }
data class TunnelStatus(
    val phase: TunnelPhase = TunnelPhase.OFF, val error: String? = null, val events: List<String> = emptyList(),
    val serverConnected: Boolean = false, val localHealth: LocalHealth = LocalHealth.UNKNOWN,
)

internal fun classifyFrpcLine(line: String): Pair<TunnelPhase, String?>? = when {
    "start proxy success" in line -> TunnelPhase.CONNECTED to null
    "token" in line && listOf("mismatch", "invalid", "not match", "doesn't match").any { it in line } -> TunnelPhase.ERROR to "服务器认证失败"
    "port already used" in line || "port already in use" in line -> TunnelPhase.ERROR to "服务器映射端口被占用"
    "start error" in line || "start proxy error" in line -> TunnelPhase.ERROR to "隧道注册失败，请检查端口、域名和服务器配置"
    "login to server failed" in line || "connect to server error" in line -> TunnelPhase.RETRYING to "无法连接服务器，正在重试"
    listOf("try to connect to server", "reconnect", "heartbeat timeout", "control writer is closing", "read from control").any { it in line } -> TunnelPhase.RETRYING to null
    else -> null
}
