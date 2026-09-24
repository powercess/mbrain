package com.powercess.mbrain.control

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.powercess.mbrain.MainActivity
import com.powercess.mbrain.gateway.GatewayRuntime
import com.powercess.mbrain.gateway.MBrainService
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in real Shizuku + authenticated MCP test. Credentials never leave the device. */
@RunWith(AndroidJUnit4::class)
class CapabilityGatewayTest {
    @Test fun shizukuToolsAreCallableThroughAuthenticatedGateway() {
        assumeTrue("Enable with -e capabilityGateway true after granting test app Shizuku",
            InstrumentationRegistry.getArguments().getString("capabilityGateway") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.powercess.mbrain.dev")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await { GatewayRuntime.status.value.shizukuReady }
            assertTrue("Enable Shizuku in the test app first", GatewayRuntime.config.value.shizukuEnabled)
            instrumentation.runOnMainSync { context.startForegroundService(Intent(context, MBrainService::class.java)) }
            await { GatewayRuntime.status.value.running && GatewayRuntime.status.value.tools.any { it.name == "shizuku_ui_dump" } }
            val status = GatewayRuntime.status.value
            var session: String? = null
            var id = 0
            fun rpc(method: String, params: JsonObject): JsonObject {
                val connection = URL(status.endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 5000
                    connection.readTimeout = 30000
                    connection.setRequestProperty("Authorization", "Bearer ${status.token}")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Accept", "application/json, text/event-stream")
                    session?.let { connection.setRequestProperty("Mcp-Session-Id", it) }
                    val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", ++id); put("method", method); put("params", params) }
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    assertEquals("HTTP status for $method", 200, connection.responseCode)
                    connection.getHeaderField("Mcp-Session-Id")?.let { session = it }
                    val response = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
                    assertFalse("RPC error for $method", response.containsKey("error"))
                    return response.getValue("result").jsonObject
                } finally { connection.disconnect() }
            }
            rpc("initialize", buildJsonObject {
                put("protocolVersion", "2024-11-05"); put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject { put("name", "capability-device-test"); put("version", "1") })
            })
            val names = rpc("tools/list", buildJsonObject {}).getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            val required = listOf("ui_dump", "ui_click", "ui_long_click", "ui_set_text", "ui_swipe", "ui_navigate", "ui_wait", "clipboard_get", "clipboard_set",
                "logs_query", "appops_get", "appops_set", "proc_list", "app_resource_usage", "net_status", "net_diagnose")
            required.forEach { assertTrue("Missing tool $it", "shizuku_$it" in names) }
            fun call(name: String, params: JsonObject = buildJsonObject {}) {
                val result = rpc("tools/call", buildJsonObject { put("name", "shizuku_$name"); put("arguments", params) })
                assertNotEquals("Tool failed: $name", true, result["isError"]?.jsonPrimitive?.booleanOrNull)
                assertTrue("Missing tool content: $name", result.getValue("content").jsonArray.isNotEmpty())
            }
            call("ui_dump", buildJsonObject { put("limit", 5) })
            call("ui_wait", buildJsonObject { put("text", "MBrain"); put("package", context.packageName); put("timeout_ms", 2000) })
            call("logs_query", buildJsonObject { put("tag", "MBrainSmoke"); put("lines", 5) })
            call("appops_get", buildJsonObject { put("package", context.packageName); put("op", "VIBRATE") })
            call("proc_list", buildJsonObject { put("package", context.packageName) })
            call("app_resource_usage", buildJsonObject { put("package", context.packageName) })
            call("net_status")
            call("net_diagnose", buildJsonObject { put("host", "localhost"); put("port", status.port!!) })
            call("ui_click", buildJsonObject { put("text", "能力"); put("package", context.packageName) })
            call("ui_wait", buildJsonObject { put("text", "手机能力"); put("timeout_ms", 2000) })
        } finally {
            instrumentation.runOnMainSync { context.stopService(Intent(context, MBrainService::class.java)); activity.finish() }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (!condition()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "Gateway/Shizuku did not become ready" }
            Thread.sleep(100)
        }
    }
}
