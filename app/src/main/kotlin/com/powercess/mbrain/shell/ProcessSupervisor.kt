package com.powercess.mbrain.shell

import com.powercess.mbrain.data.ExecutionMode
import io.droidmcp.root.RootTools
import io.droidmcp.shizuku.ShizukuTools
import io.droidmcp.shell.ShellBackend
import io.droidmcp.shell.ShellResult
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

/** Owns every command and stdio child so disabling a source or stopping closes them. */
class ProcessSupervisor {
    private val children = ConcurrentHashMap<Process, ExecutionMode>()
    @Volatile private var closed = false
    @Synchronized
    fun spawn(mode: ExecutionMode, argv: List<String>): Process {
        check(!closed) { "网关已停止" }
        require(argv.isNotEmpty() && argv.none { '\u0000' in it }) { "无效命令" }
        val process = when (mode) {
            ExecutionMode.APP -> ProcessBuilder(argv).start()
            ExecutionMode.ROOT -> {
                check(RootTools.isRootAvailable()) { "请先授予 MBrain Root 权限" }
                val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "su")
                    .firstOrNull { it == "su" || java.io.File(it).canExecute() }!!
                ProcessBuilder(su, "-c", "exec " + argv.joinToString(" ", transform = ::shellQuote)).start()
            }
            ExecutionMode.SHIZUKU -> {
                check(ShizukuTools.isShizukuReady()) { "Shizuku 未启动或尚未授权 MBrain" }
                // Same v13 API bridge as the pinned droid-mcp Shizuku backend.
                val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java,
                    Array<String>::class.java, String::class.java).apply { isAccessible = true }
                method.invoke(null, argv.toTypedArray(), null, null) as Process
            }
        }
        children[process] = mode
        return process
    }

    fun release(process: Process) {
        children.remove(process)
        runCatching { process.destroy() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
    @Synchronized fun stopMode(mode: ExecutionMode) { children.filterValues { it == mode }.keys.forEach(::release) }
    @Synchronized fun close() { closed = true; children.keys.toList().forEach(::release) }

    suspend fun execute(mode: ExecutionMode, argv: List<String>, binary: Boolean = false): ShellResult = withContext(Dispatchers.IO) {
        // Android timeout also bounds descendants that inherit the command's pipe handles.
        val process = spawn(mode, listOf("/system/bin/timeout", "-s", "KILL", "30") + argv)
        try {
            withTimeout(32_000) {
                coroutineScope {
                    val out = async(Dispatchers.IO) { drain(process.inputStream, if (binary) 4 * 1024 * 1024 else 128 * 1024) }
                    val err = async(Dispatchers.IO) { drain(process.errorStream, 32 * 1024) }
                    try {
                        val exit = runInterruptible { process.waitFor() }
                        ShellResult(exit, out.await(), err.await().toString(Charsets.UTF_8))
                    } finally { release(process) }
                }
            }
        } finally { release(process) }
    }

    private fun drain(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var truncated = false
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                val keep = minOf(count, limit - output.size())
                if (keep > 0) output.write(buffer, 0, keep)
                if (keep < count) truncated = true
            }
        }
        if (truncated) throw IllegalStateException("输出超过 $limit 字节，请缩小查询范围")
        return output.toByteArray()
    }
}

class ManagedShellBackend(
    private val mode: ExecutionMode,
    private val processes: ProcessSupervisor,
    private val enabled: () -> Boolean,
) : ShellBackend {
    override val name = mode.name
    override fun isAvailable() = enabled() && when (mode) {
        ExecutionMode.ROOT -> RootTools.isRootAvailable()
        ExecutionMode.SHIZUKU -> ShizukuTools.isShizukuReady()
        ExecutionMode.APP -> true
    }
    override suspend fun exec(command: String, args: List<String>): ShellResult {
        check(isAvailable()) { "$name 未启用或未授权" }
        return processes.execute(mode, listOf(command) + args)
    }
    override suspend fun execBinary(command: String, args: List<String>): ShellResult {
        check(isAvailable()) { "$name 未启用或未授权" }
        return processes.execute(mode, listOf(command) + args, binary = true)
    }
}
