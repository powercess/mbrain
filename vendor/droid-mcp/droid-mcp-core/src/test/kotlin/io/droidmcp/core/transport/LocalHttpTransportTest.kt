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
            DroidMcp.builder().enableHttpServer(port = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DroidMcp.builder().enableHttpServer(host = " ")
        }
    }
}
