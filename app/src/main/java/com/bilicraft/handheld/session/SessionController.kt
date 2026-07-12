package com.bilicraft.handheld.session

import com.bilicraft.handheld.auth.AuthManager
import com.bilicraft.handheld.plugin.PluginHost
import com.bilicraft.handheld.plugin.PluginManager
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.ChatSigningMode
import com.bilicraft.handheld.protocol.CommandSuggestionState
import com.bilicraft.handheld.protocol.CommandSuggestions
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.protocol.MinecraftClient
import com.bilicraft.handheld.protocol.PaletteRegistry
import com.bilicraft.handheld.protocol.ServerAddress
import com.bilicraft.handheld.protocol.ServerPinger
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

/** 当前单会话向 UI 暴露的带归属事件。 */
sealed interface SessionEvent {
    val serverId: String?

    data class State(
        override val serverId: String?,
        val state: ConnectionState
    ) : SessionEvent

    data class Chat(
        override val serverId: String?,
        val event: ChatEvent
    ) : SessionEvent
}

/**
 * 会话运行核心：编排「登录态 → 版本解析 → 连接 → 插件分发 → 断线重连」。
 *
 * 这是纯 Kotlin 业务核心，不碰 Android 组件，因此可单独测试。
 * Service 只是它的宿主容器。
 *
 * 断线重连策略：指数退避（2s,4s,8s… 上限 30s），最多 N 次；
 * 每次重连若为「自动识别」会重新 ping，适配服务器版本变化。
 */
class SessionController(
    private val authManager: AuthManager,
    private val versionRepo: VersionRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    // 聚合的聊天记录（UI 展示；含系统提示）
    private val _log = MutableStateFlow<List<ChatEvent>>(emptyList())
    val log: StateFlow<List<ChatEvent>> = _log.asStateFlow()

    private val _connState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connState: StateFlow<ConnectionState> = _connState.asStateFlow()

    private val _commandSuggestions = MutableStateFlow(CommandSuggestions.Empty)
    val commandSuggestions: StateFlow<CommandSuggestionState> = _commandSuggestions.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    // 插件宿主：把插件的 sendChat 接到当前 client
    private val pluginHost = object : PluginHost {
        override fun sendChat(text: String) { client?.sendChat(text) }
        override fun log(message: String) = appendSystem("[插件] $message")
    }
    val pluginManager = PluginManager(pluginHost)

    private var client: MinecraftClient? = null
    private var activeRequest: ConnectionRequest? = null
    private var requestSeq = 0L
    private var reconnectAllowed = true
    private var pumpJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        if (PLUGINS_ENABLED) pluginManager.installBuiltins()
        // 插件错误也进日志，用户可见但不崩溃
        scope.launch {
            pluginManager.errors.collect { e ->
                appendSystem("[插件错误] ${e.pluginName}.${e.phase}: ${e.message}")
            }
        }
    }

    /** 启动连接。version 为「自动识别」时先 ping 拿协议号。 */
    fun start(serverId: String?, address: ServerAddress, version: McVersion, mode: ChatSigningMode) {
        reconnectAllowed = false
        reconnectJob?.cancel()
        pumpJob?.cancel()
        val previousClient = client
        client = null
        _commandSuggestions.value = CommandSuggestions.Empty
        val request = ConnectionRequest(++requestSeq, serverId, address, version, mode)
        activeRequest = request
        publishState(request, ConnectionState.Connecting)
        previousClient?.disconnect()
        reconnectAllowed = true
        scope.launch { connectOnce(request, attempt = 0) }
    }

    fun stop() {
        reconnectAllowed = false
        reconnectJob?.cancel()
        pumpJob?.cancel()
        val stoppedServerId = activeRequest?.serverId
        activeRequest = null
        requestSeq++
        val previousClient = client
        client = null
        _commandSuggestions.value = CommandSuggestions.Empty
        previousClient?.disconnect()
        publishState(stoppedServerId, ConnectionState.Disconnected)
    }

    fun sendChat(text: String) {
        // 不做本地回显：服务器会把自己的消息（含命令回执）广播回来，本地再显示会重复。
        client?.sendChat(text)
    }

    fun requestCommandSuggestions(input: String) {
        client?.requestCommandSuggestions(input)
    }

    fun appendPluginLog(pluginId: String, message: String) {
        appendSystem("[外部插件:$pluginId] $message")
    }

    // ---- 内部编排 ----

    private suspend fun connectOnce(request: ConnectionRequest, attempt: Int) {
        if (!request.isCurrent()) return
        val addr = request.address
        val version = request.version

        // 解析协议号：自动识别 → ping
        val protocol = resolveProtocol(request, addr, version)
        if (!request.isCurrent()) return
        if (protocol == null) {
            publishSystem(request, "连接失败：无法确定协议版本（内置表无「${version.id}」，且自动识别未获取到版本，请改用「自动识别」或选择真实存在的版本）")
            publishState(request, ConnectionState.Failed("无法确定协议版本"))
            scheduleReconnect(request, attempt)
            return
        }

        val session = authManager.currentSession()
        if (!request.isCurrent()) return
        if (session == null) {
            publishState(request, ConnectionState.Failed("未登录"))
            return
        }

        // 强制签名模式：连接前取玩家证书（私钥只在内存流转）
        val certificate = if (request.signingMode == ChatSigningMode.SIGNED) {
            publishSystem(request, "正在获取签名证书…")
            when (val r = withTimeoutOrNull(CERTIFICATE_FETCH_TIMEOUT_MS) { authManager.fetchCertificate(session.mcAccessToken) }) {
                is com.bilicraft.handheld.auth.AuthClient.Step.Ok -> r.value
                is com.bilicraft.handheld.auth.AuthClient.Step.Err -> {
                    if (request.isCurrent()) publishSystem(request, "证书获取失败：${r.reason}，回退为未签名模式")
                    null
                }
                null -> {
                    if (request.isCurrent()) publishSystem(request, "证书获取超时，回退为未签名模式")
                    null
                }
            }
        } else null
        if (!request.isCurrent()) return

        val mc = MinecraftClient(
            palette = PaletteRegistry.forProtocol(protocol),
            protocolNumber = protocol,
            accessToken = session.mcAccessToken,
            playerName = session.mcUsername,
            playerUuid = session.mcUuid,
            signingMode = if (certificate != null) ChatSigningMode.SIGNED else ChatSigningMode.UNSIGNED,
            certificate = certificate
        )
        client = mc

        // 泵：把 client 的状态与聊天接到本控制器
        pumpJob?.cancel()
        pumpJob = scope.launch {
            launch {
                var connectionStarted = false
                mc.state.collect { state ->
                    if (!request.isCurrent()) return@collect
                    if (state is ConnectionState.Disconnected && !connectionStarted) return@collect
                    connectionStarted = true
                    onConnState(request, state, attempt)
                }
            }
            launch {
                mc.incoming.collect { ev ->
                    if (!request.isCurrent()) return@collect
                    publishChat(request, ev)
                    if (PLUGINS_ENABLED) pluginManager.dispatchChat(ev)
                }
            }
            launch {
                mc.commandSuggestions.collect { suggestions ->
                    if (!request.isCurrent()) return@collect
                    _commandSuggestions.value = suggestions
                }
            }
        }
        mc.connect(addr)
    }

    /** 协议号解析：内置表命中直接用；自动识别 ping 服务器 */
    private suspend fun resolveProtocol(request: ConnectionRequest, addr: ServerAddress, version: McVersion): Int? {
        versionRepo.resolveProtocol(version)?.let { return it }
        // 走到这里说明是自动识别或未知版本 → ping
        publishSystem(request, "正在自动识别服务器版本…")
        val status = ServerPinger().ping(addr).getOrNull()
        if (!request.isCurrent()) return null
        return status?.protocol?.takeIf { it > 0 }?.also {
            publishSystem(request, "识别到服务器版本：${status.versionName}（协议 $it）")
        }
    }

    private fun onConnState(request: ConnectionRequest, state: ConnectionState, attempt: Int) {
        publishState(request, state)
        when (state) {
            is ConnectionState.Connected -> {
                reconnectJob?.cancel()
                publishSystem(request, "已连接到服务器")
            }
            is ConnectionState.Failed -> {
                publishSystem(request, "连接失败：${state.reason}")
                if (state.retriable) scheduleReconnect(request, attempt)
                else {
                    reconnectAllowed = false
                    reconnectJob?.cancel()
                }
            }
            is ConnectionState.Disconnected -> {
                if (reconnectAllowed) scheduleReconnect(request, attempt)
            }
            else -> Unit
        }
    }

    /** 指数退避重连 */
    private fun scheduleReconnect(request: ConnectionRequest, prevAttempt: Int) {
        if (!request.isCurrent() || !reconnectAllowed || reconnectJob?.isActive == true) return
        val attempt = prevAttempt + 1
        if (attempt > MAX_RECONNECT) {
            publishSystem(request, "已达最大重连次数（$MAX_RECONNECT），停止重连")
            reconnectAllowed = false
            return
        }
        val backoff = minOf(30_000L, 1000L * (1L shl attempt))  // 2s,4s,8s… 上限30s
        publishState(request, ConnectionState.Reconnecting(attempt))
        publishSystem(request, "第 $attempt 次重连，${backoff / 1000}s 后…")
        reconnectJob = scope.launch {
            delay(backoff)
            if (request.isCurrent() && reconnectAllowed) {
                reconnectJob = null
                connectOnce(request, attempt)
            }
        }
    }

    private fun publishState(request: ConnectionRequest, state: ConnectionState) {
        if (!request.isCurrent()) return
        publishState(request.serverId, state)
    }

    private fun publishState(serverId: String?, state: ConnectionState) {
        _connState.value = state
        _events.tryEmit(SessionEvent.State(serverId, state))
    }

    private fun publishChat(request: ConnectionRequest, ev: ChatEvent) {
        if (!request.isCurrent()) return
        appendChat(ev)
        _events.tryEmit(SessionEvent.Chat(request.serverId, ev))
    }

    private fun publishSystem(request: ConnectionRequest, text: String) {
        publishChat(request, ChatEvent(plainText = text, rawJson = text, sender = null))
    }

    private fun appendChat(ev: ChatEvent) {
        _log.value = (_log.value + ev).takeLast(MAX_LOG)
    }

    private fun appendSystem(text: String) {
        appendChat(ChatEvent(plainText = text, rawJson = text, sender = null))
    }

    private fun ConnectionRequest.isCurrent(): Boolean = activeRequest?.seq == seq

    private data class ConnectionRequest(
        val seq: Long,
        val serverId: String?,
        val address: ServerAddress,
        val version: McVersion,
        val signingMode: ChatSigningMode
    )

    fun dispose() {
        stop()
        pluginManager.unloadAll()
        scope.cancel()
    }

    private companion object {
        // 正式包先关闭插件链路，避免内置示例自动回复被加载。
        const val PLUGINS_ENABLED = false
        const val MAX_RECONNECT = 6
        const val MAX_LOG = 500
        const val CERTIFICATE_FETCH_TIMEOUT_MS = 70_000L
    }
}