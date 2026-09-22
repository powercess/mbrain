package io.droidmcp.core.transport

import io.droidmcp.core.DroidMcp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** MBrain regression: exercise real sockets and authentication, not a mocked protocol. */
class LocalHttpTransportTest {
    @Test
    fun `local gateway requires token and releases port on stop`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = DroidMcp.builder().enableHttpServer(port = port, readOnly = true).build()
        fun health(token: String? = null): Int {
            val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            return try { connection.responseCode } finally { connection.disconnect() }
        }
        try {
            server.startServer()
            assertTrue(server.isServerRunning())
            assertEquals(401, health())
            assertEquals(401, health("wrong-token"))
            assertEquals(200, health(server.serverToken))
        } finally {
            server.stopServer()
        }
        assertFalse(server.isServerRunning())
        // A stopped service must actually relinquish its listening socket.
        ServerSocket(port).use { assertEquals(port, it.localPort) }
    }

    @Test
    fun `invalid listen configuration is rejected before starting`() {
        assertThrows(IllegalArgumentException::class.java) {
            DroidMcp.builder().enableHttpServer(port = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DroidMcp.builder().enableHttpServer(host = " ")
        }
    }

    @Test
    fun `occupied preferred port falls back and reports actual authenticated listener`() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
            val server = DroidMcp.builder().enableHttpServer(port = occupied.localPort, fallbackToDynamicPort = true).build()
            try {
                server.startServer()
                val actual = server.serverPort!!
                assertNotEquals(occupied.localPort, actual)
                val connection = URL("http://127.0.0.1:$actual/health").openConnection() as HttpURLConnection
                connection.setRequestProperty("Authorization", "Bearer ${server.serverToken}")
                try { assertEquals(200, connection.responseCode) } finally { connection.disconnect() }
                val oldToken = server.serverToken
                val replacement = "replacement-" + "a".repeat(32)
                server.setServerToken(replacement)
                fun health(token: String?): Int {
                    val request = URL("http://127.0.0.1:$actual/health").openConnection() as HttpURLConnection
                    request.setRequestProperty("Authorization", "Bearer $token")
                    return try { request.responseCode } finally { request.disconnect() }
                }
                assertEquals(401, health(oldToken))
                assertEquals(200, health(replacement))
            } finally { server.stopServer() }
            assertNull(server.serverPort)
        }
    }

    @Test
    fun `explicit fixed port never silently falls back`() {
        ServerSocket(0).use { occupied ->
            val server = DroidMcp.builder().enableHttpServer(port = occupied.localPort).build()
            try {
                assertThrows(Exception::class.java) { server.startServer() }
                assertFalse(server.isServerRunning())
                assertNull(server.serverPort)
            } finally { server.stopServer() }
        }
    }
}
