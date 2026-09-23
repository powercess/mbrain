package com.powercess.mbrain.remote

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.powercess.mbrain.gateway.GatewayRuntime
import com.powercess.mbrain.gateway.MBrainService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Run with scripts/smoke-frpc.py: a real frps is reached through adb reverse. */
@RunWith(AndroidJUnit4::class)
class FrpcDeviceTest {
    @Test fun realTcpFrpcSupportsSharedServerAndIndependentLifecycles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/smoke-frpc.py to provide the local frps fixture", args.containsKey("controlPort"))
        val context = instrumentation.targetContext
        val backend = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serve(backend)
        val tcpPort = args.getString("tcpPort")!!.toInt()
        val mcpPort = args.getString("mcpPort")!!.toInt()
        val server = TunnelServer(name = "integration", host = "127.0.0.1",
            port = args.getString("controlPort")!!.toInt(), token = args.getString("frpToken")!!)
        val base = TunnelConfig(name = "integration tcp", serverId = server.id,
            target = TunnelTarget.CUSTOM, localPort = backend.localPort, remotePort = tcpPort)
        val mcp = base.copy(id = newId(), name = "integration MCP", target = TunnelTarget.MCP, remotePort = mcpPort)
        val tunnels = listOf(base, mcp)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            main {
                GatewayRuntime.initialize(context)
                RemoteRuntime.initialize(context)
                RemoteRuntime.saveServer(server)
                tunnels.forEach(RemoteRuntime::saveTunnel)
                tunnels.forEach { RemoteRuntime.enableTunnel(it.id, true) }
            }
            await { RemoteRuntime.status.value[mcp.id]?.phase == TunnelPhase.WAITING }
            await { RemoteRuntime.status.value[base.id]?.phase == TunnelPhase.CONNECTED }
            assertTrue(request(tcpPort).contains("frpc-device-ok"))
            main { context.startForegroundService(Intent(context, MBrainService::class.java)) }
            await { GatewayRuntime.status.value.running && RemoteRuntime.status.value[mcp.id]?.phase == TunnelPhase.CONNECTED }
            assertTrue(request(mcpPort, "/mcp", "invalid-test-token").contains("401"))
            val initialized = request(mcpPort, "/mcp", GatewayRuntime.status.value.token)
            assertTrue(initialized.contains("200") && initialized.contains("protocolVersion"))
            main { context.stopService(Intent(context, MBrainService::class.java)) }
            await { RemoteRuntime.status.value[mcp.id]?.phase == TunnelPhase.WAITING }
            assertTrue(request(tcpPort).contains("frpc-device-ok"))
            assertStreaming(tcpPort)
            main {
                RemoteRuntime.enableTunnel(mcp.id, false)
                RemoteRuntime.saveTunnel(mcp.copy(target = TunnelTarget.CUSTOM, localPort = backend.localPort))
                RemoteRuntime.enableTunnel(mcp.id, true)
            }
            await { RemoteRuntime.status.value[mcp.id]?.phase == TunnelPhase.CONNECTED }
            main { RemoteRuntime.enableTunnel(base.id, false) }
            assertTrue(request(mcpPort).contains("frpc-device-ok"))
            main { RemoteRuntime.stopAll() }
            await { RemoteRuntime.status.value.values.all { it.phase == TunnelPhase.OFF } }
            await { java.io.File(context.noBackupFilesDir, "frpc-runtime").listFiles().orEmpty().isEmpty() }
        } finally {
            main {
                RemoteRuntime.stopAll(); context.stopService(Intent(context, MBrainService::class.java))
                tunnels.forEach { RemoteRuntime.removeTunnel(it.id) }
                RemoteRuntime.removeServer(server.id)
            }
            backend.close()
        }
    }

    private fun serve(server: ServerSocket) = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { runCatching { socket.use {
                it.soTimeout = 3000
                val request = it.inputStream.bufferedReader().readLine()
                if (request != null) {
                    if ("/events" in request) {
                        it.outputStream.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: first\n\n".toByteArray())
                        it.outputStream.flush()
                        Thread.sleep(3500)
                        it.outputStream.write("data: last\n\n".toByteArray())
                    } else it.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 14\r\nConnection: close\r\n\r\nfrpc-device-ok".toByteArray())
                }
            } } }
        }
    }

    private fun assertStreaming(port: Int) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2500
            socket.outputStream.write("GET /events HTTP/1.1\r\nHost: phone.example.test\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            val reader = socket.inputStream.bufferedReader()
            var line = reader.readLine()
            while (line != null && line != "data: first") line = reader.readLine()
            // The backend delays its final event by 3.5 seconds. A buffered response times out.
            assertEquals("data: first", line)
        }
    }

    private fun request(port: Int, path: String = "/", token: String? = null): String {
        return Socket("127.0.0.1", port).apply { soTimeout = 10000 }.use {
            val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"frpc-test\",\"version\":\"1\"}}}"
            val headers = if (token == null) "GET $path HTTP/1.1\r\n" else
                "POST $path HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: ${body.toByteArray().size}\r\n"
            it.outputStream.write((headers + "Host: localhost\r\nConnection: close\r\n\r\n" + if (token == null) "" else body).toByteArray())
            it.inputStream.bufferedReader().readText()
        }
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000
        while (!check() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Condition timed out", check())
    }
}
