package com.powercess.mbrain.remote

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

class FrpcControllerTest {
    private class FakeProcess : Process() {
        private val output = PipedOutputStream()
        private val input = PipedInputStream(output)
        @Volatile private var alive = true
        fun line(text: String) { output.write((text + "\n").toByteArray()); output.flush() }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun isAlive() = alive
        override fun destroy() { alive = false; output.close() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!check() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Condition timed out", check())
    }

    @Test fun `simultaneous tunnels stop independently and follow changed local port`() {
        val directory = Files.createTempDirectory("frpc-test").toFile()
        val binary = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val processes = CopyOnWriteArrayList<FakeProcess>()
        val configs = CopyOnWriteArrayList<String>()
        val states = ConcurrentHashMap<String, TunnelStatus>()
        val controller = FrpcController(binary, directory, { id, state -> states[id] = state }, { _, file ->
            configs.add(file.readText())
            FakeProcess().also { processes.add(it) }
        })
        val first = TunnelConfig(name = "one", server = "frp.example.com", remotePort = 18081, enabled = true)
        val second = TunnelConfig(name = "two", server = "frp.example.com", remotePort = 18082, enabled = true)
        try {
            controller.reconcile(listOf(first, second), null)
            assertTrue(processes.isEmpty())
            controller.reconcile(listOf(first, second), 41234)
            await { processes.size == 2 }
            processes.forEach { it.line("[proxy] start proxy success") }
            await { states.values.count { it.phase == TunnelPhase.CONNECTED } == 2 }
            controller.reconcile(listOf(first.copy(enabled = false), second), 41234)
            await { processes.count { it.isAlive } == 1 }
            assertEquals(TunnelPhase.OFF, states[first.id]!!.phase)
            controller.reconcile(listOf(second), 41235)
            await { processes.size == 3 }
            assertTrue(configs.last().contains("\"localPort\":41235"))
            assertFalse(processes[0].isAlive)
            assertFalse(processes[1].isAlive)
            processes.last().destroy()
            await { processes.size == 4 }
            assertTrue(processes.last().isAlive)
            controller.reconcile(listOf(second), null)
            await { processes.none { it.isAlive } && directory.listFiles().orEmpty().isEmpty() }
            Thread.sleep(1100)
            assertEquals(4, processes.size)
        } finally { controller.close(); directory.deleteRecursively() }
    }

    @Test fun `custom service survives MCP shutdown and endpoint edits restart only its process`() {
        val directory = Files.createTempDirectory("frpc-independent-test").toFile()
        val binary = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val processes = CopyOnWriteArrayList<FakeProcess>()
        val configs = CopyOnWriteArrayList<String>()
        val controller = FrpcController(binary, directory, { _, _ -> }, { _, file ->
            configs.add(file.readText()); FakeProcess().also { processes.add(it) }
        })
        val mcp = TunnelConfig(name = "mcp", server = "frp.example.com", remotePort = 18081, enabled = true)
        val custom = TunnelConfig(name = "custom", server = "frp.example.com", remotePort = 18082,
            target = TunnelTarget.CUSTOM, localPort = 8787, enabled = true)
        try {
            controller.reconcile(listOf(custom, mcp), null)
            await { processes.size == 1 }
            val customProcess = processes.single()
            assertFalse(configs.single().contains("plugin"))
            controller.reconcile(listOf(custom, mcp), 8765)
            await { processes.size == 2 }
            val mcpProcess = processes.last()
            val replacement = custom.copy(localPort = 8788)
            controller.reconcile(listOf(replacement, mcp), 8765)
            await { processes.size == 3 }
            assertFalse(customProcess.isAlive)
            assertTrue(mcpProcess.isAlive)
            val replacementProcess = processes.last()
            controller.reconcile(listOf(replacement, mcp), null)
            await { !mcpProcess.isAlive }
            assertTrue(replacementProcess.isAlive)
            controller.close()
            await { processes.none { it.isAlive } && directory.listFiles().orEmpty().isEmpty() }
        } finally { controller.close(); directory.deleteRecursively() }
    }
    @Test fun `repeated retries retain one error until connection recovers`() {
        val directory = Files.createTempDirectory("frpc-retry-test").toFile()
        val binary = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val processes = CopyOnWriteArrayList<FakeProcess>()
        val states = ConcurrentHashMap<String, TunnelStatus>()
        val controller = FrpcController(binary, directory, { id, state -> states[id] = state }, { _, _ ->
            FakeProcess().also { processes.add(it) }
        })
        val tunnel = TunnelConfig(name = "retry", server = "frp.example.com", remotePort = 18080, enabled = true)
        try {
            controller.reconcile(listOf(tunnel), 8765)
            await { processes.size == 1 }
            val process = processes.single()
            repeat(5) {
                process.line("try to connect to server")
                process.line("connect to server error: EOF")
            }
            process.line("try to connect to server")
            process.line("login to server success")
            await { states[tunnel.id]?.serverConnected == true }
            val failed = states.getValue(tunnel.id)
            assertEquals("无法连接服务器，正在重试", failed.error)
            assertEquals(1, failed.events.count { it == failed.error })
            process.line("start proxy success")
            await { states[tunnel.id]?.phase == TunnelPhase.CONNECTED }
            assertNull(states.getValue(tunnel.id).error)
        } finally { controller.close(); directory.deleteRecursively() }
    }

}
