package com.powercess.mbrain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.*
import com.powercess.mbrain.remote.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerEditor(initial: TunnelServer, existing: Boolean, config: RemoteConfig, dismiss: () -> Unit, remove: () -> Unit, save: (TunnelServer) -> Unit) {
    val json = remember { Json { encodeDefaults = true } }
    var draft by rememberSaveable { mutableStateOf(json.encodeToString(TunnelServer.serializer(), initial)) }
    val value = remember(draft) { json.decodeFromString(TunnelServer.serializer(), draft) }
    fun update(next: TunnelServer) { draft = json.encodeToString(TunnelServer.serializer(), next) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var reveal by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val close = { if (value != initial || port != initial.port.toString()) discard = true else dismiss() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BackHandler { close() }
        Scaffold(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text("配置服务器") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭编辑") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), windowInsets = WindowInsets(0, 0, 0, 0)) },
            bottomBar = { EditorSaveBar("保存服务器") {
                try {
                    val next = value.copy(name = value.name.trim(), host = value.host.trim(), port = port.toIntOrNull() ?: 0,
                        tlsServerName = "", tlsCaId = "", tlsClientCertificateId = "")
                    config.saveServer(next); save(next)
                } catch (e: Exception) { error = e.message ?: "保存失败"; scope.launch { list.animateScrollToItem(0) } }
            } }) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                ScreenList(state = list) {
                    error?.let { item { Note(it, error = true) } }
                    item { TunnelField("服务器名称", value.name, { update(value.copy(name = it)) }) }
                    item { TunnelField("服务器地址", value.host, { update(value.copy(host = it)) }, KeyboardType.Uri, "frp.example.com") }
                    item { TunnelField("服务器端口", port, { port = it }, KeyboardType.Number) }
                    item { OutlinedTextField(value.token, { update(value.copy(token = it)) }, Modifier.fillMaxWidth(), label = { Text("服务器 Token（可选）") },
                        singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示或隐藏 Token") } }) }
                    if (existing) item { TextButton(onClick = {
                        runCatching { config.removeServer(initial.id); remove() }.onFailure {
                            error = it.message ?: "无法删除服务器"; scope.launch { list.animateScrollToItem(0) }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("删除服务器", color = MaterialTheme.colorScheme.error)
                    } }

                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
            confirmButton = { TextButton(onClick = dismiss) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    }
}
