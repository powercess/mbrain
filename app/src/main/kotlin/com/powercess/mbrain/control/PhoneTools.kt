package com.powercess.mbrain.control

import io.droidmcp.core.*
import io.droidmcp.shell.ShellBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One UI automation connection at a time, shared by both privileged backends. */
object PhoneTools {
    private val session = Mutex()
    fun all(shell: ShellBackend, apkPath: String): List<McpTool> = operations.map { (name, spec) ->
        object : McpTool {
            override val name = name
            override val description = spec.description
            override val parameters = spec.parameters
            override val annotations = ToolAnnotations(readOnlyHint = spec.readOnly, destructiveHint = !spec.readOnly, openWorldHint = name == "net_diagnose")
            override suspend fun execute(params: Map<String, Any>): ToolResult = try {
                val unknown = params.keys - parameters.map { it.name }.toSet()
                require(unknown.isEmpty()) { "Unknown arguments: ${unknown.joinToString()}" }
                parameters.forEach { parameter ->
                    val value = params[parameter.name]
                    require(value != null || !parameter.required) { "${parameter.name} is required" }
                    if (value != null) require(when (parameter.type) {
                        ParameterType.STRING -> value is String
                        ParameterType.BOOLEAN -> value is Boolean
                        ParameterType.INTEGER -> value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()
                        else -> false
                    }) { "Invalid ${parameter.name}" }
                }
                params.values.filterIsInstance<String>().forEach { require(it.length <= 16000 && '\u0000' !in it) { "Text too long or contains NUL" } }
                session.withLock {
                    val response = shell.exec("/system/bin/env", listOf("CLASSPATH=$apkPath", "/system/bin/app_process",
                        "/system/bin", "com.powercess.mbrain.control.PhoneControlMain", name, buildJsonObject {
                            params.forEach { (key, value) -> put(key, when (value) {
                                is Boolean -> JsonPrimitive(value)
                                is Number -> JsonPrimitive(value)
                                else -> JsonPrimitive(value.toString())
                            }) }
                        }.toString()))
                    if (!response.isSuccess) ToolResult.error("phone_control_failed", response.stderr.take(2000))
                    else decodePhoneReply(response.stdout)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { ToolResult.error("phone_control_failed", error.message) }
        }
    }

    private data class Spec(val description: String, val parameters: List<ToolParameter>, val readOnly: Boolean = false)
    private fun string(name: String, description: String, required: Boolean = false) = ToolParameter(name, description, ParameterType.STRING, required)
    private fun number(name: String, description: String, required: Boolean = false) = ToolParameter(name, description, ParameterType.INTEGER, required)
    private val selector = listOf(
        string("text", "Exact node text; at least one of text/resource_id/description required"),
        string("resource_id", "Full Android view resource ID"),
        string("description", "Accessibility content description"),
        string("package", "Restrict selector to this package"),
        ToolParameter("contains", "Substring match for text/description; default false", ParameterType.BOOLEAN),
    )
    private val actionSelector = selector + number("index", "Zero-based occurrence among matches; required when ambiguous (NOT dump node index)")
    private val coordinates = listOf(number("x", "Default-display pixel X; use coordinates OR selector"), number("y", "Default-display pixel Y"))
    private val operations = linkedMapOf(
        "ui_dump" to Spec("Read the active window's node tree, text, bounds and actions. Paginated live snapshot; default display. Requires Root/Shizuku, no accessibility service setup.",
            listOf(number("offset", "Node offset, default 0"), number("limit", "Page size 1..60, default 50")), true),
        "ui_click" to Spec("Click a visible node (or nearest clickable ancestor), or pixel coordinates. Ambiguous selectors fail unless index supplied.", actionSelector + coordinates),
        "ui_long_click" to Spec("Long-click a visible node (or actionable ancestor), or hold coordinates for 800ms.", actionSelector + coordinates),
        "ui_set_text" to Spec("Replace an editable node's text with Unicode, including Chinese and emoji. Empty value clears it; does not submit. Use ui_wait/dump to verify.",
            actionSelector + string("value", "Replacement text, up to 16000 characters", true)),
        "ui_swipe" to Spec("Swipe between default-display pixel coordinates. Coordinates follow current screen rotation.",
            listOf(number("x1", "Start X", true), number("y1", "Start Y", true), number("x2", "End X", true), number("y2", "End Y", true), number("duration_ms", "50..3000; default 350"))),
        "ui_navigate" to Spec("Perform Android navigation: back, home, recents, notifications or quick_settings.",
            listOf(string("action", "back | home | recents | notifications | quick_settings", true))),
        "ui_wait" to Spec("Wait for a visible node to appear or disappear; polls a fresh tree every 250ms. Timeout is an error, not success.",
            selector + listOf(string("state", "present (default) or absent"), number("timeout_ms", "0..15000, default 5000")), true),
        "clipboard_get" to Spec("Read first clipboard text item for the current Android user. Returns available=false when empty or system-denied; non-text content is not dereferenced.", emptyList(), true),
        "clipboard_set" to Spec("Replace clipboard text for the current Android user, including Chinese and emoji.", listOf(string("value", "Text, up to 16000 characters", true))),
        "net_diagnose" to Spec("Diagnose DNS resolution and TCP connectivity from the selected Root/Shizuku backend. Returns per-stage status; DNS/TCP failure is a diagnostic result. No HTTP request is sent.",
            listOf(string("host", "Hostname or IP without scheme/path", true), number("port", "TCP port 1..65535; default 443")), true),
    )
}

internal fun decodePhoneReply(output: String): ToolResult {
    val envelope = output.lineSequence().lastOrNull { it.startsWith("MBRAIN_RESULT:") }
        ?: return ToolResult.error("phone_control_protocol", "Helper returned no result")
    return try {
        val reply = Json.parseToJsonElement(envelope.removePrefix("MBRAIN_RESULT:")).jsonObject
        if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) ToolResult.error("phone_control_failed", reply["error"]?.jsonPrimitive?.content)
        else ToolResult.success(reply.getValue("data").jsonObject.mapValues { jsonValue(it.value) })
    } catch (_: Exception) { ToolResult.error("phone_control_protocol", "Invalid helper result") }
}

private fun jsonValue(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.mapValues { jsonValue(it.value) }
    is JsonArray -> value.map(::jsonValue)
    is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
}
