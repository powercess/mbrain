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
}
