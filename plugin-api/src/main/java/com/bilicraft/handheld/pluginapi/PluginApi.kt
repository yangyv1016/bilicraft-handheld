package com.bilicraft.handheld.pluginapi

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

const val BH_PLUGIN_API_VERSION = 1

interface BhPlugin {
    val descriptor: BhPluginDescriptor

    fun entrypoints(host: BhPluginHost): List<BhPluginEntrypoint> = listOf(
        BhPluginEntrypoint(
            id = "main",
            title = descriptor.name,
            description = descriptor.description
        )
    )

    fun createPanel(host: BhPluginHost): BhPluginPanel

    fun createPanel(host: BhPluginHost, entrypointId: String): BhPluginPanel = createPanel(host)

    fun onLoad(host: BhPluginHost) = Unit

    fun onUnload(host: BhPluginHost) = Unit
}

data class BhPluginEntrypoint(
    val id: String,
    val title: String,
    val description: String = "",
    val order: Int = 0
)

data class BhPluginDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val minApiVersion: Int = BH_PLUGIN_API_VERSION
)

interface BhPluginPanel {
    @Composable
    fun Content(host: BhPluginHost, onClose: () -> Unit)
}

interface BhPluginHost {
    val appContext: Context
    val pluginDataDir: File
    val connectionState: StateFlow<BhConnectionState>
    val chatEvents: Flow<BhChatEvent>

    fun sendChat(text: String): Boolean
    fun log(message: String)
    suspend fun httpGet(url: String): String
}

data class BhChatEvent(
    val plainText: String,
    val rawJson: String,
    val sender: String?,
    val timestamp: Long
)

enum class BhConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Failed
}