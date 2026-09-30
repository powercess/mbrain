package com.powercess.mbrain.capability

import io.droidmcp.core.McpTool
import io.droidmcp.core.ToolAnnotations
import io.droidmcp.core.ToolParameter
import io.droidmcp.core.ToolResult
import io.droidmcp.core.ParameterType

enum class ProviderKind { APP, SPECIAL_ACCESS, SHIZUKU, ROOT }

data class CapabilityDefinition(
    val id: String,
    val description: String,
    val risk: String = "read",
    val providers: List<ProviderKind>,
)

/** Stable MCP capability names over provider-specific tools. */
class CapabilityDispatcher(
    private val definitions: List<CapabilityDefinition>,
    private val tools: Map<ProviderKind, Map<String, McpTool>>,
) {
    fun tools(): List<McpTool> = definitions.mapNotNull { definition ->
        val available = definition.providers.firstOrNull { tools[it]?.containsKey(definition.id) == true } ?: return@mapNotNull null
        val delegate = tools.getValue(available).getValue(definition.id)
        object : McpTool {
            override val name = definition.id
            override val description = "${definition.description} Provider: ${available.name.lowercase()}."
            override val annotations: ToolAnnotations = delegate.annotations
            override val parameters: List<ToolParameter> = delegate.parameters +
                ToolParameter("provider", "auto or ${definition.providers.joinToString("/") { it.name.lowercase() }}", ParameterType.STRING)
            override suspend fun execute(params: Map<String, Any>): ToolResult {
                val requested = (params["provider"] as? String)?.lowercase()
                val selected = if (requested == null || requested == "auto") available else
                    runCatching { ProviderKind.valueOf(requested.uppercase()) }.getOrNull()
                val target = selected?.let { tools[it]?.get(definition.id) }
                    ?: return ToolResult.error("provider_unavailable", "Capability ${definition.id} has no available provider")
                return target.execute(params - "provider")
            }
        }
    }
}
