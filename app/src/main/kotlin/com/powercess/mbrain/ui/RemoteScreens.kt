package com.powercess.mbrain.ui

import androidx.activity.compose.BackHandler
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
import com.powercess.mbrain.gateway.*
import com.powercess.mbrain.remote.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal fun tunnelState(tunnel: TunnelConfig, status: GatewayStatus): TunnelStatus = status.tunnels[tunnel.id]
    ?: TunnelStatus(if (tunnel.enabled) TunnelPhase.WAITING else TunnelPhase.OFF)

@Composable
internal fun RemoteScreen(tunnels: List<TunnelConfig>, status: GatewayStatus, open: (String) -> Unit, add: () -> Unit) {
    ScreenList {
        if (tunnels.isEmpty()) item {
            EmptyState(Icons.Outlined.Public, "还没有隧道", "添加你的 frp 服务器", "添加隧道", add)
        } else {
            item { SectionLabel("我的隧道", "${status.tunnels.values.count { it.phase == TunnelPhase.CONNECTED }} / ${tunnels.size} 已连接") }
            items(tunnels, key = { it.id }) { tunnel ->
                Group { ActionRow(tunnel.name, tunnelState(tunnel, status).phase.label, Icons.Outlined.Public,
                    { open("tunnel:${tunnel.id}") }, trailing = {
                        Switch(checked = tunnel.enabled, onCheckedChange = { GatewayRuntime.enableTunnel(tunnel.id, it) },
                            modifier = Modifier.semantics { contentDescription = "启用${tunnel.name}" })
                    }) }
            }
            item { OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加隧道")
            } }
        }
    }
}

@Composable
internal fun TunnelScreen(tunnel: TunnelConfig?, status: GatewayStatus, open: (String) -> Unit,
    edit: (String) -> Unit, remove: (String) -> Unit, copy: (String, String) -> Unit) {
    if (tunnel == null) return
    val state = tunnelState(tunnel, status)
    ScreenList {
        item { Group {
            ActionRow("启用隧道", state.phase.label, Icons.Outlined.Public, trailing = {
                Switch(checked = tunnel.enabled, onCheckedChange = { GatewayRuntime.enableTunnel(tunnel.id, it) },
                    modifier = Modifier.semantics { contentDescription = "启用隧道" })
            })
        } }
        state.error?.let { item { Note(it, error = true) } }
        if (tunnel.enabled && status.running && state.phase != TunnelPhase.CONNECTED) item {
            OutlinedButton(onClick = { GatewayRuntime.retryTunnel(tunnel.id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重新连接")
            }
        }
        item { SectionLabel("连接") }
        item { Group {
            ActionRow("公网 MCP", tunnel.publicUrl.ifBlank { "未设置" }, Icons.Outlined.Link,
                if (tunnel.publicUrl.isNotBlank()) ({ copy("公网地址", tunnel.publicUrl) }) else null,
                trailing = { if (tunnel.publicUrl.isNotBlank()) Icon(Icons.Outlined.ContentCopy, null) })
            if (tunnel.publicUrl.isNotBlank() && status.token != null) {
                GroupDivider()
                ActionRow("复制客户端配置", icon = Icons.Outlined.DataObject, onClick = {
                    val payload = buildJsonObject { putJsonObject("mcpServers") {
                        putJsonObject(tunnel.name) {
                            put("url", tunnel.publicUrl)
                            putJsonObject("headers") { put("Authorization", "Bearer ${status.token}") }
                        }
                    } }
                    copy("客户端配置", Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload))
                })
            }
            GroupDivider()
            ActionRow("访问凭据", icon = Icons.Outlined.Key, onClick = { open("credentials") })
        } }
        item { SectionLabel("管理") }
        item { Group {
            ActionRow("服务器", "${tunnel.server}:${tunnel.serverPort}", Icons.Outlined.Dns)
            GroupDivider()
            ActionRow("映射端口", tunnel.remotePort.toString(), Icons.Outlined.SettingsEthernet)
            GroupDivider()
            ActionRow("编辑配置", if (tunnel.enabled) "先关闭隧道" else null, Icons.Outlined.Edit,
                if (!tunnel.enabled) ({ edit(tunnel.id) }) else null)
            GroupDivider()
            ActionRow("连接记录", icon = Icons.Outlined.History, onClick = { open("tunnel-log:${tunnel.id}") })
        } }
        item { TextButton(onClick = { remove(tunnel.id) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp)); Text("删除隧道", color = MaterialTheme.colorScheme.error)
        } }
    }
}

@Composable
internal fun TunnelLogScreen(state: TunnelStatus?) {
    ScreenList {
        if (state?.events.isNullOrEmpty()) item { EmptyState(Icons.Outlined.History, "暂无记录", "连接后显示运行状态") }
        else items(state!!.events) { event -> Group { ActionRow(event, icon = Icons.Outlined.History) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TunnelEditor(initial: TunnelConfig, existing: Boolean, others: List<TunnelConfig>, dismiss: () -> Unit, save: (TunnelConfig) -> Unit) {
    var id by rememberSaveable { mutableStateOf(initial.id) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var server by rememberSaveable { mutableStateOf(initial.server) }
    var port by rememberSaveable { mutableStateOf(initial.serverPort.toString()) }
    var token by rememberSaveable { mutableStateOf(initial.token) }
    var remotePort by rememberSaveable { mutableStateOf(initial.remotePort.takeIf { it > 0 }?.toString().orEmpty()) }
    var url by rememberSaveable { mutableStateOf(initial.publicUrl) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val dirty = name != initial.name || server != initial.server || port != initial.serverPort.toString() || token != initial.token ||
        remotePort != initial.remotePort.takeIf { it > 0 }?.toString().orEmpty() || url != initial.publicUrl
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
                            val next = initial.copy(id = id, name = name.trim(), server = server.trim(), serverPort = port.toIntOrNull() ?: 0,
                                token = token, remotePort = remotePort.toIntOrNull() ?: 0, publicUrl = url.trim())
                            next.validate(others); save(next)
                        } catch (e: Exception) { error = e.message ?: "保存失败"; scope.launch { list.animateScrollToItem(0) } }
                    }, modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(min = 50.dp)) { Text("保存") }
                }
            } }) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                ScreenList(state = list) {
                    error?.let { item(key = "error") { Note(it, error = true) } }
                    item(key = "name") { TunnelField("名称", name, { name = it; error = null }) }
                    item(key = "server") { TunnelField("服务器地址", server, { server = it; error = null }, KeyboardType.Uri) }
                    item(key = "port") { TunnelField("服务器端口", port, { port = it; error = null }, KeyboardType.Number) }
                    item(key = "token") {
                        OutlinedTextField(token, { token = it; error = null }, Modifier.fillMaxWidth(), label = { Text("服务器 Token（可选）") },
                            singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { reveal = !reveal }) {
                                Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "隐藏 Token" else "显示 Token")
                            } })
                    }
                    item(key = "remote-port") { TunnelField("映射端口", remotePort, { remotePort = it; error = null }, KeyboardType.Number) }
                    item(key = "url") { TunnelField("公网 MCP 地址（可选）", url, { url = it; error = null }, KeyboardType.Uri, "https://example.com/mcp") }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
            confirmButton = { TextButton(onClick = dismiss) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    }
}

@Composable
private fun TunnelField(label: String, value: String, change: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "") {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = keyboard), placeholder = { Text(placeholder) })
}
