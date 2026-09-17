package com.powercess.mbrain.gateway

import android.content.Context
import com.powercess.mbrain.data.*
import com.powercess.mbrain.mcp.*
import com.powercess.mbrain.shell.*
import io.droidmcp.apps.AppsTools
import io.droidmcp.core.*
import io.droidmcp.device.DeviceTools
import io.droidmcp.root.RootTools
import io.droidmcp.shizuku.ShizukuTools
import io.droidmcp.shell.ShellTools
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

data class ToolInfo(val name: String, val description: String, val source: String)
data class ConnectionStatus(val state: String = "未连接", val count: Int = 0, val error: String? = null)
data class GatewayStatus(
    val running: Boolean = false, val token: String? = null,
    val tools: List<ToolInfo> = emptyList(), val error: String? = null,
    val rootReady: Boolean = false, val shizukuReady: Boolean = false,
    val rootActivation: ActivationState = ActivationState(), val shizukuActivation: ActivationState = ActivationState(),
    val connections: Map<String, ConnectionStatus> = emptyMap(),
    val events: List<String> = emptyList(),
)

object GatewayRuntime {
    const val PORT = 8765
    const val ENDPOINT = "http://127.0.0.1:8765/mcp"
    private lateinit var context: Context
    private lateinit var store: ConfigStore
    private val mutableConfig = MutableStateFlow(GatewayConfig())
    val config = mutableConfig.asStateFlow()
    private val mutableStatus = MutableStateFlow(GatewayStatus())
    val status = mutableStatus.asStateFlow()
    @Volatile private var server: DroidMcp? = null
    private var scope: CoroutineScope? = null
    private var processes = ProcessSupervisor()
    private val operation = Mutex()
    private data class Connected(val peer: McpPeer, val tools: List<McpTool>, val name: String)
    private val peers = ConcurrentHashMap<String, Connected>()
    private val accessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shizukuRequestCode = 100
    private var shizukuPermission: CompletableDeferred<Boolean>? = null
    private val rootActivation = CapabilityActivation(accessScope, authorize = {
        val granted = withTimeoutOrNull(30_000) {
            runInterruptible(Dispatchers.IO) {
                Shell.getCachedShell()?.takeIf { !it.isRoot }?.close()
                Shell.getShell().isRoot
            }
        } ?: error("Root 授权超时，请重试")
        check(granted) { "未获得 Root 权限，请在 Root 管理器中允许 MBrain，并确认设备支持 Root" }
        // Check the same execution path used by MCP, not only libsu's cached grant.
        val probe = ProcessSupervisor()
        try {
            val result = probe.execute(ExecutionMode.ROOT, listOf("/system/bin/id", "-u"))
            check(result.exitCode == 0 && result.stdoutBytes.toString(Charsets.UTF_8).trim() == "0") { "Root 执行通道不可用，请检查 Root 管理器" }
        } finally { probe.close() }
        mutableStatus.update { it.copy(rootReady = true) }
    }, onEnabled = { enabled ->
        if (!enabled) mutableStatus.update { it.copy(rootReady = false) }
        updateConfig { it.copy(rootEnabled = enabled) }
    })
    private val shizukuActivation = CapabilityActivation(accessScope, authorize = {
        check(runCatching { Shizuku.pingBinder() }.getOrDefault(false)) { "Shizuku 未启动，请先在 Shizuku 应用中启动服务，再打开此开关" }
        if (!ShizukuTools.isShizukuReady()) {
            val reply = CompletableDeferred<Boolean>()
            shizukuPermission = reply
            try {
                Shizuku.requestPermission(++shizukuRequestCode)
                val allowed = withTimeoutOrNull(60_000) { reply.await() }
                    ?: error("Shizuku 授权超时，请重新打开开关")
                check(allowed) { "未获得 Shizuku 权限，请允许 MBrain 使用 Shizuku" }
            } finally { if (shizukuPermission === reply) shizukuPermission = null }
        }
        check(ShizukuTools.isShizukuReady()) { "Shizuku 服务不可用，请启动后重试" }
        refreshPermissions()
    }, onEnabled = { enabled -> updateConfig { it.copy(shizukuEnabled = enabled) } })
    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshPermissions() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        accessScope.launch { shizukuPermission?.complete(false); refreshPermissions() }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        accessScope.launch {
            if (code == shizukuRequestCode) shizukuPermission?.complete(result == PackageManager.PERMISSION_GRANTED)
            refreshPermissions()
        }
    }

    fun initialize(appContext: Context) {
        if (!::context.isInitialized) {
            context = appContext.applicationContext
            store = ConfigStore(context)
            mutableConfig.value = store.load()
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
            accessScope.launch { rootActivation.state.collect { state ->
                mutableStatus.update { it.copy(rootActivation = state) }
                reconcileAccess()
            } }
            accessScope.launch { shizukuActivation.state.collect { state ->
                mutableStatus.update { it.copy(shizukuActivation = state) }
                reconcileAccess()
            } }
            if (config.value.rootEnabled) accessScope.launch { rootActivation.setEnabled(true) }
        }
        refreshPermissions()
    }
    fun refreshPermissions() {
        mutableStatus.update { it.copy(rootReady = it.rootReady && RootTools.isRootAvailable(), shizukuReady = ShizukuTools.isShizukuReady()) }
        reconcileAccess()
    }
    fun setCapabilityEnabled(mode: ExecutionMode, enabled: Boolean) {
        accessScope.launch {
            when (mode) {
                ExecutionMode.ROOT -> rootActivation.setEnabled(enabled)
                ExecutionMode.SHIZUKU -> shizukuActivation.setEnabled(enabled)
                ExecutionMode.APP -> Unit
            }
        }
    }
    private fun available(mode: ExecutionMode): Boolean = when (mode) {
        ExecutionMode.ROOT -> config.value.rootEnabled && status.value.rootReady && !rootActivation.state.value.checking
        ExecutionMode.SHIZUKU -> config.value.shizukuEnabled && status.value.shizukuReady && !shizukuActivation.state.value.checking
        ExecutionMode.APP -> true
    }
    private fun reconcileAccess() {
        scope?.launch {
            operation.withLock {
                listOf(ExecutionMode.ROOT, ExecutionMode.SHIZUKU).filterNot(::available).forEach { mode ->
                    processes.stopMode(mode)
                    peers.keys.toList().forEach { id ->
                        val connection = config.value.connections.find { it.id == id }
                        if (connection?.type == ConnectionType.STDIO && connection.mode == mode) disconnectPeer(id)
                    }
                }
                refreshTools()
            }
        }
    }
    fun updateConfig(transform: (GatewayConfig) -> GatewayConfig) {
        val next = transform(config.value)
        mutableConfig.value = next
        store.save(next)
        // Close in-flight connections immediately instead of waiting behind discovery.
        peers.keys.toList().forEach { id ->
            val item = next.connections.find { it.id == id }
            if (item == null || !item.enabled || (item.type == ConnectionType.STDIO &&
                ((item.mode == ExecutionMode.ROOT && !next.rootEnabled) ||
                 (item.mode == ExecutionMode.SHIZUKU && !next.shizukuEnabled)))) disconnectPeer(id)
        }
        scope?.launch {
            operation.withLock {
                val current = config.value
                if (!current.rootEnabled) processes.stopMode(ExecutionMode.ROOT)
                if (!current.shizukuEnabled) processes.stopMode(ExecutionMode.SHIZUKU)
                peers.keys.toList().forEach { id ->
                    val item = current.connections.find { it.id == id }
                    if (item == null || !item.enabled || (item.type == ConnectionType.STDIO &&
                        ((item.mode == ExecutionMode.ROOT && !current.rootEnabled) ||
                         (item.mode == ExecutionMode.SHIZUKU && !current.shizukuEnabled)))) disconnectPeer(id)
                }
                refreshTools()
            }
        }
    }
    fun saveConnection(connection: McpConnectionConfig) {
        connection.validate()
        updateConfig { it.copy(connections = it.connections.filterNot { c -> c.id == connection.id } + connection) }
    }
    fun removeConnection(id: String) = updateConfig { it.copy(connections = it.connections.filterNot { c -> c.id == id }) }

    fun createServer(appContext: Context): DroidMcp {
        initialize(appContext)
        processes = ProcessSupervisor()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return DroidMcp.builder().addTools(localTools())
            .withAuditSink(object : AuditSink {
                override fun record(event: ToolCallAudit) {
                    event("${event.toolName} · ${if (event.success) "成功" else "失败"} · ${event.durationMs}ms")
                }
            })
            .enableHttpServer(port = PORT, host = "127.0.0.1", requireAuth = true, readOnly = false).build()
    }
    private fun localTools(): List<McpTool> = buildList {
        addAll(DeviceTools.all(context))
        if (config.value.appsEnabled) addAll(AppsTools.all(context))
        listOf(ExecutionMode.ROOT, ExecutionMode.SHIZUKU).forEach { mode ->
            val prefix = mode.name.lowercase()
            val enabled = { available(mode) }
            if (enabled()) {
                val backend = ManagedShellBackend(mode, processes, enabled)
                add(ShellCommandTool(backend, prefix))
                addAll(ShellTools.all(context, backend).filterNot { it.name == "run_shell" }.map { NamedTool(it, prefix) })
                addAll(FileOperationTool.all(backend, prefix))
            }
        }
    }
    private fun refreshTools() {
        val current = server ?: return
        val local = localTools()
        val external = peers.values.flatMap { it.tools }
        current.replaceTools(local + external)
        val sources = peers.values.flatMap { p -> p.tools.map { it.name to p.name } }.toMap()
        mutableStatus.update { state -> state.copy(tools = (local + external).map {
            ToolInfo(it.name, it.description, sources[it.name] ?: when {
                it.name.startsWith("root_") -> "Root"
                it.name.startsWith("shizuku_") -> "Shizuku"
                it.name.contains("app") -> "应用"
                else -> "设备"
            })
        }.sortedBy { it.name }) }
    }
    fun started(instance: DroidMcp) {
        server = instance
        mutableStatus.update { it.copy(running = true, token = instance.serverToken, error = null) }
        refreshTools()
        event("本机 MCP 服务已启动")
        config.value.connections.filter { it.enabled }.forEach { connect(it.id) }
    }
    fun connect(id: String) {
        if (server == null) { failed(IllegalStateException("请先在首页启动网关")); return }
        updateConfig { cfg -> cfg.copy(connections = cfg.connections.map { if (it.id == id) it.copy(enabled = true) else it }) }
        scope?.launch {
            operation.withLock {
                val item = config.value.connections.find { it.id == id } ?: return@withLock
                if (!item.enabled) return@withLock
                disconnectPeer(id)
                connectionState(id, ConnectionStatus("连接中"))
                var peer: McpPeer? = null
                try {
                    item.validate()
                    val rpc = when (item.type) {
                        ConnectionType.HTTP -> HttpRpcConnection(item)
                        ConnectionType.STDIO -> {
                            check(item.mode != ExecutionMode.ROOT || available(ExecutionMode.ROOT)) { "Root 尚未就绪，请在能力页启用" }
                            check(item.mode != ExecutionMode.SHIZUKU || available(ExecutionMode.SHIZUKU)) { "Shizuku 尚未就绪，请在能力页启用" }
                            StdioRpcConnection(processes.spawn(item.mode, item.command), processes::release)
                        }
                    }
                    peer = McpPeer(id, rpc)
                    peers[id] = Connected(peer, emptyList(), item.name)
                    val tools = peer.initialize()
                    ensureActive()
                    check(config.value.connections.any { it.id == id && it.enabled }) { "连接已取消" }
                    peers[id] = Connected(peer, tools, item.name)
                    connectionState(id, ConnectionStatus("已连接", tools.size))
                    refreshTools()
                    event("${item.name} 已接入 · ${tools.size} 个工具")
                } catch (e: Exception) {
                    peer?.close()
                    peers.remove(id)
                    if (e is CancellationException) throw e
                    connectionState(id, ConnectionStatus("连接失败", error = e.message))
                    event("${item.name} 连接失败")
                    refreshTools()
                }
            }
        }
    }
    fun disconnect(id: String) = updateConfig { cfg -> cfg.copy(connections = cfg.connections.map { if (it.id == id) it.copy(enabled = false) else it }) }
    private fun disconnectPeer(id: String) {
        peers.remove(id)?.peer?.close()
        connectionState(id, ConnectionStatus())
    }
    private fun connectionState(id: String, state: ConnectionStatus) { mutableStatus.update { it.copy(connections = it.connections + (id to state)) } }
    fun event(message: String) { mutableStatus.update { it.copy(events = (listOf(message) + it.events).take(40)) } }
    fun failed(error: Exception) { mutableStatus.update { it.copy(error = error.message ?: "操作失败") } }
    fun shutdown() {
        scope?.cancel(); scope = null
        peers.values.forEach { it.peer.close() }; peers.clear()
        processes.close()
        server = null
    }
    fun stopped() {
        shutdown()
        mutableStatus.update { it.copy(running = false, token = null, tools = emptyList(), connections = emptyMap()) }
        event("网关及托管子进程已停止")
    }
}
