package com.powercess.mbrain.mcp

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import kotlin.concurrent.thread

class StdioRpcConnectionTest {
    @Test fun `stdio routes replies separately from notifications and destroys child on close`() {
        val process = PipeProcess()
        thread(isDaemon = true) {
            val request = Json.parseToJsonElement(process.serverInput.bufferedReader().readLine()).jsonObject
            process.serverOutput.write(("""{"jsonrpc":"2.0","method":"notifications/progress"}""" + "\n" +
                """{"jsonrpc":"2.0","id":${request["id"]},"result":{"ok":true}}""" + "\n").toByteArray())
            process.serverOutput.flush()
        }
        StdioRpcConnection(process) { it.destroy() }.use { connection ->
            val reply = connection.exchange(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").jsonObject)
            assertTrue(reply!!.getValue("result").jsonObject.getValue("ok").jsonPrimitive.boolean)
        }
        assertTrue(process.destroyed)
    }
    private class PipeProcess : Process() {
        private val stdin = PipedOutputStream()
        val serverInput = PipedInputStream(stdin)
        val serverOutput = PipedOutputStream()
        private val stdout = PipedInputStream(serverOutput)
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { destroyed = true; runCatching { stdin.close(); stdout.close(); serverInput.close(); serverOutput.close() } }
    }
}
