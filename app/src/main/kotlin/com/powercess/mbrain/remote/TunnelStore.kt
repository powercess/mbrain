package com.powercess.mbrain.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TunnelStore(private val secrets: SecretStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): List<TunnelConfig> = secrets.read("tunnels")?.let { raw ->
        json.decodeFromString<List<TunnelConfig>>(raw).also { list ->
            require(list.map { it.id }.distinct().size == list.size)
            list.forEach { it.validate(list) }
        }
    } ?: emptyList()
    fun save(items: List<TunnelConfig>) = secrets.write("tunnels", json.encodeToString(items))
}
