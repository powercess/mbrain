package com.powercess.mbrain.remote

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Configuration outlives the service; enabled flags belong only to this application session. */
object RemoteRuntime {
    private lateinit var context: Context
    private lateinit var store: TunnelStore
    private val mutableConfig = MutableStateFlow(RemoteConfig())
    val config = mutableConfig.asStateFlow()
    private val mutableStatus = MutableStateFlow<Map<String, TunnelStatus>>(emptyMap())
    val status = mutableStatus.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var loadFailed = false
    private var controller: FrpcController? = null
    private var mcpPort: Int? = null

    @Synchronized fun initialize(appContext: Context) {
        if (::context.isInitialized) return
        context = appContext.applicationContext
        store = TunnelStore(SecretStore(context))
        runCatching { store.load() }.onSuccess { mutableConfig.value = it }.onFailure {
            loadFailed = true
            mutableError.value = "隧道配置无法解密或读取，已停止加载；原配置保留"
        }
    }

    @Synchronized fun saveTunnel(tunnel: TunnelConfig) {
        check(mutableConfig.value.tunnels.none { it.id == tunnel.id && it.enabled }) { "请先关闭此隧道再编辑" }
        val old = mutableConfig.value
        persist(old.copy(tunnels = old.tunnels.filterNot { it.id == tunnel.id } + tunnel.copy(enabled = false)))
    }

    @Synchronized fun removeTunnel(id: String) {
        persist(mutableConfig.value.copy(tunnels = mutableConfig.value.tunnels.filterNot { it.id == id }))
        mutableStatus.update { it - id }
        refresh()
    }

    @Synchronized fun saveCertificate(certificate: TunnelCertificate) {
        certificate.validate()
        val old = mutableConfig.value
        persist(old.copy(certificates = old.certificates.filterNot { it.id == certificate.id } + certificate))
        // Replacing a referenced certificate restarts only affected tunnels.
        refresh()
    }

    @Synchronized fun removeCertificate(id: String) {
        check(mutableConfig.value.tunnels.none { id in it.certificateIds() }) { "此证书仍被隧道引用，请先修改隧道配置" }
        persist(mutableConfig.value.copy(certificates = mutableConfig.value.certificates.filterNot { it.id == id }))
    }

    private fun persist(next: RemoteConfig) {
        check(!loadFailed) { "原配置读取失败，不能覆盖；请重新启动应用后重试" }
        store.save(next)
        mutableConfig.value = next
    }

    @Synchronized fun enableTunnel(id: String, enabled: Boolean) {
        check(!loadFailed) { "隧道配置不可用" }
        val old = mutableConfig.value
        check(old.tunnels.any { it.id == id }) { "隧道不存在" }
        val next = old.copy(tunnels = old.tunnels.map { if (it.id == id) it.copy(enabled = enabled) else it })
        next.validate()
        mutableConfig.value = next
        try {
            if (enabled) context.startForegroundService(Intent(context, TunnelService::class.java))
            refresh()
        } catch (_: Exception) {
            mutableConfig.value = old
            throw IllegalStateException("无法启动隧道服务，请回到应用后重试")
        }
    }

    @Synchronized fun retry(id: String) { controller?.retry(id); refresh() }

    @Synchronized fun gatewayChanged(port: Int?) { mcpPort = port; refresh() }

    @Synchronized fun serviceStarted() {
        if (controller == null) {
            val directory = File(context.noBackupFilesDir, "frpc-runtime")
            // Only this service owns this fixed private directory. Remove credentials left by process death.
            directory.deleteRecursively()
            controller = FrpcController(File(context.applicationInfo.nativeLibraryDir, "libfrpc.so"), directory, { id, state ->
                mutableStatus.update { states -> states + (id to state) }
            })
        }
        refresh()
    }

    @Synchronized fun serviceStopped() {
        // A deliberately stopped service may be destroyed after a new UI start request.
        // In that case its controller was already detached; do not clear the new request.
        if (controller == null) return
        controller?.close(); controller = null
        mutableConfig.update { it.copy(tunnels = it.tunnels.map { tunnel -> tunnel.copy(enabled = false) }) }
        mutableStatus.value = mutableConfig.value.tunnels.associate { it.id to TunnelStatus() }
    }

    @Synchronized fun stopAll() {
        mutableConfig.update { it.copy(tunnels = it.tunnels.map { tunnel -> tunnel.copy(enabled = false) }) }
        mutableStatus.value = mutableConfig.value.tunnels.associate { it.id to TunnelStatus() }
        controller?.close(); controller = null
        context.stopService(Intent(context, TunnelService::class.java))
    }

    private fun refresh() {
        controller?.reconcile(mutableConfig.value.tunnels, mcpPort, mutableConfig.value.certificates)
        if (mutableConfig.value.tunnels.none { it.enabled } && controller != null) {
            controller?.close(); controller = null
            context.stopService(Intent(context, TunnelService::class.java))
        }
    }
}
