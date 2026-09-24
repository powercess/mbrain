package com.powercess.mbrain.control

import com.powercess.mbrain.shell.DiagnosticTools
import io.droidmcp.shell.ShellBackend
import io.droidmcp.shell.ShellResult
import kotlinx.coroutines.runBlocking

/** Device-only smoke entry point. Run via app_process with app + test APKs on CLASSPATH. */
object CapabilitySmokeMain {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        try {
            require(args.size == 2) { "Usage: APK_PATH TEST_PACKAGE" }
            val shell = object : ShellBackend {
                override val name = "device-shell"
                override fun isAvailable() = true
                override suspend fun exec(command: String, args: List<String>): ShellResult {
                    val process = ProcessBuilder(listOf(command) + args).start()
                    var stderr = ""
                    val reader = Thread { stderr = process.errorStream.bufferedReader().use { it.readText() } }.apply { start() }
                    val stdout = process.inputStream.use { it.readBytes() }
                    val exit = process.waitFor()
                    reader.join()
                    return ShellResult(exit, stdout, stderr)
                }
            }
            val diagnostic = DiagnosticTools.all(shell).associateBy { it.name }
            suspend fun checkCall(name: String, params: Map<String, Any> = emptyMap()) {
                val result = diagnostic.getValue(name).execute(params)
                check(result.isSuccess) { "$name: ${result.errorMessage}" }
                println("PASS $name")
            }
            checkCall("logs_query", mapOf("lines" to 5, "tag" to "MBrainSmoke"))
            checkCall("appops_get", mapOf("package" to args[1], "op" to "VIBRATE"))
            // Dedicated test package only; preserve the original mode on-device.
            val before = shell.exec("cmd", listOf("appops", "get", args[1], "VIBRATE")).stdout
            val mode = Regex("VIBRATE: (allow|ignore|deny|default|foreground)").find(before)?.groupValues?.get(1) ?: "default"
            try { checkCall("appops_set", mapOf("package" to args[1], "op" to "VIBRATE", "mode" to "ignore")) }
            finally { shell.exec("cmd", listOf("appops", "set", args[1], "VIBRATE", mode)) }
            checkCall("proc_list", mapOf("package" to args[1]))
            checkCall("app_resource_usage", mapOf("package" to args[1]))
            checkCall("net_status")
            val network = PhoneTools.all(shell, args[0]).first { it.name == "net_diagnose" }
            val result = network.execute(mapOf("host" to "localhost", "port" to 1))
            check(result.isSuccess)
            check((result.data!!["dns"] as Map<*, *>)["ok"] == true)
            check((result.data!!["tcp"] as Map<*, *>)["ok"] == false)
            println("PASS net_diagnose DNS success and TCP failure")
            ClipboardDeviceSmoke.run()
            println("CAPABILITY_SMOKE_OK")
            System.exit(0)
        } catch (failure: Throwable) {
            // Do not print tool payloads: logs and clipboard can contain private data.
            System.err.println("CAPABILITY_SMOKE_FAILED: ${failure.javaClass.simpleName}: ${failure.message}")
            System.exit(1)
        }
    }
}
