package com.powercess.mbrain.remote

import kotlinx.coroutines.*
import java.io.File
import java.io.Reader
import java.net.InetSocketAddress
import java.net.Socket

/** One supervised process per tunnel: credentials, failures and retries are independent. */
class FrpcController(
    private val executable: File,
    private val directory: File,
    private val changed: (String, TunnelStatus) -> Unit,
    private val launchProcess: (File, File) -> Process = { binary, config ->
        ProcessBuilder(binary.absolutePath, "-c", config.absolutePath).redirectErrorStream(true).start()
    },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Entry(val config: TunnelConfig, val endpoint: Pair<String, Int>) {
        var process: Process? = null
        lateinit var job: Job
        var status = TunnelStatus(TunnelPhase.CONNECTING)
    }
    private val entries = mutableMapOf<String, Entry>()
    private var closed = false

    @Synchronized fun reconcile(configs: List<TunnelConfig>, port: Int?) {
        if (closed) return
        val desired = configs.filter { it.enabled && it.endpoint(port) != null }.associateBy { it.id }
        entries.keys.toList().forEach { id ->
            val entry = entries.getValue(id)
            if (desired[id] != entry.config || desired[id]?.endpoint(port) != entry.endpoint) stop(id)
        }
        configs.forEach { config ->
            val endpoint = config.endpoint(port)
            if (!config.enabled || endpoint == null) changed(config.id, TunnelStatus(if (config.enabled) TunnelPhase.WAITING else TunnelPhase.OFF))
            else if (config.id !in entries) {
                val entry = Entry(config, endpoint)
                entry.job = scope.launch(start = CoroutineStart.LAZY) { supervise(entry) }
                entries[config.id] = entry
                changed(config.id, entry.status)
                entry.job.start()
            }
        }
    }

    @Synchronized fun retry(id: String) { stop(id) }

    private fun stop(id: String) {
        val entry = entries.remove(id) ?: return
        // Destroy first to unblock pipe reads; no process can be published after removal.
        entry.process?.destroy()
        entry.process?.let { process -> if (process.isAlive) process.destroyForcibly() }
        entry.job.cancel()
    }

    @Synchronized private fun publish(entry: Entry, phase: TunnelPhase, error: String? = null) {
        if (entries[entry.config.id] !== entry) return
        // A new connection attempt does not resolve the preceding failure. Keep its reason
        // until the state changes, rather than alternating error/retry rows on every attempt.
        if (phase == TunnelPhase.RETRYING && entry.status.phase == TunnelPhase.RETRYING && error == null) return
        if (entry.status.phase == phase && entry.status.error == error) return
        entry.status = entry.status.copy(phase = phase, error = error, events = (listOf(error ?: phase.label) + entry.status.events).take(40),
            serverConnected = when (phase) { TunnelPhase.CONNECTED -> true; TunnelPhase.ERROR -> entry.status.serverConnected; else -> false })
        changed(entry.config.id, entry.status)
    }

    @Synchronized private fun updateHealth(entry: Entry, health: LocalHealth? = null, connected: Boolean? = null) {
        if (entries[entry.config.id] !== entry) return
        entry.status = entry.status.copy(localHealth = health ?: entry.status.localHealth, serverConnected = connected ?: entry.status.serverConnected)
        changed(entry.config.id, entry.status)
    }

    private suspend fun supervise(entry: Entry) {
        var pause = 1000L
        while (currentCoroutineContext().isActive) {
            var process: Process? = null
            // A unique file per attempt prevents an old job deleting its replacement's config.
            var attemptDirectory: File? = null
            var healthJob: Job? = null
            try {
                check(executable.isFile && executable.canExecute()) { "此设备的 frpc 不可用" }
                directory.mkdirs()
                attemptDirectory = java.nio.file.Files.createTempDirectory(directory.toPath(), "frpc-").toFile()
                val configFile = privateFile(attemptDirectory, "frpc.json", entry.config.frpcConfig(entry.endpoint))
                currentCoroutineContext().ensureActive()
                synchronized(this) {
                    if (entries[entry.config.id] !== entry) throw CancellationException()
                    process = launchProcess(executable, configFile)
                    entry.process = process
                }
                publish(entry, TunnelPhase.CONNECTING)
                healthJob = scope.launch {
                    while (isActive) {
                        val healthy = runCatching {
                            Socket().use { it.connect(InetSocketAddress(entry.endpoint.first, entry.endpoint.second), 2000) }
                        }.isSuccess
                        updateHealth(entry, if (healthy) LocalHealth.REACHABLE else LocalHealth.UNREACHABLE)
                        delay(15_000)
                    }
                }
                process!!.inputStream.reader().use { reader ->
                    while (currentCoroutineContext().isActive) {
                        val line = reader.boundedLine() ?: break
                        if ("login to server success" in line) updateHealth(entry, connected = true)
                        classifyFrpcLine(line)?.let { (phase, error) ->
                            if (phase == TunnelPhase.CONNECTED) pause = 1000L
                            publish(entry, phase, error)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                publish(entry, TunnelPhase.RETRYING, "隧道进程已退出，正在重试")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                publish(entry, TunnelPhase.ERROR, if (!executable.canExecute()) "此设备的 frpc 不可用" else "隧道启动失败，正在重试")
            } finally {
                healthJob?.cancel()
                process?.destroy()
                process?.let { if (it.isAlive) it.destroyForcibly() }
                process?.let { runCatching { it.inputStream.close() }; runCatching { it.outputStream.close() }; runCatching { it.errorStream.close() } }
                attemptDirectory?.deleteRecursively()
                synchronized(this) { if (entry.process === process) entry.process = null }
            }
            delay(pause)
            pause = (pause * 2).coerceAtMost(30_000)
        }
    }

    @Synchronized override fun close() {
        closed = true
        entries.keys.toList().forEach(::stop)
        scope.cancel()
    }

    // Await asynchronous process/config cleanup before deleting the owner's directory.
    internal suspend fun awaitTermination() { scope.coroutineContext.job.join() }
}

private fun privateFile(directory: File, name: String, text: String): File = File(directory, name).apply {
    createNewFile()
    setReadable(false, false); setReadable(true, true)
    setWritable(false, false); setWritable(true, true)
    writeText(text)
}

internal fun Reader.boundedLine(): String? {
    val line = StringBuilder()
    while (true) {
        val char = read()
        if (char < 0) return line.toString().takeIf { it.isNotEmpty() }
        if (char == 10) return line.toString()
        if (line.length < 4096) line.append(char.toChar())
    }
}
