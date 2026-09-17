package com.powercess.mbrain.shell

import io.droidmcp.core.*
import io.droidmcp.shell.ShellBackend
import kotlinx.serialization.json.*
import java.util.Base64

class NamedTool(private val delegate: McpTool, prefix: String) : McpTool by delegate {
    override val name = "${prefix}_${delegate.name}"
}

class ShellCommandTool(private val shell: ShellBackend, prefix: String) : McpTool {
    override val name = "${prefix}_exec"
    override val description = "Run a shell command as ${shell.name}. Supports shell syntax. 30-second timeout; stdout <=128KiB, stderr <=32KiB."
    override val annotations = ToolAnnotations(destructiveHint = true)
    override val parameters = listOf(ToolParameter("command", "Shell command", ParameterType.STRING, true))
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val command = params["command"] as? String ?: return ToolResult.error("command is required")
        require(command.isNotBlank() && command.length <= 32768) { "command 长度无效" }
        val result = shell.exec("/system/bin/sh", listOf("-c", command))
        return ToolResult(result.isSuccess, mapOf("stdout" to result.stdout, "stderr" to result.stderr,
            "exit_code" to result.exitCode), if (result.isSuccess) null else "exit=${result.exitCode}: ${result.output}")
    }
}

/** Privileged file operations use argv or single-quoted values, never unescaped paths. */
class FileOperationTool(private val shell: ShellBackend, prefix: String, private val operation: String) : McpTool {
    override val name = "${prefix}_file_$operation"
    override val description = when (operation) {
        "list" -> "List a directory through ${shell.name}."
        "read" -> "Read up to 48KiB of a file as base64 through ${shell.name}."
        "write" -> "Write UTF-8 text (<=48KiB) to a file. Overwrites existing content only if overwrite=true."
        "mkdir" -> "Create directories through ${shell.name}."
        "delete" -> "Delete one file or empty directory. Recursive deletion is not supported by this tool."
        else -> "Copy or move a file; destination must not exist."
    }
    override val annotations = ToolAnnotations(readOnlyHint = operation in listOf("list", "read"),
        destructiveHint = operation !in listOf("list", "read"))
    override val parameters = buildList {
        add(ToolParameter("path", "Absolute path", ParameterType.STRING, true))
        if (operation == "write") {
            add(ToolParameter("text", "UTF-8 content", ParameterType.STRING, true))
            add(ToolParameter("overwrite", "Allow replacing existing file; default false", ParameterType.BOOLEAN))
        }
        if (operation in listOf("copy", "move")) add(ToolParameter("destination", "Absolute destination", ParameterType.STRING, true))
    }
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        fun path(key: String): String {
            val value = params[key] as? String ?: error("$key is required")
            require(value.startsWith('/') && '\u0000' !in value && value.length <= 4096) { "$key 必须为绝对路径" }
            return value
        }
        val path = path("path")
        val quoted = shellQuote(path)
        val result = when (operation) {
            "list" -> shell.exec("ls", listOf("-la", "--", path))
            "read" -> {
                val bytes = shell.execBinary("head", listOf("-c", "49153", path))
                if (!bytes.isSuccess) return ToolResult.error(bytes.output)
                return ToolResult.success(mapOf("path" to path,
                    "base64" to Base64.getEncoder().encodeToString(bytes.stdoutBytes.take(49152).toByteArray()),
                    "truncated" to (bytes.stdoutBytes.size > 49152)))
            }
            "mkdir" -> shell.exec("mkdir", listOf("-p", "--", path))
            "delete" -> shell.exec("sh", listOf("-c", "if [ -d $quoted ] && [ ! -L $quoted ]; then rmdir -- $quoted; else rm -- $quoted; fi"))
            "write" -> {
                val content = params["text"] as? String ?: error("text is required")
                require(content.toByteArray().size <= 49152) { "内容超过 48KiB" }
                val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
                val noclobber = if (params["overwrite"] == true) "" else "set -C; "
                shell.exec("sh", listOf("-c", "${noclobber}printf '%s' ${shellQuote(encoded)} | base64 -d > $quoted"))
            }
            else -> {
                val destination = shellQuote(path("destination"))
                val command = if (operation == "copy") "cp" else "mv"
                shell.exec("sh", listOf("-c", "if [ -e $destination ] || [ -L $destination ]; then echo 'destination exists' >&2; exit 1; fi; $command -n -- $quoted $destination"))
            }
        }
        return if (result.isSuccess) ToolResult.success(mapOf("path" to path, "output" to result.stdout, "exit_code" to result.exitCode))
        else ToolResult.error("exit=${result.exitCode}: ${result.output}")
    }
    companion object {
        fun all(shell: ShellBackend, prefix: String) = listOf("list", "read", "write", "mkdir", "copy", "move", "delete")
            .map { FileOperationTool(shell, prefix, it) }
    }
}
