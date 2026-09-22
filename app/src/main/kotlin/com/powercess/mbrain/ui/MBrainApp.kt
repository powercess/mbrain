package com.powercess.mbrain.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powercess.mbrain.data.*
import com.powercess.mbrain.gateway.*
import com.powercess.mbrain.remote.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MBrainApp(start: () -> Unit, stop: () -> Unit) {
    val status by GatewayRuntime.status.collectAsState()
    val config by GatewayRuntime.config.collectAsState()
    val tunnels by GatewayRuntime.tunnels.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var appearance by rememberSaveable { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    val dark = when (appearance) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stack by rememberSaveable { mutableStateOf(listOf("main")) }
    val route = stack.last()
    val open: (String) -> Unit = { stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var themePicker by rememberSaveable { mutableStateOf(false) }
    var removal by rememberSaveable { mutableStateOf<String?>(null) }
    var tunnelEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var tunnelRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }
    val copy: (String, String) -> Unit = { label, value ->
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        notify("已复制$label")
    }
    val pages = listOf("首页", "能力", "MCP", "设置")
    val icons = listOf(Icons.Outlined.SpaceDashboard, Icons.Outlined.Widgets, Icons.Outlined.Hub, Icons.Outlined.Tune)
    val title = when {
        route == "main" -> if (tab == 0) "MBrain" else pages[tab]
        route.startsWith("cap:") -> capabilityName(route.substringAfter(':'))
        route.startsWith("connection:") -> config.connections.find { it.id == route.substringAfter(':') }?.name ?: "服务详情"
        route.startsWith("tools:") -> "工具目录"
        route.startsWith("tool:") -> "工具详情"
        route == "credentials" -> "连接到 MBrain"
        route == "remote" -> "远程访问"
        route.startsWith("tunnel:") -> tunnels.find { it.id == route.substringAfter(':') }?.name ?: "隧道详情"
        route.startsWith("tunnel-log:") -> "连接记录"
        route == "activity" -> "运行记录"
        route == "about" -> "关于 MBrain"
        else -> "使用说明"
    }
    BackHandler(stack.size > 1 && editor == null && tunnelEditor == null) { back() }
    LaunchedEffect(status.error) { status.error?.let { snackbar.showSnackbar(it) } }
    val holder = rememberSaveableStateHolder()
    MBrainTheme(dark) {
        if (editor == null && tunnelEditor == null) Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = {
                if (route != "main") IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            }, actions = {
                if (route == "main" && tab == 2) IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "添加服务") }
                else if (route == "main" && tab != 0) Box(Modifier.padding(end = 16.dp)) { StatusPill(if (status.running) "运行中" else "已停止", status.running) }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
            bottomBar = {
                if (route == "main") NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                    pages.forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(icons[index], null) }, label = { Text(label) })
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Crossfade(targetState = if (route == "main") "tab:$tab" else route, label = "页面切换") { key ->
                    holder.SaveableStateProvider(key) {
                        when {
                            key == "tab:0" -> HomeScreen(status, config, start, stop, open, { tab = it })
                            key == "tab:1" -> CapabilitiesScreen(status, config, open)
                            key == "tab:2" -> ConnectionsScreen(status, config, open, { adding = true }, { tab = 0 })
                            key == "tab:3" -> SettingsScreen(appearance, open, { themePicker = true }, tunnels.size, status.tunnels.values.count { it.phase == TunnelPhase.CONNECTED })
                            key.startsWith("cap:") -> CapabilityScreen(key.substringAfter(':'), status, config, open, notify)
                            key.startsWith("tools:") -> ToolsScreen(status, key.substringAfter(':'), open)
                            key.startsWith("tool:") -> ToolScreen(status.tools.find { it.name == key.substringAfter(':') }, copy)
                            key.startsWith("connection:") -> ConnectionScreen(config.connections.find { it.id == key.substringAfter(':') }, status,
                                open, { editor = it }, { removal = it })
                            key == "credentials" -> CredentialsScreen(status, copy, tunnels, open)
                            key == "remote" -> RemoteScreen(tunnels, status, open, { tunnelEditor = "new" })
                            key.startsWith("tunnel:") -> TunnelScreen(tunnels.find { it.id == key.substringAfter(':') }, status, open, { tunnelEditor = it }, { tunnelRemoval = it }, copy)
                            key.startsWith("tunnel-log:") -> TunnelLogScreen(status.tunnels[key.substringAfter(':')])
                            key == "activity" -> ActivityScreen(status)
                            key == "about" -> AboutScreen()
                            key == "help" -> HelpScreen(status, copy)
                        }
                    }
                }
            }
        }
        if (adding) ModalBottomSheet(onDismissRequest = { adding = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("添加 MCP 服务", style = MaterialTheme.typography.headlineSmall)
                Group {
                    ActionRow("HTTP 服务", "连接已有的本机 MCP", Icons.Outlined.Link, { adding = false; editor = "new:http" })
                    GroupDivider()
                    ActionRow("托管进程", "由 MBrain 启动和停止", Icons.Outlined.Terminal, { adding = false; editor = "new:stdio" })
                }
            }
        }
        editor?.let { editorKey ->
            val initial = remember(editorKey) {
                when (editorKey) {
                    "new:http" -> McpConnectionConfig()
                    "new:stdio" -> McpConnectionConfig(type = ConnectionType.STDIO)
                    else -> config.connections.find { it.id == editorKey }
                }
            }
            if (initial != null) key(editorKey) {
                ConnectionEditor(initial, !editorKey.startsWith("new:"), { editor = null }) { next ->
                    GatewayRuntime.saveConnection(next)
                    editor = null
                    if (editorKey.startsWith("new:")) open("connection:${next.id}")
                    notify("已保存服务")
                }
            }
        }

        tunnelEditor?.let { editorKey ->
            val initial = remember(editorKey) { if (editorKey == "new") TunnelConfig() else tunnels.find { it.id == editorKey } }
            if (initial != null) key(editorKey) {
                TunnelEditor(initial, editorKey != "new", tunnels, { tunnelEditor = null }) { next ->
                    GatewayRuntime.saveTunnel(next)
                    tunnelEditor = null
                    if (editorKey == "new") open("tunnel:${next.id}")
                    notify("已保存隧道")
                }
            }
        }
        tunnelRemoval?.let { id ->
            AlertDialog(onDismissRequest = { tunnelRemoval = null }, title = { Text("删除这个隧道？") },
                text = { Text("连接将断开，配置将删除。") },
                confirmButton = { TextButton(onClick = { GatewayRuntime.removeTunnel(id); tunnelRemoval = null; back() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { tunnelRemoval = null }) { Text("取消") } })
        }
        if (themePicker) ModalBottomSheet(onDismissRequest = { themePicker = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("外观", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Group {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                        ActionRow(label, icon = if (value == "dark") Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                            onClick = { appearance = value; prefs.edit().putString("theme", value).apply(); themePicker = false },
                            trailing = { if (appearance == value) Icon(Icons.Outlined.Check, "已选择", tint = MaterialTheme.colorScheme.primary) })
                    }
                }
            }
        }
        removal?.let { id ->
            AlertDialog(onDismissRequest = { removal = null }, icon = { Icon(Icons.Outlined.DeleteOutline, null) },
                title = { Text("移除这个服务？") }, text = { Text("${config.connections.find { it.id == id }?.name.orEmpty()} 的连接将关闭，并删除保存的配置。") },
                confirmButton = { TextButton(onClick = { GatewayRuntime.removeConnection(id); removal = null; back(); notify("已移除服务") }) { Text("移除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { removal = null }) { Text("取消") } })
        }
    }
}
