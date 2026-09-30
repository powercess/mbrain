package com.powercess.mbrain.control

import io.droidmcp.shell.ShellBackend
import io.droidmcp.shell.ShellResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhoneToolsTest {
    private class Shell : ShellBackend {
        override val name = "fake"
        override fun isAvailable() = true
        val calls = mutableListOf<Pair<String, List<String>>>()
        override suspend fun exec(command: String, args: List<String>): ShellResult {
            calls += command to args
            return ShellResult.ofText(0, "MBRAIN_RESULT:{\"ok\":true,\"data\":{\"performed\":true}}", "")
        }
    }
    @Test fun `unicode and shell syntax are delivered as literal JSON argv`() = runTest {
        val shell = Shell()
        val tool = PhoneTools.all(shell, "/data/app/a b/base.apk").first { it.name == "ui_set_text" }
        val text = "中文😀\n'\"; $(touch /sdcard/injected)"
        assertTrue(tool.execute(mapOf("resource_id" to "app:id/input", "value" to text)).isSuccess)
        val (command, args) = shell.calls.single()
        assertEquals("/system/bin/env", command)
        assertEquals("CLASSPATH=/data/app/a b/base.apk", args.first())
        assertEquals(text, Json.parseToJsonElement(args.last()).jsonObject["value"]!!.jsonPrimitive.content)
        assertFalse(args.contains("-c"))
    }
    @Test fun `bad required types fractional numbers and unknown args never reach backend`() = runTest {
        val shell = Shell()
        val tools = PhoneTools.all(shell, "/base.apk").associateBy { it.name }
        assertFalse(tools.getValue("ui_set_text").execute(mapOf("text" to "field")).isSuccess)
        assertFalse(tools.getValue("clipboard_set").execute(mapOf("value" to 12)).isSuccess)
        assertFalse(tools.getValue("ui_click").execute(mapOf("x" to 1.5, "y" to 2)).isSuccess)
        assertFalse(tools.getValue("ui_navigate").execute(mapOf("action" to "back", "acton" to "home")).isSuccess)
        assertFalse(tools.getValue("clipboard_set").execute(mapOf("value" to "x\u0000y")).isSuccess)
        assertTrue(shell.calls.isEmpty())
    }
    @Test fun `helper failures and malformed output never become successful calls`() {
        assertFalse(decodePhoneReply("VM startup failed").isSuccess)
        assertFalse(decodePhoneReply("MBRAIN_RESULT:garbage").isSuccess)
        val result = decodePhoneReply("log line\nMBRAIN_RESULT:{\"ok\":false,\"error\":\"wait_timeout\"}")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("wait_timeout"))
    }
    @Test fun `nested node results and explicit null survive decoding`() {
        val result = decodePhoneReply("MBRAIN_RESULT:{\"ok\":true,\"data\":{\"nodes\":[{\"text\":\"中文\",\"bounds\":[1,2,3,4]}],\"next_offset\":null,\"truncated\":false}}")
        assertTrue(result.isSuccess)
        val data = result.data!!
        assertNull(data["next_offset"])
        assertEquals(false, data["truncated"])
        assertEquals("中文", ((data["nodes"] as List<*>).single() as Map<*, *>)["text"])
    }
}
