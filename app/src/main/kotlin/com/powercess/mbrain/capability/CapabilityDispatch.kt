package com.powercess.mbrain.capability

import io.droidmcp.core.*

enum class ProviderKind { APP, SPECIAL_ACCESS, SHIZUKU, ROOT }
enum class ProviderStatus { AVAILABLE, UNAVAILABLE, REQUIRES_PERMISSION, DISCONNECTED, UNSUPPORTED }
data class ProviderState(val status: ProviderStatus, val reason: String? = null) { val available get() = status == ProviderStatus.AVAILABLE }
data class CapabilityDefinition(val id: String, val description: String, val risk: String = "read", val providers: List<ProviderKind>, val outputContract: String = "provider_envelope_v1")
interface CapabilityProvider { val kind: ProviderKind; fun state(capabilityId: String): ProviderState; fun tool(capabilityId: String): McpTool? }
class ToolMapProvider(override val kind: ProviderKind, private val tools: Map<String, McpTool>, private val stateResolver: (String) -> ProviderState = { ProviderState(ProviderStatus.AVAILABLE) }) : CapabilityProvider {
    override fun state(capabilityId: String) = if (tools.containsKey(capabilityId)) stateResolver(capabilityId) else ProviderState(ProviderStatus.UNSUPPORTED, "provider_has_no_implementation")
    override fun tool(capabilityId: String): McpTool? = tools[capabilityId]
}
class CapabilityRegistry(val definitions: List<CapabilityDefinition>, private val providers: Map<ProviderKind, CapabilityProvider>) {
    fun states(id: String) = definitions.firstOrNull { it.id == id }?.providers?.associateWith { providers[it]?.state(id) ?: ProviderState(ProviderStatus.UNSUPPORTED, "provider_not_registered") } ?: emptyMap()
    fun provider(kind: ProviderKind) = providers[kind]
}
class CapabilityDispatcher(private val registry: CapabilityRegistry) {
    fun tools(): List<McpTool> = registry.definitions.map { definition -> object : McpTool {
        override val name = definition.id
        override val description = definition.description
        private val delegate = definition.providers.asSequence().mapNotNull { registry.provider(it)?.tool(definition.id) }.firstOrNull()
        override val annotations = delegate?.annotations ?: ToolAnnotations()
        override val parameters = (delegate?.parameters ?: emptyList()) + ToolParameter("provider", "Provider override; default auto", ParameterType.STRING)
        override suspend fun execute(params: Map<String, Any>): ToolResult {
            val requested = (params["provider"] as? String)?.lowercase() ?: "auto"
            val explicit = if (requested == "auto") null else runCatching { ProviderKind.valueOf(requested.uppercase()) }.getOrNull() ?: return ToolResult.error("invalid_provider", requested)
            val candidates = explicit?.let { listOf(it) } ?: definition.providers
            val states = registry.states(definition.id)
            val selected = candidates.firstOrNull { states[it]?.available == true } ?: return ToolResult.error("provider_unavailable", states.entries.joinToString { "${it.key.name.lowercase()}:${it.value.reason ?: it.value.status.name.lowercase()}" })
            val target = registry.provider(selected)?.tool(definition.id) ?: return ToolResult.error("provider_unavailable", selected.name.lowercase())
            val result = target.execute(params - "provider")
            return if (result.isSuccess) ToolResult.success((result.data ?: emptyMap()) + mapOf("provider" to selected.name.lowercase(), "capability" to definition.id, "contract" to definition.outputContract)) else result
        }
    } }
    fun directory(): List<Map<String, Any?>> = registry.definitions.map { definition -> mapOf("id" to definition.id, "risk" to definition.risk, "contract" to definition.outputContract, "providers" to definition.providers.map { it.name.lowercase() }, "states" to registry.states(definition.id).mapKeys { it.key.name.lowercase() }.mapValues { mapOf("status" to it.value.status.name.lowercase(), "reason" to it.value.reason) }) }
}
