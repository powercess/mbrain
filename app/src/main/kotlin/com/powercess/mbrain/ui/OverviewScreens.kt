package com.powercess.mbrain.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.powercess.mbrain.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.powercess.mbrain.data.*
import com.powercess.mbrain.gateway.*

@Composable
internal fun HomeScreen(status: GatewayStatus, config: GatewayConfig, start: () -> Unit, stop: () -> Unit,
    open: (String) -> Unit, tab: (Int) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(status.running, status.error) { busy = false }
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(5000); busy = false } }
    ScreenList {
        item { SectionLabel("能力概览") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile("可用工具", if (status.running) status.tools.size.toString() else "—", Icons.Outlined.Build,
                    Modifier.weight(1f)) { open("tools:") }
                SummaryTile("MCP 服务", "${status.connections.values.count { it.state == "已连接" }} / ${config.connections.size}",
                    Icons.Outlined.Hub, Modifier.weight(1f), green = true) { tab(2) }
            }
        }
        item {
            Group(card = true) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("服务网关", style = MaterialTheme.typography.titleMedium)
                        StatusPill(if (status.running) "已开启" else "已关闭", status.running)
                    }
                    FilledIconButton(
                        onClick = { busy = true; if (status.running) stop() else start() },
                        enabled = !busy,
                        modifier = Modifier.size(64.dp).semantics {
                            contentDescription = if (busy) "正在处理网关" else if (status.running) "关闭网关" else "开启网关"
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (status.running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = if (status.running) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(26.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                        else Icon(if (status.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, green: Boolean = false, click: () -> Unit) {
    val accent = if (green) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Surface(onClick = click, modifier = modifier, shape = RoundedCornerShape(20.dp),
        color = if (green) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (green) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = accent)
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun capabilityName(id: String) = when (id) { "root" -> "Root"; "shizuku" -> "Shizuku"; "apps" -> "应用管理"; else -> "设备信息" }
internal fun capabilityState(ready: Boolean, enabled: Boolean, activation: ActivationState) = when { activation.checking -> "正在检查"; activation.error != null -> "未启用"; enabled && ready -> "已启用"; enabled -> "不可用"; else -> "未启用" }

@Composable
internal fun CapabilitiesScreen(status: GatewayStatus, config: GatewayConfig, open: (String) -> Unit) {
    ScreenList {
        item { SectionLabel("执行通道", "打开开关即可申请权限并启用") }
        item {
            Group {
                ActionRow("Root", "超级用户执行权限", Icons.Outlined.AdminPanelSettings, { open("cap:root") },
                    trailing = { StatusPill(capabilityState(status.rootReady, config.rootEnabled, status.rootActivation), status.rootReady && config.rootEnabled) })
                GroupDivider()
                ActionRow("Shizuku", "通过 Shizuku 访问系统", Icons.Outlined.Bolt, { open("cap:shizuku") },
                    trailing = { StatusPill(capabilityState(status.shizukuReady, config.shizukuEnabled, status.shizukuActivation), status.shizukuReady && config.shizukuEnabled) })
            }
        }
        item { SectionLabel("基础能力") }
        item {
            Group {
                ActionRow("应用管理", "查看应用、详情与启动", Icons.Outlined.Apps, { open("cap:apps") })
                GroupDivider()
                ActionRow("设备信息", "电量、存储与网络", Icons.Outlined.PhoneAndroid, { open("cap:device") })
            }
        }
        item { SectionLabel("工具") }
        item { Group { ActionRow("浏览工具目录", if (status.running) "${status.tools.size} 个工具可供调用" else "启动网关后查看", Icons.Outlined.Search, { open("tools:") }) } }
    }
}

@Composable
internal fun CapabilityScreen(id: String, status: GatewayStatus, config: GatewayConfig, open: (String) -> Unit,
    notify: (String) -> Unit) {
    val privileged = id == "root" || id == "shizuku"
    val ready = when (id) { "root" -> status.rootReady; "shizuku" -> status.shizukuReady; else -> true }
    val enabled = when (id) { "root" -> config.rootEnabled; "shizuku" -> config.shizukuEnabled; "apps" -> config.appsEnabled; else -> true }
    val name = capabilityName(id)
    val source = when (id) { "apps" -> "应用"; "device" -> "设备"; else -> name }
    val activation = when (id) { "root" -> status.rootActivation; "shizuku" -> status.shizukuActivation; else -> ActivationState() }
    ScreenList {
        item { SectionLabel("提供给 Agent") }
        item {
            Group {
                ActionRow(if (id == "device") "基础工具始终启用" else "启用$name", if (id == "device") "仅提供只读设备信息" else if (activation.checking) "正在检查权限，请完成授权" else if (privileged && (!enabled || !ready)) "打开时自动检查并申请权限" else "允许客户端调用这组工具", Icons.Outlined.PowerSettingsNew,
                    trailing = {
                        if (id == "device") Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                        else Switch(modifier = Modifier.semantics { contentDescription = "启用$name" }, checked = enabled && ready, enabled = !activation.checking, onCheckedChange = { value ->
                            if (privileged) GatewayRuntime.setCapabilityEnabled(if (id == "root") ExecutionMode.ROOT else ExecutionMode.SHIZUKU, value)
                            else { GatewayRuntime.updateConfig { it.copy(appsEnabled = value) }; notify(if (value) "已启用$name" else "已关闭$name") }
                        })
                    })
                GroupDivider()
                ActionRow("查看工具", if (status.running) "${status.tools.count { it.source == source }} 个已注册工具" else "启动网关后查看", Icons.Outlined.Build, { open("tools:$source") })
            }
        }
        activation.error?.let { error -> item { Note(error, error = true) } }
        if (id == "apps") item { Note("安装、卸载、停止应用与文件操作可在 Root 或 Shizuku 中启用。") }
    }
}

@Composable
internal fun SettingsScreen(appearance: String, open: (String) -> Unit, theme: () -> Unit, tunnelCount: Int, connectedCount: Int) {
    ScreenList {
        item { SectionLabel("连接") }
        item { Group {
            ActionRow("地址与凭据", "供 Agent 连接本机网关", Icons.Outlined.Key, { open("credentials") })
            GroupDivider()
            ActionRow("远程访问", if (tunnelCount == 0) "未配置" else "$connectedCount / $tunnelCount 已连接", Icons.Outlined.Public, { open("remote") })
            GroupDivider()
            ActionRow("使用说明", "电脑连接、运行方式与限制", Icons.Outlined.HelpOutline, { open("help") })
        } }
        item { SectionLabel("偏好") }
        item { Group { ActionRow("外观", when (appearance) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }, Icons.Outlined.Palette, theme) } }
        item { SectionLabel("应用") }
        item { Group {
            ActionRow("运行记录", "最近的连接与调用", Icons.Outlined.History, { open("activity") })
            GroupDivider()
            ActionRow("关于 MBrain", "版本与项目信息", Icons.Outlined.Info, { open("about") })
        } }
    }
}

@Composable
internal fun ActivityScreen(status: GatewayStatus) {
    ScreenList {
        if (status.events.isEmpty()) item { EmptyState(Icons.Outlined.History, "还没有运行记录", "启动网关或连接服务后，活动会显示在这里。") }
        else {
            item { SectionLabel("最近 ${status.events.size} 条", "最新在前 · 仅保留本次进程内的记录") }
            itemsIndexed(status.events) { _, event -> Group {
                ActionRow(event, icon = if (event.contains("失败")) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle)
            } }
        }
    }
}

@Composable
internal fun AboutScreen() {
    ScreenList {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
                    Image(painterResource(R.drawable.ic_mbrain_brand), null, Modifier.size(96.dp).padding(6.dp))
                }
                Text("MBrain", style = MaterialTheme.typography.titleLarge)
                Text("手机能力，统一连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(com.powercess.mbrain.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Group {
            ActionRow("原生 Android", "Kotlin · Jetpack Compose", Icons.Outlined.Android)
            GroupDivider()
            ActionRow("MCP 网关", "基于 droid-mcp", Icons.Outlined.Hub)
        } }
    }
}

@Composable
internal fun HelpScreen(status: GatewayStatus, copy: (String, String) -> Unit) {
    val command = status.port?.let { "adb forward tcp:$it tcp:$it" }
    ScreenList {
        item { SectionLabel("从电脑连接") }
        item { Group { ActionRow("转发网关端口", command ?: "启动网关后获取", Icons.Outlined.Computer,
            command?.let { { copy("ADB 命令", it) } }, trailing = { if (command != null) Icon(Icons.Outlined.ContentCopy, null) }) } }
        item { Note("完成端口转发后，使用连接页中的地址和 Bearer Token 配置客户端。") }
        item { SectionLabel("运行方式") }
        item { Note("网关仅监听本机地址。停止网关会断开 MCP 并关闭直接托管的进程；应用不会在开机后自动启动。") }
        item { SectionLabel("调用与记录") }
        item { Note("命令与外部 MCP 请求的超时约为 30 秒。运行记录仅保留最近 40 条，应用进程退出后清空。") }
    }
}
