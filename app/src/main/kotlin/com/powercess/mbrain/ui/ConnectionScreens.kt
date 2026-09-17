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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.powercess.mbrain.data.*
import com.powercess.mbrain.gateway.*
import kotlinx.coroutines.launch

@Composable
internal fun ConnectionsScreen(status: GatewayStatus, config: GatewayConfig, open: (String) -> Unit, add: () -> Unit, home: () -> Unit) {
    ScreenList {
        item { SectionLabel("我的服务", "${config.connections.size} 个配置 · ${status.connections.values.count { it.state == "已连接" }} 个已连接") }
        if (!status.running) item { Group { ActionRow("网关尚未启动", "前往首页启动后连接服务", Icons.Outlined.PowerSettingsNew, home) } }
        if (config.connections.isEmpty()) item { EmptyState(Icons.Outlined.Hub, "接入你的第一个服务", "连接本机 HTTP 服务，或由 MBrain 托管 MCP 进程。", "添加服务", add) }
        items(config.connections, key = { it.id }) { connection ->
            val state = status.connections[connection.id] ?: ConnectionStatus()
            Group { ActionRow(connection.name, "${if (connection.type == ConnectionType.HTTP) "HTTP" else "托管进程"} · ${state.state}" + if (state.count > 0) " · ${state.count} 个工具" else "",
                if (connection.type == ConnectionType.HTTP) Icons.Outlined.Link else Icons.Outlined.Terminal,
                { open("connection:${connection.id}") }, trailing = {
                    if (state.state == "连接中") CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(if (state.error != null) Icons.Outlined.ErrorOutline else Icons.Outlined.ChevronRight, null,
                        tint = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }) }
        }
        if (config.connections.isNotEmpty()) item { OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加服务")
        } }
    }
}

@Composable
internal fun ConnectionScreen(connection: McpConnectionConfig?, status: GatewayStatus, open: (String) -> Unit, edit: (String) -> Unit, remove: (String) -> Unit) {
    if (connection == null) { EmptyState(Icons.Outlined.Hub, "服务已移除", "返回服务列表以添加或选择其他服务。"); return }
    val state = status.connections[connection.id] ?: ConnectionStatus()
    val active = state.state in listOf("已连接", "连接中")
    ScreenList {
        item {
            Group(card = true) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(if (connection.type == ConnectionType.HTTP) Icons.Outlined.Link else Icons.Outlined.Terminal, true)
                        Column(Modifier.weight(1f)) {
                            Text(if (connection.type == ConnectionType.HTTP) "本机 HTTP 服务" else "MBrain 托管进程", style = MaterialTheme.typography.titleMedium)
                            Text(if (connection.type == ConnectionType.HTTP) connection.endpoint else connection.command.firstOrNull().orEmpty(),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    StatusPill(state.state, state.state == "已连接", state.error != null)
                    Button(onClick = { if (active) GatewayRuntime.disconnect(connection.id) else GatewayRuntime.connect(connection.id) },
                        enabled = status.running, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        if (state.state == "连接中") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(if (active) Icons.Outlined.LinkOff else Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.state == "连接中") "取消连接" else if (active) "断开连接" else if (state.error != null) "重试连接" else "连接服务")
                    }
                }
            }
        }
        if (!status.running) item { Note("请先在首页启动网关。") }
        state.error?.let { item { SectionLabel("连接失败") }; item { Note(it, error = true) } }
        item { SectionLabel("服务管理") }
        item { Group {
            ActionRow("工具目录", "${state.count} 个工具", Icons.Outlined.Build, { open("tools:@${connection.id}") })
            GroupDivider()
            ActionRow("编辑配置", if (active) "断开连接后可修改" else "地址、认证与执行方式", Icons.Outlined.Edit,
                if (active) null else ({ edit(connection.id) }))
        } }
        item { TextButton(onClick = { remove(connection.id) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp)); Text("移除服务", color = MaterialTheme.colorScheme.error)
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionEditor(initial: McpConnectionConfig, existing: Boolean, dismiss: () -> Unit, save: (McpConnectionConfig) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var endpoint by rememberSaveable { mutableStateOf(initial.endpoint) }
    var token by rememberSaveable { mutableStateOf(initial.token) }
    var executable by rememberSaveable { mutableStateOf(initial.command.firstOrNull().orEmpty()) }
    var arguments by rememberSaveable { mutableStateOf(initial.command.drop(1)) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val formList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dirty = name != initial.name || endpoint != initial.endpoint || token != initial.token ||
        executable != initial.command.firstOrNull().orEmpty() || arguments != initial.command.drop(1) || mode != initial.mode
    val close = { if (dirty) discard = true else dismiss() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { close() }
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text(if (existing) "编辑服务" else "添加服务") }, navigationIcon = {
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭编辑") }
            }) },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Button(onClick = {
                            try {
                                val next = initial.copy(name = name.trim(), endpoint = endpoint.trim(), token = token.trim(),
                                    command = if (initial.type == ConnectionType.STDIO) listOf(executable.trim()) + arguments else emptyList(), mode = mode)
                                next.validate(); save(next)
                            } catch (e: Exception) {
                                error = e.message ?: "请检查配置"
                                scope.launch { formList.animateScrollToItem(0) }
                            }
                        }, modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(min = 50.dp)) { Text("保存服务") }
                    }
                }
            }) { padding ->
            Box(Modifier.padding(padding)) {
                ScreenList(state = formList) {
                    item(key = "type") { Group { ActionRow(if (initial.type == ConnectionType.HTTP) "HTTP MCP" else "托管 MCP 进程",
                        if (initial.type == ConnectionType.HTTP) "连接这台设备上已有的服务" else "由 MBrain 管理进程的启动与停止",
                        if (initial.type == ConnectionType.HTTP) Icons.Outlined.Link else Icons.Outlined.Terminal) } }
                    error?.let { item(key = "error") { Note(it, error = true) } }
                    item(key = "name") { OutlinedTextField(name, { name = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("服务名称") },
                        placeholder = { Text("为服务起一个名称") }, singleLine = true, shape = MaterialTheme.shapes.medium) }
                    if (initial.type == ConnectionType.HTTP) {
                        item(key = "endpoint") { OutlinedTextField(endpoint, { endpoint = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("服务地址") },
                            placeholder = { Text("http://127.0.0.1:端口/路径") },
                            supportingText = { Text("仅支持这台设备的 localhost 或 127.0.0.1") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, shape = MaterialTheme.shapes.medium) }
                        item(key = "token") { OutlinedTextField(token, { token = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("访问 Token（可选）") },
                            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true,
                            trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "隐藏 Token" else "显示 Token") } },
                            shape = MaterialTheme.shapes.medium) }
                    } else {
                        item(key = "executable") { OutlinedTextField(executable, { executable = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("可执行文件") },
                            placeholder = { Text("/system/bin/sh") }, supportingText = { Text("填写完整路径。运行时需已安装在设备上。") }, singleLine = true, shape = MaterialTheme.shapes.medium) }
                        item(key = "arguments-title") { SectionLabel("启动参数", "每项单独填写，无需 JSON 或额外引号") }
                        items(arguments.size, key = { "argument:$it" }) { index ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(arguments[index], { value -> arguments = arguments.toMutableList().also { it[index] = value }; error = null },
                                    Modifier.weight(1f), label = { Text("参数 ${index + 1}") }, shape = MaterialTheme.shapes.medium)
                                IconButton(onClick = { arguments = arguments.filterIndexed { i, _ -> i != index } }) { Icon(Icons.Outlined.RemoveCircleOutline, "移除参数 ${index + 1}") }
                            }
                        }
                        item(key = "add-argument") { OutlinedButton(onClick = { arguments = arguments + "" }) { Icon(Icons.Outlined.Add, null); Text("添加参数") } }
                        item(key = "mode-title") { SectionLabel("执行身份") }
                        item(key = "mode") { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ExecutionMode.entries.forEachIndexed { index, value ->
                                SegmentedButton(selected = mode == value, onClick = { mode = value }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                                    Text(when (value) { ExecutionMode.APP -> "应用"; ExecutionMode.ROOT -> "Root"; ExecutionMode.SHIZUKU -> "Shizuku" })
                                }
                            }
                        } }
                        item(key = "mode-hint") { Note(when (mode) { ExecutionMode.APP -> "使用 MBrain 自身权限，受 Android 应用沙箱限制。"; ExecutionMode.ROOT -> "连接前需授权并启用 Root 能力。"; ExecutionMode.SHIZUKU -> "连接前需授权并启用 Shizuku 能力。" }) }
                    }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
            confirmButton = { TextButton(onClick = dismiss) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    }
}
