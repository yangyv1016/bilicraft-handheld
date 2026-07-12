package com.bilicraft.handheld.externalplugin

import com.bilicraft.handheld.pluginapi.BH_PLUGIN_API_VERSION
import kotlinx.serialization.Serializable

@Serializable
data class ExternalPluginManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String,
    val apiVersion: Int = BH_PLUGIN_API_VERSION,
    val entryClass: String,
    val permissions: List<String> = emptyList()
)

data class ExternalPluginEntry(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val loaded: Boolean,
    val enabled: Boolean = true,
    val packageName: String?,
    val statusMessage: String? = null
)

data class ExternalPluginEntrypoint(
    val pluginId: String,
    val entrypointId: String,
    val title: String,
    val description: String,
    val pluginName: String,
    val pluginVersion: String,
    val order: Int = 0
)