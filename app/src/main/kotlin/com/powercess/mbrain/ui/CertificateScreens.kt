package com.powercess.mbrain.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powercess.mbrain.remote.TunnelCertificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

@Composable
internal fun CertificatesScreen(certificates: List<TunnelCertificate>, edit: (String) -> Unit, remove: (String) -> Unit) {
    ScreenList(groupedRows = true) {
        item { SecondaryAction("导入证书", onClick = { edit("new") }) }
        if (certificates.isEmpty()) item { EmptyState("暂无证书") }
        groupedItems(certificates, key = { it.id }) { certificate ->
            val details = remember(certificate) {
                runCatching { "${certificate.expiryMessage()} · 到期 ${DateFormat.getDateInstance().format(certificate.chain().first().notAfter)}" }.getOrDefault("证书不可读")
            }
            Column {
                ActionRow(certificate.name, details, Icons.Outlined.VerifiedUser, { edit(certificate.id) })
                GroupDivider()
                ActionRow(if (certificate.keyPem.isBlank()) "仅证书 / CA" else "包含私钥", null, Icons.Outlined.Key,
                    trailing = { IconButton(onClick = { remove(certificate.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除${certificate.name}") } })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CertificateEditor(initial: TunnelCertificate, dismiss: () -> Unit, save: (TunnelCertificate) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var pem by rememberSaveable { mutableStateOf(initial.pem) }
    var keyPem by rememberSaveable { mutableStateOf(initial.keyPem) }
    var importingKey by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val dirty = name != initial.name || pem != initial.pem || keyPem != initial.keyPem
    val close = { if (!busy) { if (dirty) discard = true else dismiss() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= 65536) { "文件超过 64 KiB" }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    bytes.toString(Charsets.UTF_8)
                }
                if (importingKey) keyPem = text else pem = text
                error = null
            } catch (_: Exception) { error = "读取失败，请选择不超过 64 KiB 的 PEM 文件" }
            finally { busy = false }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BackHandler { close() }
        Scaffold(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { TopAppBar(title = { Text("证书配置") }, navigationIcon = {
                IconButton(onClick = close, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭编辑") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), windowInsets = WindowInsets(0, 0, 0, 0)) },
            bottomBar = { EditorSaveBar(if (busy) "处理中…" else "保存证书", enabled = !busy) {
                scope.launch {
                    busy = true
                    try {
                        val next = initial.copy(name = name.trim(), pem = pem.trim(), keyPem = keyPem.trim())
                        withContext(Dispatchers.IO) { next.validate() }
                        save(next)
                    } catch (e: Exception) { error = e.message ?: "保存失败" }
                    finally { busy = false }
                }
            } },
            containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) { ScreenList {
                error?.let { item(key = "error") { Note(it, error = true) } }
                item(key = "name") { TunnelField("证书名称", name, { name = it }) }
                item(key = "certificate") { SecondaryAction(if (pem.isBlank()) "选择证书链文件" else "替换证书链文件", enabled = !busy,
                    onClick = { importingKey = false; picker.launch(arrayOf("*/*")) }) }
                if (pem.isNotBlank()) item(key = "details") {
                    val info = remember(pem) { runCatching {
                        val cert = initial.copy(pem = pem).chain().first()
                        "${cert.subjectX500Principal.name}\n到期：${DateFormat.getDateInstance().format(cert.notAfter)}"
                    }.getOrDefault("文件已读取，保存时验证证书格式") }
                    Note(info)
                }
                item(key = "key") { SecondaryAction(if (keyPem.isBlank()) "选择私钥文件（CA 可省略）" else "替换私钥文件", enabled = !busy,
                    onClick = { importingKey = true; picker.launch(arrayOf("*/*")) }) }
                if (keyPem.isNotBlank()) item(key = "key-present") { Group { ActionRow("私钥已导入", "保存时检查与证书是否匹配", Icons.Outlined.Key,
                    trailing = { TextButton(onClick = { keyPem = "" }, enabled = !busy) { Text("清除") } }) } }
                item(key = "help") { Note("支持 PEM X.509 证书链，以及未加密的 RSA / EC PKCS#8 或 RSA PKCS#1 私钥。证书与私钥加密保存在应用内；替换后仅重连引用它的隧道。") }
            } }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
            confirmButton = { TextButton(onClick = dismiss) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    }
}
