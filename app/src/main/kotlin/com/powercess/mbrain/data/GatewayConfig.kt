package com.powercess.mbrain.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.util.UUID

@Serializable
enum class ExecutionMode { APP, ROOT, SHIZUKU }
@Serializable
enum class ConnectionType { HTTP, STDIO }

@Serializable
data class McpConnectionConfig(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val name: String = "",
    val type: ConnectionType = ConnectionType.HTTP,
    val endpoint: String = "",
    val token: String = "",
    val command: List<String> = emptyList(),
    val mode: ExecutionMode = ExecutionMode.APP,
    val enabled: Boolean = false,
) {
    fun validate() {
        require(name.isNotBlank()) { "请填写服务名称" }
        require(id.matches(Regex("[a-f0-9]{32}"))) { "连接 ID 无效" }
        if (type == ConnectionType.HTTP) {
            require(endpoint.isNotBlank()) { "请填写服务地址" }
            val uri = URI(endpoint)
            require(uri.scheme in listOf("http", "https")) { "仅支持 HTTP / HTTPS MCP" }
            require(uri.host in listOf("127.0.0.1", "localhost", "[::1]", "::1")) { "当前仅支持本机回环地址" }
            require(uri.userInfo == null && uri.fragment == null) { "地址不能包含用户名或 fragment" }
            require(uri.port != 8765) { "不能连接 MBrain 自身端口" }
            require(!token.contains('\r') && !token.contains('\n')) { "Token 不能包含换行" }
        } else {
            require(command.isNotEmpty() && command.first().startsWith('/')) { "命令首项必须是可执行文件绝对路径" }
            require(command.none { '\u0000' in it }) { "命令包含无效字符" }
        }
    }
}

@Serializable
data class GatewayConfig(
    val rootEnabled: Boolean = false,
    val shizukuEnabled: Boolean = false,
    val appsEnabled: Boolean = true,
    val connections: List<McpConnectionConfig> = emptyList(),
)

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): GatewayConfig = runCatching {
        val raw = prefs.getString("config", null) ?: return GatewayConfig()
        val legacy = prefs.getInt("format_version", 1) < 2
        decodeStoredConfig(raw, legacy).also { if (legacy) save(it) }
    }.getOrDefault(GatewayConfig())
    fun save(config: GatewayConfig) {
        prefs.edit().putString("config", json.encodeToString(config)).putInt("format_version", 2).apply()
    }
}

// Old serialization omitted fields equal to its vendor-specific defaults.
// Restore only those omitted fields in existing v1 data, never in a new connection.
internal fun decodeStoredConfig(raw: String, legacy: Boolean): GatewayConfig {
    val json = Json { ignoreUnknownKeys = true }
    if (!legacy) return json.decodeFromString(raw)
    val root = json.parseToJsonElement(raw).jsonObject
    val connections = root["connections"]?.jsonArray ?: return json.decodeFromString(raw)
    val migrated = JsonArray(connections.map { entry ->
        JsonObject(entry.jsonObject.toMutableMap().apply {
            putIfAbsent("name", JsonPrimitive("MT 管理器"))
            if (get("type")?.jsonPrimitive?.contentOrNull != "STDIO") {
                putIfAbsent("endpoint", JsonPrimitive("http://127.0.0.1:8787/mcp"))
            }
        })
    })
    return json.decodeFromJsonElement(JsonObject(root + ("connections" to migrated)))
}
