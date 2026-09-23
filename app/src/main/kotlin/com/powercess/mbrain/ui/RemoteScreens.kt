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
import com.powercess.mbrain.gateway.GatewayRuntime
import com.powercess.mbrain.remote.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun RemoteScreen(config: RemoteConfig, states: Map<String, TunnelStatus>, open: (String) -> Unit, add: () -> Unit, notify: (String) -> Unit) {
    val failure by RemoteRuntime.error.collectAsState()
    ScreenList(groupedRows = true) {
        failure?.let { item { Note(it, error = true) } }
        item { Group { ActionRow("配置服务器", config.server?.let { "${it.name} · ${it.host}:${it.port}" } ?: "未配置", Icons.Outlined.Dns, { open("servers") }) } }
        if (config.tunnels.isEmpty()) item { EmptyState("暂无隧道",
            description = if (config.servers.isEmpty()) "先配置服务器，再添加需要转发的服务" else null,
            action = if (config.servers.isEmpty()) "配置服务器" else "添加隧道",
            onAction = if (config.servers.isEmpty()) ({ open("servers") }) else add) }
        else {
            item { ListSection("我的隧道", "${states.values.count { it.phase == TunnelPhase.CONNECTED }} / ${config.tunnels.size} 已注册") }
            groupedItems(config.tunnels, key = { it.id }) { tunnel ->
                ActionRow(tunnel.name, "${tunnel.type} · ${(states[tunnel.id] ?: TunnelStatus()).phase.label}", Icons.Outlined.Public,
                    { open("tunnel:${tunnel.id}") }, trailing = { TunnelSwitch(tunnel, notify) })
            }
            item { SecondaryAction("添加隧道", Icons.Outlined.Add, onClick = add) }
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
    var more by remember { mutableStateOf(false) }
    var diagnostics by rememberSaveable(tunnel.id) { mutableStateOf(false) }
    val target = if (tunnel.target == TunnelTarget.MCP) "MCP 网关 · 127.0.0.1:${gateway.port ?: GatewayRuntime.PORT}" else "${tunnel.localHost}:${tunnel.localPort}"
    ScreenList {
        item { Group { ActionRow("启用隧道", state.phase.label, Icons.Outlined.Public,
            trailing = { TunnelSwitch(tunnel, notify) }) } }
        state.error?.let { item { Note(it, error = true) } }
        if (tunnel.accessUrl.isNotBlank()) item { Group {
            ActionRow("访问地址", tunnel.accessUrl, Icons.Outlined.Link, { copy("访问地址", tunnel.accessUrl) })
            if (tunnel.target == TunnelTarget.MCP && gateway.running && gateway.token != null) {
                GroupDivider()
                ActionRow("复制客户端配置", icon = Icons.Outlined.ContentCopy, onClick = {
                    val payload = buildJsonObject { putJsonObject("mcpServers") { putJsonObject(tunnel.name) {
                        put("url", tunnel.accessUrl)
                        putJsonObject("headers") { put("Authorization", "Bearer ${gateway.token}") }
                    } } }
                    copy("客户端配置", payload.toString())
                })
            }
        } }
        item { Group { ActionRow("编辑隧道", if (tunnel.enabled) "关闭后可编辑" else "$target · 公网访问端口 ${tunnel.remotePort}",
            Icons.Outlined.Edit, if (!tunnel.enabled) ({ edit(tunnel.id) }) else null) } }
        item { Box {
            TextButton(onClick = { more = true }) { Text("更多"); Icon(Icons.Outlined.ExpandMore, null) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text(if (diagnostics) "收起连接详情" else "连接详情") },
                    onClick = { diagnostics = !diagnostics; more = false })
                if (tunnel.target == TunnelTarget.MCP) DropdownMenuItem(text = { Text("访问凭据") },
                    onClick = { more = false; open("credentials") })
                if (tunnel.enabled && state.phase != TunnelPhase.WAITING) DropdownMenuItem(text = { Text("重新连接") },
                    onClick = { more = false; RemoteRuntime.retry(tunnel.id) })
                DropdownMenuItem(text = { Text("删除隧道", color = MaterialTheme.colorScheme.error) },
                    onClick = { more = false; remove(tunnel.id) })
            }
        } }
        if (diagnostics) {
            item { Group {
                ActionRow("服务器", "${tunnel.server}:${tunnel.serverPort}", Icons.Outlined.Dns)
                GroupDivider(); ActionRow("服务器连接", if (state.serverConnected) "已连接" else "未连接", Icons.Outlined.Link)
                GroupDivider(); ActionRow("本地服务", state.localHealth.label, Icons.Outlined.PhoneAndroid)
            } }
            if (state.events.isNotEmpty()) {
                item { SectionLabel("最近运行记录") }
                item { Group { state.events.forEachIndexed { index, event ->
                    if (index > 0) GroupDivider()
                    ActionRow(event, icon = Icons.Outlined.History)
                } } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TunnelEditor(initial: TunnelConfig, existing: Boolean, config: RemoteConfig, dismiss: () -> Unit, save: (TunnelConfig) -> Unit) {
    val json = remember { Json { encodeDefaults = true } }
    var draft by rememberSaveable { mutableStateOf(json.encodeToString(TunnelConfig.serializer(), initial)) }
    val value = remember(draft) { json.decodeFromString(TunnelConfig.serializer(), draft) }
    fun update(next: TunnelConfig) { draft = json.encodeToString(TunnelConfig.serializer(), next) }
    var localPort by rememberSaveable { mutableStateOf(initial.localPort.toString()) }
    var remotePort by rememberSaveable { mutableStateOf(initial.remotePort.takeIf { it > 0 }?.toString().orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val dirty = value != initial.copy(enabled = false) || localPort != initial.localPort.toString() ||
        remotePort != initial.remotePort.takeIf { it > 0 }?.toString().orEmpty()
    val close = { if (dirty) discard = true else dismiss() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BackHandler { close() }
        Scaffold(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text(if (existing) "编辑隧道" else "添加隧道") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭编辑") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), windowInsets = WindowInsets(0, 0, 0, 0)) },
            bottomBar = { EditorSaveBar("保存隧道") {
                        try {
                            val next = value.asBasicTcp().referenceServer(config.server?.id.orEmpty()).copy(name = value.name.trim(),
                                localHost = if (value.target == TunnelTarget.MCP) "127.0.0.1" else value.localHost.trim(),
                                localPort = if (value.target == TunnelTarget.MCP) GatewayRuntime.PORT else localPort.toIntOrNull() ?: 0, remotePort = remotePort.toIntOrNull() ?: 0,
                                publicUrl = value.publicUrl.trim())
                            require(next.serverId.isNotEmpty()) { "请先配置服务器" }
                            config.copy(tunnels = config.tunnels.filterNot { it.id == next.id } + next).validate()
                            save(next.referenceServer(next.serverId))
                        } catch (e: Exception) { error = e.message ?: "保存失败"; scope.launch { list.animateScrollToItem(0) } }
            } }) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                ScreenList(state = list) {
                    error?.let { item(key = "error") { Note(it, error = true) } }
                    if (!initial.supportsBasicTcp() || (initial.serverId.isNotEmpty() && initial.serverId != config.server?.id))
                        item(key = "legacy") { Note("保存后将使用当前服务器和基础 TCP 转发，请确认公网访问端口。") }
                    item(key = "name") { TunnelField("名称", value.name, { update(value.copy(name = it)); error = null }) }
                    item(key = "target-label") { SectionLabel("本地目标") }
                    item(key = "target") { ChoiceRow(TunnelTarget.entries, value.target, { if (it == TunnelTarget.MCP) "本应用 MCP" else "自定义服务" }) {
                        update(value.copy(target = it))
                    } }
                    if (value.target == TunnelTarget.MCP) {
                        item(key = "mcp-endpoint") { Note("转发本机 MCP 网关：127.0.0.1:${GatewayRuntime.PORT}") }
                    }
                    if (value.target == TunnelTarget.CUSTOM) {
                        item(key = "local-host") { TunnelField("本地服务地址", value.localHost, { update(value.copy(localHost = it)) }, KeyboardType.Uri) }
                        item(key = "local-port") { TunnelField("本地服务端口", localPort, { localPort = it }, KeyboardType.Number) }
                    }
                    item(key = "remote-port") { TunnelField("公网访问端口", remotePort, { remotePort = it }, KeyboardType.Number) }
                    item(key = "public-url") { TunnelField("自定义公网地址（可选）", value.publicUrl, { update(value.copy(publicUrl = it)) }, KeyboardType.Uri,
                        if (value.target == TunnelTarget.MCP) value.copy(server = config.server?.host.orEmpty(), remotePort = remotePort.toIntOrNull() ?: 0, publicUrl = "").accessUrl else "http://phone.example.com:18080") }

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
internal fun TunnelField(label: String, value: String, change: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "") {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = keyboard), placeholder = { Text(placeholder) })
}
