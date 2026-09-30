package com.powercess.mbrain.capability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import io.droidmcp.core.*

/** Capabilities implemented by the MBrain process without Shizuku or Root. */
object AppCapabilityTools {
    fun all(context: Context): List<McpTool> = listOf(clipboardGet(context), clipboardSet(context), networkStatus(context))

    private fun clipboardGet(context: Context) = object : McpTool {
        override val name = "clipboard_get"
        override val description = "Read the current clipboard through the normal Android ClipboardManager API."
        override val annotations = ToolAnnotations(readOnlyHint = true)
        override val parameters = emptyList<ToolParameter>()
        override suspend fun execute(params: Map<String, Any>): ToolResult = try {
            val manager = context.getSystemService(ClipboardManager::class.java)
            val clip = manager?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            ToolResult.success(mapOf("available" to (text != null), "text" to (text ?: ""), "source" to "app"))
        } catch (error: SecurityException) {
            ToolResult.success(mapOf("available" to false, "text" to "", "source" to "app", "reason" to "system_denied"))
        }
    }

    private fun clipboardSet(context: Context) = object : McpTool {
        override val name = "clipboard_set"
        override val description = "Set the current clipboard through the normal Android ClipboardManager API."
        override val annotations = ToolAnnotations(destructiveHint = true)
        override val parameters = listOf(ToolParameter("value", "Clipboard text", ParameterType.STRING, true))
        override suspend fun execute(params: Map<String, Any>): ToolResult = try {
            val value = params["value"] as? String ?: return ToolResult.error("value is required")
            require(value.length <= 16000 && '\u0000' !in value) { "Text too long or contains NUL" }
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("MBrain", value))
            ToolResult.success(mapOf("ok" to true, "source" to "app"))
        } catch (error: SecurityException) { ToolResult.error("clipboard_denied", "System denied clipboard write") }
    }

    private fun networkStatus(context: Context) = object : McpTool {
        override val name = "net_status"
        override val description = "Read active network transport through ConnectivityManager."
        override val annotations = ToolAnnotations(readOnlyHint = true)
        override val parameters = emptyList<ToolParameter>()
        override suspend fun execute(params: Map<String, Any>): ToolResult {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            val caps = network?.let { manager.getNetworkCapabilities(it) }
            return ToolResult.success(mapOf("available" to (network != null), "validated" to (caps?.hasCapability(16) == true),
                "wifi" to (caps?.hasTransport(1) == true), "cellular" to (caps?.hasTransport(0) == true),
                "vpn" to (caps?.hasTransport(4) == true), "api" to Build.VERSION.SDK_INT, "source" to "app"))
        }
    }
}
