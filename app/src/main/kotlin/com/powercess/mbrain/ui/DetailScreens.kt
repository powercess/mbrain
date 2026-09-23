package com.powercess.mbrain.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.powercess.mbrain.gateway.*

private val toolLabels = mapOf(
    "get_device_info" to "设备信息", "get_battery_info" to "电池状态", "get_connectivity" to "网络连接", "get_storage_info" to "存储空间",
    "list_installed_apps" to "应用列表", "get_app_info" to "应用详情", "launch_app" to "启动应用", "exec" to "执行命令",
    "file_list" to "浏览目录", "file_read" to "读取文件", "file_write" to "写入文件", "file_mkdir" to "创建目录",
    "file_copy" to "复制文件", "file_move" to "移动文件", "file_delete" to "删除文件",
)
internal fun toolTitle(name: String): String = toolLabels[name.removePrefix("root_").removePrefix("shizuku_")] ?: name

@Composable
internal fun ToolsScreen(status: GatewayStatus, source: String, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(status.tools, source, query) {
        status.tools.filter { tool ->
            (source.isEmpty() || if (source.startsWith('@')) tool.name.startsWith("mcp_${source.drop(1).take(8)}_") else tool.source == source) &&
                (query.isBlank() || listOf(tool.name, tool.description, tool.source, toolTitle(tool.name)).any { it.contains(query, true) })
        }
    }
    if (!status.running) {
        ScreenList {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("启动网关后查看工具", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OutlinedTextField(query, { query = it }, Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索名称或功能") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } },
                singleLine = true, shape = MaterialTheme.shapes.large)
        }
        ScreenList(groupedRows = true) {
            if (matches.isEmpty()) item { EmptyState(if (query.isNotBlank()) "没有匹配的工具" else "暂无工具") }
            else {
                item { ListSection("共 ${matches.size} 个工具", firstSection = true) }
                groupedItems(matches, key = { it.name }) { tool ->
                    ActionRow(toolTitle(tool.name), tool.source, Icons.Outlined.Build, { open("tool:${tool.name}") })
                }
            }
        }
    }
}

@Composable
internal fun ToolScreen(tool: ToolInfo?, copy: (String, String) -> Unit) {
    ScreenList {
        if (tool == null) item { EmptyState("工具当前不可用", "返回列表刷新查看") }
        else {
            item { Group { ActionRow(toolTitle(tool.name), tool.source, Icons.Outlined.Build) } }
            item { SectionLabel("功能说明") }
            item { ContentCard(tool.description.ifBlank { "暂无说明" }) }
            item { SectionLabel("调用名称") }
            item { Group(card = true) {
                SelectionContainer { Text(tool.name, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
                GroupDivider()
                ActionRow("复制调用名称", icon = Icons.Outlined.ContentCopy, onClick = { copy("工具名称", tool.name) }, trailing = {})
            } }
        }
    }
}

@Composable
internal fun CredentialsScreen(status: GatewayStatus, copy: (String, String) -> Unit,
    tunnels: List<com.powercess.mbrain.remote.TunnelConfig>, open: (String) -> Unit) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    var reset by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status.token) { reveal = false }
    ScreenList {
        item { SectionLabel("连接地址", firstSection = true) }
        item { Group { ActionRow("本机 MCP", status.endpoint ?: "启动网关后获取", Icons.Outlined.Link,
            status.endpoint?.let { { copy("连接地址", it) } }, trailing = { if (status.endpoint != null) Icon(Icons.Outlined.ContentCopy, null) }) } }
        tunnels.filter { it.publicUrl.isNotBlank() }.forEach { tunnel ->
            item(key = tunnel.id) { Group { ActionRow(tunnel.name, tunnel.publicUrl, Icons.Outlined.Public,
                { open("tunnel:${tunnel.id}") }) } }
        }
        item { SectionLabel("访问凭据") }
        if (status.token == null) item { EmptyState("启动网关后查看 Token") }
        else {
            item { Group(card = true) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Bearer Token", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(if (reveal) status.token else "•••• •••• •••• ••••", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { copy("Token", status.token) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("复制 Token") }
                        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示") }
                    }
                }
            } }
            item { Note("Token 固定保存在本机，重启不会更换；仅主动重置后改变。将它填入客户端的 Bearer 认证，即可调用已启用的全部工具。") }
            item { TextButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth()) { Text("重置访问凭据", color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("重置访问凭据？") },
        text = { Text("所有客户端需要更新 Token。") },
        confirmButton = { TextButton(onClick = { GatewayRuntime.rotateAccessToken(); reset = false }) { Text("重置") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}
