package com.powercess.mbrain.shell

import io.droidmcp.core.*
import io.droidmcp.shell.ShellBackend
import kotlinx.coroutines.CancellationException

/** Bounded diagnostic commands; caller values are argv, never shell fragments. */
object DiagnosticTools {
    fun all(shell: ShellBackend): List<McpTool> = listOf(
        command("logs_query", "Read a bounded logcat snapshot (not a stream). Filters combine; time is MM-DD HH:MM:SS.mmm. Buffer/UID support varies by Android version.", listOf(
            integer("lines", "Maximum lines 1..300; default 100"), integer("pid", "Process ID"), integer("uid", "Android UID"),
            string("tag", "Exact log tag; default all"), string("level", "V/D/I/W/E/F; default I"),
            string("since", "MM-DD HH:MM:SS.mmm; optional"), string("buffer", "main/system/crash/events/all; default main"),
        )) { p ->
            val args = mutableListOf("-d", "-v", "threadtime",
                "-b", p.choice("buffer", "main", setOf("main", "system", "crash", "events", "all")))
            if (p.containsKey("pid")) args += "--pid=${p.int("pid", 0, 1, Int.MAX_VALUE)}"
            if (p.containsKey("uid")) args += "--uid=${p.int("uid", 0, 0, Int.MAX_VALUE)}"
            if (p.containsKey("since")) {
                val since = p.text("since")
                require(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}").matches(since)) { "Invalid since format" }
                args += listOf("-T", since, "-m", p.int("lines", 100, 1, 300).toString())
            } else args += listOf("-t", p.int("lines", 100, 1, 300).toString())
            val tag = if (p.containsKey("tag")) p.text("tag").also { require(Regex("[A-Za-z0-9_.-]{1,120}").matches(it)) { "Invalid tag" } } else "*"
            args += "$tag:${p.choice("level", "I", setOf("V", "D", "I", "W", "E", "F"))}"
            if (tag != "*") args += "*:S"
            run(shell, "logcat", args, p.int("lines", 100, 1, 300))
        },
        command("appops_get", "Read AppOps modes for an application and optional operation.", packageParams + string("op", "Optional operation name")) { p ->
            run(shell, "cmd", listOf("appops", "get", "--user", p.user(), p.pkg()) + p.optionalOp())
        },
        command("appops_set", "Set one AppOp mode, then return the observed mode. default resets this operation to platform policy.", packageParams + listOf(
            string("op", "Operation, e.g. CAMERA or android:camera", true), string("mode", "allow/ignore/deny/default/foreground", true)), false) { p ->
            val op = p.op()
            val result = run(shell, "cmd", listOf("appops", "set", "--user", p.user(), p.pkg(), op,
                p.choice("mode", null, setOf("allow", "ignore", "deny", "default", "foreground"))))
            if (!result.isSuccess) result else {
                val observed = run(shell, "cmd", listOf("appops", "get", "--user", p.user(), p.pkg(), op))
                if (observed.isSuccess) ToolResult.success(mapOf("applied" to true, "observed" to observed.data))
                else ToolResult.error("appops_verification_failed", "Set completed; readback failed: ${observed.errorMessage}")
            }
        },
        command("proc_list", "List processes with PID, UID, RSS and command. RSS is KiB. Optional package matches main and colon-suffixed processes.",
            listOf(string("package", "Optional exact package"), integer("limit", "1..300, default 100"))) { p ->
            val response = shell.exec("ps", listOf("-A", "-o", "PID,UID,RSS,NAME"))
            if (!response.isSuccess) failure(response.exitCode, response.stderr) else {
                val packageName = if (p.containsKey("package")) p.pkg() else null
                val lines = response.stdout.lineSequence().drop(1).filter { it.isNotBlank() }.toList()
                val processes = lines.mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 4)
                    if (parts.size != 4 || parts[0].toLongOrNull() == null) null else mapOf(
                        "pid" to parts[0].toLong(), "uid" to parts[1], "rss_kib" to parts[2].toLongOrNull(), "name" to parts[3])
                }.filter { row -> packageName == null || row["name"] == packageName || row["name"].toString().startsWith("$packageName:") }
                val limit = p.int("limit", 100, 1, 300)
                ToolResult.success(mapOf("processes" to processes.take(limit), "total" to processes.size, "truncated" to (processes.size > limit)))
            }
        },
        command("app_resource_usage", "Read application memory and system CPU snapshot. CPU uses dumpsys' reported sampling interval, not an instantaneous percent.",
            listOf(string("package", "Application package", true))) { p ->
            val pkg = p.pkg()
            val memory = run(shell, "dumpsys", listOf("meminfo", pkg))
            if (!memory.isSuccess) memory else {
                val cpu = shell.exec("dumpsys", listOf("cpuinfo"))
                if (!cpu.isSuccess) failure(cpu.exitCode, cpu.stderr) else {
                    val cpuLines = cpu.stdout.lineSequence().filter { it.contains(pkg) || it.startsWith("CPU usage") || it.contains("TOTAL:") }.toList()
                    ToolResult.success(mapOf("package" to pkg, "memory" to memory.data, "cpu" to cpuLines))
                }
            }
        },
        command("net_status", "Read IP addresses, IPv4/IPv6 routes, Wi-Fi status and global HTTP proxy. Each section reports its own exit code.", emptyList()) {
            val sections = linkedMapOf<String, Any>()
            listOf(Triple("addresses", "ip", listOf("addr", "show")),
                Triple("routes_ipv4", "ip", listOf("route", "show")), Triple("routes_ipv6", "ip", listOf("-6", "route", "show")),
                Triple("wifi", "cmd", listOf("wifi", "status")), Triple("http_proxy", "settings", listOf("get", "global", "http_proxy")))
                .forEach { (name, executable, args) ->
                    val result = shell.exec(executable, args)
                    sections[name] = mapOf("exit_code" to result.exitCode, "output" to result.stdout.take(12000), "stderr" to result.stderr.take(2000))
                }
            ToolResult.success(sections)
        },
    )

    private val packageParams = listOf(string("package", "Application package", true), integer("user", "Android user ID; defaults to current user"))
    private fun string(name: String, description: String, required: Boolean = false) = ToolParameter(name, description, ParameterType.STRING, required)
    private fun integer(name: String, description: String) = ToolParameter(name, description, ParameterType.INTEGER)
    private fun command(name: String, description: String, parameters: List<ToolParameter>, readOnly: Boolean = true,
        openWorld: Boolean = false, execute: suspend (Map<String, Any>) -> ToolResult): McpTool = object : McpTool {
        override val name = name
        override val description = description
        override val parameters = parameters
        override val annotations = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = !readOnly, openWorldHint = openWorld)
        override suspend fun execute(params: Map<String, Any>): ToolResult = try {
            require((params.keys - parameters.map { it.name }.toSet()).isEmpty()) { "Unknown argument" }
            execute.invoke(params)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { ToolResult.error("diagnostic_failed", error.message) }
    }
    private suspend fun run(shell: ShellBackend, executable: String, args: List<String>, maxLines: Int = 400): ToolResult {
        val result = shell.exec(executable, args)
        if (!result.isSuccess) return failure(result.exitCode, result.stderr.ifBlank { result.stdout })
        // Some Android cmd handlers report errors on stderr while exiting zero.
        if (result.stderr.lineSequence().any { it.startsWith("Error:") || it.startsWith("Exception") }) return ToolResult.error(result.stderr.take(2000))
        val lines = result.stdout.lines()
        val text = lines.takeLast(maxLines).joinToString("\n")
        return ToolResult.success(mapOf("output" to text.take(48000), "truncated" to (lines.size > maxLines || text.length > 48000),
            "exit_code" to result.exitCode, "stderr" to result.stderr.take(2000)))
    }
    private fun failure(code: Int, error: String) = ToolResult.error("command_failed", "exit=$code: ${error.take(2000)}")
    private fun Map<String, Any>.text(key: String): String = (this[key] as? String)?.also {
        require(it.isNotBlank() && '\u0000' !in it) { "$key must not be blank or contain NUL" }
    } ?: error("$key is required")
    private fun Map<String, Any>.pkg() = text("package").also { require(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(it)) { "Invalid package" } }
    private fun Map<String, Any>.op() = text("op").also { require(Regex("[A-Za-z][A-Za-z0-9_:]{0,100}").matches(it)) { "Invalid operation" } }
    private fun Map<String, Any>.optionalOp() = if (containsKey("op")) listOf(op()) else emptyList()
    private fun Map<String, Any>.user() = if (containsKey("user")) int("user", 0, 0, 100000).toString() else "current"
    private fun Map<String, Any>.choice(key: String, default: String?, choices: Set<String>): String =
        (if (containsKey(key)) text(key) else default ?: error("$key is required")).also { require(it in choices) { "Invalid $key" } }
    private fun Map<String, Any>.int(key: String, default: Int, min: Int, max: Int): Int {
        if (!containsKey(key)) return default
        val value = this[key] as? Number ?: error("$key must be an integer")
        val number = value.toDouble()
        require(number.isFinite() && number == value.toLong().toDouble() && number >= min && number <= max) { "$key must be $min..$max" }
        return value.toInt()
    }
}
