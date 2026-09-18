package com.powercess.mbrain.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.powercess.mbrain.gateway.GatewayStatus
import com.powercess.mbrain.remote.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun RemoteScreen(config: RemoteConfig, states: Map<String, TunnelStatus>, open: (String) -> Unit, add: () -> Unit, notify: (String) -> Unit) {
    val failure by RemoteRuntime.error.collectAsState()
    ScreenList {
        failure?.let { item { Note(it, error = true) } }
        item { Group { ActionRow("证书管理", "导入服务证书、私钥和 CA", Icons.Outlined.VerifiedUser, { open("certificates") }) } }
        if (config.tunnels.isEmpty()) item { EmptyState(Icons.Outlined.Public, "还没有隧道", "连接你自己的 frps，转发手机上的服务", "添加隧道", add) }
        else {
            item { SectionLabel("我的隧道", "${states.values.count { it.phase == TunnelPhase.CONNECTED }} / ${config.tunnels.size} 已注册") }
            items(config.tunnels, key = { it.id }) { tunnel ->
                Group { ActionRow(tunnel.name, "${tunnel.type} · ${(states[tunnel.id] ?: TunnelStatus()).phase.label}", Icons.Outlined.Public,
                    { open("tunnel:${tunnel.id}") }, trailing = { TunnelSwitch(tunnel, notify) }) }
            }
            item { OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加隧道")
            } }
        }
    }
}

@Composable
private fun TunnelSwitch(tunnel: TunnelConfig, notify: (String) -> Unit) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Switch(tunnel.enabled, onCheckedChange = { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        runCatching { RemoteRuntime.enableTunnel(tunnel.id, enabled) }.onFailure { notify(it.message ?: "启动失败") }
    }, modifier = Modifier.semantics { contentDescription = "启用${tunnel.name}" })
}

@Composable
internal fun TunnelScreen(tunnel: TunnelConfig?, state: TunnelStatus, gateway: GatewayStatus, open: (String) -> Unit,
    edit: (String) -> Unit, remove: (String) -> Unit, copy: (String, String) -> Unit, notify: (String) -> Unit) {
    if (tunnel == null) return
    ScreenList {
        item { Group { ActionRow("启用隧道", state.phase.label, Icons.Outlined.Public, trailing = { TunnelSwitch(tunnel, notify) }) } }
        state.error?.let { item { Note(it, error = true) } }
        item { Group {
            ActionRow("frps 连接", if (state.serverConnected) "已连接" else "未连接", Icons.Outlined.Dns)
            GroupDivider(); ActionRow("隧道注册", state.phase.label, Icons.Outlined.Link)
            GroupDivider(); ActionRow("本地服务", state.localHealth.label, Icons.Outlined.PhoneAndroid)
        } }
        if (state.phase == TunnelPhase.WAITING) item { Note("启动首页的 MCP 网关后，此隧道会自动连接。其他自定义隧道不受影响。") }
        if (tunnel.enabled && state.phase != TunnelPhase.WAITING) item {
            OutlinedButton(onClick = { RemoteRuntime.retry(tunnel.id) }, modifier = Modifier.fillMaxWidth()) { Text("重新连接") }
        }
        item { SectionLabel("连接信息") }
        item { Group {
            ActionRow("公网地址", tunnel.publicUrl.ifBlank { "未填写" }, Icons.Outlined.Link,
                if (tunnel.publicUrl.isNotBlank()) ({ copy("公网地址", tunnel.publicUrl) }) else null)
            if (tunnel.target == TunnelTarget.MCP) {
                if (tunnel.publicUrl.isNotBlank() && gateway.running && gateway.token != null) {
                    GroupDivider()
                    ActionRow("复制 MCP 客户端配置", "包含本次网关运行的 Token", Icons.Outlined.DataObject, {
                        val payload = buildJsonObject { putJsonObject("mcpServers") { putJsonObject(tunnel.name) {
                            put("url", tunnel.publicUrl)
                            putJsonObject("headers") { put("Authorization", "Bearer ${gateway.token}") }
                        } } }
                        copy("客户端配置", Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload))
                    })
                }
                GroupDivider(); ActionRow("MCP 访问凭据", "网关重启后需更新客户端 Token", Icons.Outlined.Key, { open("credentials") })
            }
        } }
        item { SectionLabel("管理") }
        item { Group {
            ActionRow("服务器", "${tunnel.server}:${tunnel.serverPort}", Icons.Outlined.Dns)
            GroupDivider(); ActionRow("转发方式", "${tunnel.type} · ${tunnel.plugin.label}", Icons.Outlined.SettingsEthernet)
            GroupDivider(); ActionRow("本地目标", if (tunnel.target == TunnelTarget.MCP) "MBrain MCP" else "${tunnel.localHost}:${tunnel.localPort}", Icons.Outlined.PhoneAndroid)
            GroupDivider(); ActionRow("公网入口", if (tunnel.type == ProxyType.TCP) "端口 ${tunnel.remotePort}" else tunnel.domains.joinToString(), Icons.Outlined.Language)
            GroupDivider(); ActionRow("编辑配置", if (tunnel.enabled) "先关闭此隧道" else null, Icons.Outlined.Edit, if (!tunnel.enabled) ({ edit(tunnel.id) }) else null)
        } }
        if (state.events.isNotEmpty()) {
            item { SectionLabel("最近运行记录") }
            items(state.events) { event -> Group { ActionRow(event, icon = Icons.Outlined.History) } }
        }
        item { TextButton(onClick = { remove(tunnel.id) }, modifier = Modifier.fillMaxWidth()) { Text("删除隧道", color = MaterialTheme.colorScheme.error) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TunnelEditor(initial: TunnelConfig, existing: Boolean, config: RemoteConfig, dismiss: () -> Unit, save: (TunnelConfig) -> Unit) {
    val json = remember { Json { encodeDefaults = true } }
    var draft by rememberSaveable { mutableStateOf(json.encodeToString(TunnelConfig.serializer(), initial)) }
    val value = remember(draft) { json.decodeFromString(TunnelConfig.serializer(), draft) }
    fun update(next: TunnelConfig) { draft = json.encodeToString(TunnelConfig.serializer(), next) }
    var port by rememberSaveable { mutableStateOf(initial.serverPort.toString()) }
    var localPort by rememberSaveable { mutableStateOf(initial.localPort.toString()) }
    var remotePort by rememberSaveable { mutableStateOf(initial.remotePort.takeIf { it > 0 }?.toString().orEmpty()) }
    var domains by rememberSaveable { mutableStateOf(initial.domains.joinToString(", ")) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val dirty = value != initial.copy(enabled = false) || port != initial.serverPort.toString() || localPort != initial.localPort.toString() ||
        remotePort != initial.remotePort.takeIf { it > 0 }?.toString().orEmpty() || domains != initial.domains.joinToString(", ")
    val close = { if (dirty) discard = true else dismiss() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BackHandler { close() }
        Scaffold(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text(if (existing) "编辑隧道" else "添加隧道") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭编辑") } }, windowInsets = WindowInsets(0, 0, 0, 0)) },
            bottomBar = { Surface(color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        try {
                            val next = value.copy(name = value.name.trim(), server = value.server.trim(), serverPort = port.toIntOrNull() ?: 0,
                                localHost = value.localHost.trim(), localPort = localPort.toIntOrNull() ?: 0, remotePort = remotePort.toIntOrNull() ?: 0,
                                domains = domains.split(Regex("[,，\\s]+")).filter { it.isNotBlank() }, publicUrl = value.publicUrl.trim())
                            next.validate(config.tunnels); save(next)
                        } catch (e: Exception) { error = e.message ?: "保存失败"; scope.launch { list.animateScrollToItem(0) } }
                    }, modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(min = 50.dp)) { Text("保存隧道") }
                }
            } }) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                ScreenList(state = list) {
                    error?.let { item(key = "error") { Note(it, error = true) } }
                    item(key = "name") { TunnelField("名称", value.name, { update(value.copy(name = it)); error = null }) }
                    item(key = "target-label") { SectionLabel("本地目标") }
                    item(key = "target") { ChoiceRow(TunnelTarget.entries, value.target, { if (it == TunnelTarget.MCP) "本应用 MCP" else "自定义服务" }) {
                        update(value.copy(target = it, plugin = if (it == TunnelTarget.MCP && value.type == ProxyType.HTTPS) TunnelPlugin.HTTPS2HTTP else value.plugin))
                    } }
                    if (value.target == TunnelTarget.CUSTOM) {
                        item(key = "local-host") { TunnelField("本地服务地址", value.localHost, { update(value.copy(localHost = it)) }, KeyboardType.Uri) }
                        item(key = "local-port") { TunnelField("本地服务端口", localPort, { localPort = it }, KeyboardType.Number) }
                    }
                    item(key = "server-label") { SectionLabel("frps 服务器") }
                    item(key = "server") { TunnelField("服务器地址", value.server, { update(value.copy(server = it)) }, KeyboardType.Uri) }
                    item(key = "port") { TunnelField("服务器端口", port, { port = it }, KeyboardType.Number) }
                    item(key = "token") { OutlinedTextField(value.token, { update(value.copy(token = it)) }, Modifier.fillMaxWidth(), label = { Text("服务器 Token（可选）") },
                        singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示或隐藏 Token") } }) }
                    item(key = "proxy-label") { SectionLabel("公网入口") }
                    item(key = "type") { ChoiceRow(ProxyType.entries, value.type, { it.name }) {
                        update(value.copy(type = it, plugin = if (it == ProxyType.HTTPS && value.target == TunnelTarget.MCP) TunnelPlugin.HTTPS2HTTP else TunnelPlugin.NONE))
                    } }
                    if (value.type == ProxyType.TCP) item(key = "remote-port") { TunnelField("映射端口", remotePort, { remotePort = it }, KeyboardType.Number) }
                    else item(key = "domains") { TunnelField("域名（多个用逗号分隔）", domains, { domains = it }, KeyboardType.Uri, "phone.example.com") }
                    item(key = "plugin") { SelectionField("客户端插件", value.plugin.label,
                        TunnelPlugin.entries.filter { value.type == ProxyType.TCP || it != TunnelPlugin.TLS2RAW }.map { it to it.label }) { update(value.copy(plugin = it)) } }
                    if (value.plugin != TunnelPlugin.NONE) {
                        item(key = "certificate") { CertificateChoice("服务证书与私钥", value.certificateId, config.certificates.filter { it.keyPem.isNotBlank() }) { update(value.copy(certificateId = it)) } }
                        item(key = "certificate-help") { Note("请先在内网穿透 → 证书管理中导入证书与私钥。") }
                    }
                    if (value.plugin == TunnelPlugin.HTTPS2HTTP) item(key = "host-header") { TunnelField("重写 Host（可选）", value.hostHeaderRewrite, { update(value.copy(hostHeaderRewrite = it)) }) }
                    item(key = "public-url") { TunnelField("公网访问地址（可选）", value.publicUrl, { update(value.copy(publicUrl = it)) }, KeyboardType.Uri,
                        if (value.target == TunnelTarget.MCP) "https://phone.example.com/mcp" else "https://phone.example.com") }
                    item(key = "advanced") { Group { ActionRow("frps 连接 TLS", "已启用加密 · 配置 CA 或双向认证", Icons.Outlined.Lock, { advanced = !advanced }) } }
                    if (advanced) {
                        item(key = "tls-help") { Note("此处配置 frpc 到 frps 的连接，与公网服务证书独立。未选择 CA 时沿用 frp 默认行为，不校验 frps 证书身份。") }
                        item(key = "tls-ca") { CertificateChoice("信任的 CA（可选）", value.tlsCaId, config.certificates) { update(value.copy(tlsCaId = it)) } }
                        item(key = "tls-name") { TunnelField("TLS 服务器名称（可选）", value.tlsServerName, { update(value.copy(tlsServerName = it)) }) }
                        item(key = "tls-client") { CertificateChoice("客户端证书（可选）", value.tlsClientCertificateId, config.certificates.filter { it.keyPem.isNotBlank() }) { update(value.copy(tlsClientCertificateId = it)) } }
                    }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
            confirmButton = { TextButton(onClick = dismiss) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, change: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { values.forEachIndexed { index, option ->
        SegmentedButton(selected == option, { change(option) }, SegmentedButtonDefaults.itemShape(index, values.size)) { Text(label(option)) }
    } }
}

@Composable
internal fun CertificateChoice(label: String, id: String, certificates: List<TunnelCertificate>, change: (String) -> Unit) {
    SelectionField(label, certificates.find { it.id == id }?.name ?: "未选择", listOf("" to "不使用") + certificates.map { it.id to it.name }, change)
}

@Composable
private fun <T> SelectionField(label: String, value: String, choices: List<Pair<T, String>>, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Group { ActionRow(label, value, Icons.Outlined.ExpandMore, { expanded = true }) }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { (key, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { change(key); expanded = false })
        } }
    }
}

@Composable
internal fun TunnelField(label: String, value: String, change: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "") {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = keyboard), placeholder = { Text(placeholder) })
}
