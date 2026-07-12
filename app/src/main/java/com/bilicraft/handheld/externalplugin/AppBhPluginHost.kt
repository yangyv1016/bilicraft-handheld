package com.bilicraft.handheld.externalplugin

import android.content.Context
import com.bilicraft.handheld.pluginapi.BhChatEvent
import com.bilicraft.handheld.pluginapi.BhConnectionState
import com.bilicraft.handheld.pluginapi.BhPluginHost
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.session.SessionController
import com.bilicraft.handheld.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppBhPluginHost(
    override val appContext: Context,
    private val session: SessionController,
    override val pluginDataDir: File,
    private val pluginId: String
) : BhPluginHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow(session.connState.value.toBhState())
    override val connectionState = _connectionState.asStateFlow()

    private val _chatEvents = MutableSharedFlow<BhChatEvent>(extraBufferCapacity = 256)
    override val chatEvents = _chatEvents.asSharedFlow()

    init {
        pluginDataDir.mkdirs()
        scope.launch {
            session.connState.collect { state -> _connectionState.value = state.toBhState() }
        }
        scope.launch {
            session.events.collect { event ->
                if (event is SessionEvent.Chat) {
                    _chatEvents.tryEmit(
                        BhChatEvent(
                            plainText = event.event.plainText,
                            rawJson = event.event.rawJson,
                            sender = event.event.sender,
                            timestamp = event.event.timestamp
                        )
                    )
                }
            }
        }
    }

    override fun sendChat(text: String): Boolean {
        if (text.isBlank() || session.connState.value !is ConnectionState.Connected) return false
        session.sendChat(text)
        return true
    }

    override fun log(message: String) {
        session.appendPluginLog(pluginId, message)
    }

    override suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("empty body")
        }
    }

    fun close() {
        scope.cancel()
    }
}

private fun ConnectionState.toBhState(): BhConnectionState = when (this) {
    is ConnectionState.Connected -> BhConnectionState.Connected
    ConnectionState.Connecting -> BhConnectionState.Connecting
    ConnectionState.LoggingIn -> BhConnectionState.Connecting
    is ConnectionState.Reconnecting -> BhConnectionState.Reconnecting
    is ConnectionState.Failed -> BhConnectionState.Failed
    ConnectionState.Disconnected -> BhConnectionState.Disconnected
}