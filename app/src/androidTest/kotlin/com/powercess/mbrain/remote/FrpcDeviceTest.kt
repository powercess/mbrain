package com.powercess.mbrain.remote

import android.content.Intent
import android.util.Base64
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
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.*
import kotlin.concurrent.thread

/** Run with scripts/smoke-frpc.py: a real frps is reached through adb reverse. */
@RunWith(AndroidJUnit4::class)
class FrpcDeviceTest {
    @Test fun realFrpcSupportsAllModesAndIndependentLifecycles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/smoke-frpc.py to provide the local frps fixture", args.containsKey("certificate"))
        val context = instrumentation.targetContext
        val cert = TunnelCertificate(name = "integration CA", pem = String(Base64.decode(args.getString("certificate"), Base64.DEFAULT)),
            keyPem = String(Base64.decode(args.getString("privateKey"), Base64.DEFAULT)))
        cert.validate()
        val serviceCert = cert.copy(id = newId(), name = "integration service")
        val tls = tlsContext(cert)
        val backend = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val secureBackend = tls.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serve(backend); serve(secureBackend)
        val controlPort = args.getString("controlPort")!!.toInt()
        val tcpPort = args.getString("tcpPort")!!.toInt()
        val rawPort = args.getString("rawPort")!!.toInt()
        val httpsPort = args.getString("httpsPort")!!.toInt()
        val base = TunnelConfig(name = "integration tcp", server = "127.0.0.1", serverPort = controlPort,
            token = args.getString("frpToken")!!, target = TunnelTarget.CUSTOM, localPort = backend.localPort,
            remotePort = tcpPort, tlsCaId = cert.id, tlsClientCertificateId = cert.id, tlsServerName = "phone.example.test")
        val raw = base.copy(id = newId(), name = "integration tls2raw", remotePort = rawPort, plugin = TunnelPlugin.TLS2RAW, certificateId = serviceCert.id)
        val https = base.copy(id = newId(), name = "integration MCP", target = TunnelTarget.MCP, type = ProxyType.HTTPS,
            domains = listOf("phone.example.test"), plugin = TunnelPlugin.HTTPS2HTTP, certificateId = serviceCert.id)
        val passthrough = base.copy(id = newId(), name = "integration passthrough", type = ProxyType.HTTPS,
            domains = listOf("passthrough.example.test"), localPort = secureBackend.localPort)
        val tunnels = listOf(base, raw, https, passthrough)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            main {
                GatewayRuntime.initialize(context)
                RemoteRuntime.initialize(context)
                RemoteRuntime.saveCertificate(cert); RemoteRuntime.saveCertificate(serviceCert)
                tunnels.forEach(RemoteRuntime::saveTunnel)
                tunnels.forEach { RemoteRuntime.enableTunnel(it.id, true) }
            }
            await { RemoteRuntime.status.value[https.id]?.phase == TunnelPhase.WAITING }
            await { listOf(base, raw, passthrough).all { RemoteRuntime.status.value[it.id]?.phase == TunnelPhase.CONNECTED } }
            assertTrue(request(tcpPort).contains("frpc-device-ok"))
            assertTrue(request(rawPort, tls, "phone.example.test").contains("frpc-device-ok"))
            assertTrue(request(httpsPort, tls, "passthrough.example.test").contains("frpc-device-ok"))
            main { context.startForegroundService(Intent(context, MBrainService::class.java)) }
            await { GatewayRuntime.status.value.running && RemoteRuntime.status.value[https.id]?.phase == TunnelPhase.CONNECTED }
            val unauthenticated = request(httpsPort, tls, "phone.example.test", "/mcp", "invalid-test-token")
            assertTrue("MCP authentication survives HTTPS forwarding", unauthenticated.contains("401"))
            val initialized = request(httpsPort, tls, "phone.example.test", "/mcp", GatewayRuntime.status.value.token)
            assertTrue("MCP initialize works through https2http", initialized.contains("200") && initialized.contains("protocolVersion"))
            main { context.stopService(Intent(context, MBrainService::class.java)) }
            await { RemoteRuntime.status.value[https.id]?.phase == TunnelPhase.WAITING }
            assertTrue(request(tcpPort).contains("frpc-device-ok"))
            assertTrue(request(rawPort, tls, "phone.example.test").contains("frpc-device-ok"))
            main {
                RemoteRuntime.enableTunnel(https.id, false)
                RemoteRuntime.saveTunnel(https.copy(target = TunnelTarget.CUSTOM, localPort = backend.localPort))
                RemoteRuntime.enableTunnel(https.id, true)
            }
            await { RemoteRuntime.status.value[https.id]?.phase == TunnelPhase.CONNECTED }
            assertStreaming(tls, httpsPort)
            main { RemoteRuntime.enableTunnel(base.id, false) }
            assertTrue(request(rawPort, tls, "phone.example.test").contains("frpc-device-ok"))
            main { RemoteRuntime.stopAll() }
            await { RemoteRuntime.status.value.values.all { it.phase == TunnelPhase.OFF } }
            await { java.io.File(context.noBackupFilesDir, "frpc-runtime").listFiles().orEmpty().isEmpty() }
        } finally {
            main {
                RemoteRuntime.stopAll(); context.stopService(Intent(context, MBrainService::class.java))
                tunnels.forEach { RemoteRuntime.removeTunnel(it.id) }
                RemoteRuntime.removeCertificate(serviceCert.id); RemoteRuntime.removeCertificate(cert.id)
            }
            backend.close(); secureBackend.close()
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

    private fun assertStreaming(tls: SSLContext, port: Int) {
        val tcp = Socket("127.0.0.1", port)
        (tls.socketFactory.createSocket(tcp, "phone.example.test", port, true) as SSLSocket).use { socket ->
            socket.soTimeout = 2500
            socket.sslParameters = socket.sslParameters.apply {
                serverNames = listOf(SNIHostName("phone.example.test")); endpointIdentificationAlgorithm = "HTTPS"
            }
            socket.startHandshake()
            socket.outputStream.write("GET /events HTTP/1.1\r\nHost: phone.example.test\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            val reader = socket.inputStream.bufferedReader()
            var line = reader.readLine()
            while (line != null && line != "data: first") line = reader.readLine()
            // The backend delays its final event by 3.5 seconds. A buffered response times out.
            assertEquals("data: first", line)
        }
    }

    private fun request(port: Int, tls: SSLContext? = null, hostname: String = "localhost", path: String = "/", token: String? = null): String {
        val tcp = Socket("127.0.0.1", port).apply { soTimeout = 10000 }
        val socket = if (tls == null) tcp else (tls.socketFactory.createSocket(tcp, hostname, port, true) as SSLSocket).apply {
            sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(hostname)); endpointIdentificationAlgorithm = "HTTPS" }
            startHandshake()
        }
        return socket.use {
            val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"frpc-test\",\"version\":\"1\"}}}"
            val headers = if (token == null) "GET $path HTTP/1.1\r\n" else
                "POST $path HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: ${body.toByteArray().size}\r\n"
            it.outputStream.write((headers + "Host: $hostname\r\nConnection: close\r\n\r\n" + if (token == null) "" else body).toByteArray())
            it.inputStream.bufferedReader().readText()
        }
    }

    private fun tlsContext(cert: TunnelCertificate): SSLContext {
        val bytes = Base64.decode(cert.keyPem.lines().filterNot { it.startsWith("-----") }.joinToString(""), Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("test", key, CharArray(0), cert.chain().toTypedArray()); setCertificateEntry("ca", cert.chain().first())
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000
        while (!check() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Condition timed out", check())
    }
}
