package com.powercess.mbrain.shell

import io.droidmcp.shell.ShellBackend
import io.droidmcp.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PrivilegedToolsTest {
    private class FakeShell : ShellBackend {
        override val name = "test"
        override fun isAvailable() = true
        var command = ""
        var args = emptyList<String>()
        override suspend fun exec(command: String, args: List<String>): ShellResult {
            this.command = command; this.args = args
            return ShellResult.ofText(0, "ok", "")
        }
    }
    @Test fun `file paths are passed literally and writes default to no clobber`() = runTest {
        val shell = FakeShell()
        val path = "/sdcard/a'; touch injected; #"
        FileOperationTool(shell, "root", "list").execute(mapOf("path" to path))
        assertEquals(listOf("-la", "--", path), shell.args)
        FileOperationTool(shell, "root", "write").execute(mapOf("path" to path, "text" to "hello"))
        assertTrue(shell.args[1].startsWith("set -C; "))
        assertTrue(shell.args[1].endsWith(shellQuote(path)))
        assertFalse(shell.args[1].contains("hello"))
    }
    @Test fun `relative paths are rejected`() = runTest {
        try {
            FileOperationTool(FakeShell(), "root", "delete").execute(mapOf("path" to "../file"))
            fail("relative path accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
