package com.bilicraft.handheld.session

import com.bilicraft.handheld.auth.AuthManager
import com.bilicraft.handheld.plugin.PluginHost
import com.bilicraft.handheld.plugin.PluginManager
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.ChatSigningMode
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.protocol.MinecraftClient
import com.bilicraft.handheld.protocol.PacketProfile
import com.bilicraft.handheld.protocol.ServerAddress
import com.bilicraft.handheld.protocol.ServerPinger
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

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

    // 插件宿主：把插件的 sendChat 接到当前 client
    private val pluginHost = object : PluginHost {
        override fun sendChat(text: String) { client?.sendChat(text) }
        override fun log(message: String) = appendSystem("[插件] $message")
    }
    val pluginManager = PluginManager(pluginHost)

    private var client: MinecraftClient? = null
    private var target: ServerAddress? = null
    private var selectedVersion: McVersion? = null
    private var signingMode: ChatSigningMode = ChatSigningMode.UNSIGNED
    private var reconnectAllowed = true
    private var pumpJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        pluginManager.installBuiltins()
        // 插件错误也进日志，用户可见但不崩溃
        scope.launch {
            pluginManager.errors.collect { e ->
                appendSystem("[插件错误] ${e.pluginName}.${e.phase}: ${e.message}")
            }
        }
    }

    /** 启动连接。version 为「自动识别」时先 ping 拿协议号。 */
    fun start(address: ServerAddress, version: McVersion, mode: ChatSigningMode) {
        reconnectAllowed = false
        reconnectJob?.cancel()
        pumpJob?.cancel()
        val previousClient = client
        client = null
        previousClient?.disconnect()
        target = address
        selectedVersion = version
        signingMode = mode
        reconnectAllowed = true
        scope.launch { connectOnce(attempt = 0) }
    }

    fun stop() {
        reconnectAllowed = false
        reconnectJob?.cancel()
        pumpJob?.cancel()
        val previousClient = client
        client = null
        previousClient?.disconnect()
        _connState.value = ConnectionState.Disconnected
    }

    fun sendChat(text: String) {
        client?.sendChat(text)
        appendSystem("> $text")   // 本地回显发送内容
    }

    // ---- 内部编排 ----

    private suspend fun connectOnce(attempt: Int) {
        val addr = target ?: return
        val version = selectedVersion ?: return

        // 解析协议号：自动识别 → ping
        val protocol = resolveProtocol(addr, version)
        if (protocol == null) {
            _connState.value = ConnectionState.Failed("无法确定协议版本")
            scheduleReconnect(attempt)
            return
        }

        val session = authManager.currentSession()
        if (session == null) {
            _connState.value = ConnectionState.Failed("未登录")
            return
        }

        // 强制签名模式：连接前取玩家证书（私钥只在内存流转）
        val certificate = if (signingMode == ChatSigningMode.SIGNED) {
            appendSystem("正在获取签名证书…")
            when (val r = authManager.fetchCertificate(session.mcAccessToken)) {
                is com.bilicraft.handheld.auth.AuthClient.Step.Ok -> r.value
                is com.bilicraft.handheld.auth.AuthClient.Step.Err -> {
                    appendSystem("证书获取失败：${r.reason}，回退为未签名模式")
                    null
                }
            }
        } else null

        val mc = MinecraftClient(
            profile = PacketProfile.forProtocol(protocol),
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
                    if (state is ConnectionState.Disconnected && !connectionStarted) return@collect
                    connectionStarted = true
                    onConnState(state, attempt)
                }
            }
            launch { mc.incoming.collect { ev ->
                appendChat(ev)
                pluginManager.dispatchChat(ev)   // 广播给插件
            } }
        }
        mc.connect(addr)
    }

    /** 协议号解析：内置表命中直接用；自动识别 ping 服务器 */
    private suspend fun resolveProtocol(addr: ServerAddress, version: McVersion): Int? {
        versionRepo.resolveProtocol(version)?.let { return it }
        // 走到这里说明是自动识别或未知版本 → ping
        appendSystem("正在自动识别服务器版本…")
        val status = ServerPinger().ping(addr).getOrNull()
        return status?.protocol?.takeIf { it > 0 }?.also {
            appendSystem("识别到服务器版本：${status.versionName}（协议 $it）")
        }
    }

    private fun onConnState(state: ConnectionState, attempt: Int) {
        _connState.value = state
        when (state) {
            is ConnectionState.Connected -> {
                reconnectJob?.cancel()
                appendSystem("已连接到服务器")
            }
            is ConnectionState.Failed -> {
                appendSystem("连接失败：${state.reason}")
                if (state.retriable) scheduleReconnect(attempt)
                else {
                    reconnectAllowed = false
                    reconnectJob?.cancel()
                }
            }
            is ConnectionState.Disconnected -> {
                if (reconnectAllowed) scheduleReconnect(attempt)
            }
            else -> Unit
        }
    }

    /** 指数退避重连 */
    private fun scheduleReconnect(prevAttempt: Int) {
        if (!reconnectAllowed || reconnectJob?.isActive == true) return
        val attempt = prevAttempt + 1
        if (attempt > MAX_RECONNECT) {
            appendSystem("已达最大重连次数（$MAX_RECONNECT），停止重连")
            reconnectAllowed = false
            return
        }
        val backoff = minOf(30_000L, 1000L * (1L shl attempt))  // 2s,4s,8s… 上限30s
        _connState.value = ConnectionState.Reconnecting(attempt)
        appendSystem("第 $attempt 次重连，${backoff / 1000}s 后…")
        reconnectJob = scope.launch {
            delay(backoff)
            if (reconnectAllowed) {
                reconnectJob = null
                connectOnce(attempt)
            }
        }
    }

    private fun appendChat(ev: ChatEvent) {
        _log.value = (_log.value + ev).takeLast(MAX_LOG)
    }

    private fun appendSystem(text: String) {
        appendChat(ChatEvent(plainText = text, rawJson = text, sender = null))
    }

    fun dispose() {
        stop()
        pluginManager.unloadAll()
        scope.cancel()
    }

    private companion object {
        const val MAX_RECONNECT = 6
        const val MAX_LOG = 500
    }
}