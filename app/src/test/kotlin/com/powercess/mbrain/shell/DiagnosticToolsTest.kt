package com.powercess.mbrain.shell

import io.droidmcp.shell.ShellBackend
import io.droidmcp.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DiagnosticToolsTest {
    private class Shell : ShellBackend {
        override val name = "fake"
        override fun isAvailable() = true
        val calls = mutableListOf<Pair<String, List<String>>>()
        var reply = ShellResult.ofText(0, "ok", "")
        override suspend fun exec(command: String, args: List<String>): ShellResult {
            calls += command to args
            return reply
        }
        fun tool(name: String) = DiagnosticTools.all(this).first { it.name == name }
    }
    @Test fun `log queries are finite and combine validated filters`() = runTest {
        val shell = Shell()
        val result = shell.tool("logs_query").execute(mapOf("pid" to 42L, "uid" to 10000L, "tag" to "App", "lines" to 12, "level" to "E"))
        assertTrue(result.isSuccess)
        val args = shell.calls.single().second
        assertTrue(args.containsAll(listOf("-d", "-t", "12", "--pid=42", "--uid=10000", "App:E", "*:S")))
    }
    @Test fun `invalid command arguments cannot reach shell`() = runTest {
        val shell = Shell()
        assertFalse(shell.tool("logs_query").execute(mapOf("tag" to "x; id")).isSuccess)
        assertFalse(shell.tool("logs_query").execute(mapOf("lines" to 0)).isSuccess)
        assertFalse(shell.tool("logs_query").execute(mapOf("pid" to 1.5)).isSuccess)
        assertFalse(shell.tool("appops_set").execute(mapOf("package" to "a.b;id", "op" to "CAMERA", "mode" to "allow")).isSuccess)
        assertFalse(shell.tool("appops_set").execute(mapOf("package" to "a.b", "op" to "CAMERA", "mode" to "typo")).isSuccess)
        assertTrue(shell.calls.isEmpty())
    }
    @Test fun `timestamp queries retain a maximum record count`() = runTest {
        val shell = Shell()
        assertTrue(shell.tool("logs_query").execute(mapOf("since" to "09-24 12:30:00.000", "lines" to 7)).isSuccess)
        val args = shell.calls.single().second
        assertTrue(args.containsAll(listOf("-d", "-T", "09-24 12:30:00.000", "-m", "7")))
        assertFalse(args.contains("-t"))
    }
    @Test fun `AppOps writes specify user and read back same operation`() = runTest {
        val shell = Shell()
        assertTrue(shell.tool("appops_set").execute(mapOf("package" to "a.b", "op" to "CAMERA", "mode" to "default", "user" to 10)).isSuccess)
        assertEquals(listOf("appops", "set", "--user", "10", "a.b", "CAMERA", "default"), shell.calls[0].second)
        assertEquals(listOf("appops", "get", "--user", "10", "a.b", "CAMERA"), shell.calls[1].second)
    }
    @Test fun `shell failures are surfaced and AppOps write does not continue`() = runTest {
        val shell = Shell()
        shell.reply = ShellResult.ofText(1, "", "denied")
        assertFalse(shell.tool("appops_set").execute(mapOf("package" to "a.b", "op" to "CAMERA", "mode" to "allow")).isSuccess)
        assertEquals(1, shell.calls.size)
        shell.reply = ShellResult.ofText(0, "", "Error: unknown operation")
        assertFalse(shell.tool("appops_get").execute(mapOf("package" to "a.b")).isSuccess)
    }
    @Test fun `process filtering excludes similar package names and reports truncation`() = runTest {
        val shell = Shell()
        shell.reply = ShellResult.ofText(0, "PID UID RSS NAME\n1 10000 42 a.b\n2 10000 21 a.b:remote\n3 10001 11 a.bc\n", "")
        val result = shell.tool("proc_list").execute(mapOf("package" to "a.b", "limit" to 1))
        val data = result.data!!
        assertEquals(2, data["total"])
        assertEquals(true, data["truncated"])
        assertEquals(42L, ((data["processes"] as List<*>).single() as Map<*, *>)["rss_kib"])
    }
}
