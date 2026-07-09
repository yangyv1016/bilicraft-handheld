package com.bilicraft.handheld.auth

import com.bilicraft.handheld.storage.AuthSession
import com.bilicraft.handheld.storage.SecureStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * auth 编排层：把 AuthClient 的各个纯变换串成完整流程，管理状态机与持久化。
 *
 * 职责边界：
 * - AuthClient 只做单步 HTTP 变换（无状态）
 * - SecureStore 只做加密存取（无语义）
 * - AuthManager 负责编排顺序、状态流转、落盘、静默刷新（业务语义都在这里）
 *
 * 对 UI 只暴露 state: StateFlow<AuthState>，UI 不接触任何 token 细节。
 */
class AuthManager(
    private val client: AuthClient,
    private val store: SecureStore
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * 启动设备码登录。这是个长流程 suspend：
     * 申请设备码 → 展示给用户 → 轮询 → 换取全链路 token → 落盘。
     */
    suspend fun startDeviceLogin() {
        _state.value = AuthState.RequestingDeviceCode

        val info = when (val r = client.requestDeviceCode()) {
            is AuthClient.Step.Ok -> r.value
            is AuthClient.Step.Err -> { fail(r.reason); return }
        }
        _state.value = AuthState.WaitingForUser(info)

        // 轮询直到用户授权 / 超时
        var waited = 0
        while (waited < info.expiresIn) {
            delay(info.interval * 1000L)
            waited += info.interval
            when (val poll = client.pollForToken(info.deviceCode)) {
                is AuthClient.PollResult.Ok -> {
                    exchangeAndPersist(poll.token)
                    return
                }
                is AuthClient.PollResult.Pending -> Unit   // 继续等
                is AuthClient.PollResult.Err -> { fail(poll.reason); return }
            }
        }
        fail("授权超时，请重新登录")
    }

    /**
     * 静默刷新：App 启动或 token 将过期时调用，用户无感知。
     * 成功返回可用的 AuthSession；失败返回 null（需要重新登录）。
     */
    suspend fun silentRefresh(): AuthSession? {
        val existing = store.loadSession() ?: return null
        val ms = when (val r = client.refreshMsToken(existing.msRefreshToken)) {
            is AuthClient.Step.Ok -> r.value
            is AuthClient.Step.Err -> return null
        }
        return runFullChain(ms)?.also { _state.value = AuthState.Success(McProfile(it.mcUuid, it.mcUsername)) }
    }

    /** 读取本地会话（不触网） */
    fun currentSession(): AuthSession? = store.loadSession()

    /** 获取玩家签名证书（强制签名模式用）。委托给 AuthClient，私钥不落盘。 */
    suspend fun fetchCertificate(mcAccessToken: String): AuthClient.Step<PlayerCertificate> =
        client.fetchCertificate(mcAccessToken)

    /** 登出 */
    fun logout() {
        store.clear()
        _state.value = AuthState.Idle
    }

    // ---- 内部编排 ----

    private suspend fun exchangeAndPersist(ms: MsToken) {
        _state.value = AuthState.ExchangingTokens
        val session = runFullChain(ms)
        if (session == null) {
            fail("换取游戏 token 失败")
            return
        }
        _state.value = AuthState.Success(McProfile(session.mcUuid, session.mcUsername))
    }

    /**
     * MS token → 完整 AuthSession 的变换链。任何一步失败即返回 null。
     * 成功则落盘并返回。这是刷新与首登共用的核心管线。
     */
    private suspend fun runFullChain(ms: MsToken): AuthSession? {
        val xbl = client.authXbl(ms.accessToken).okOrNull() ?: return null
        val xsts = client.authXsts(xbl.token).okOrNull() ?: return null
        val mc = client.loginMinecraft(xsts.userHash, xsts.token).okOrNull() ?: return null

        // 验权：无游戏所有权直接失败
        val owns = client.checkEntitlements(mc.accessToken).okOrNull() ?: false
        if (!owns) return null

        val profile = client.fetchProfile(mc.accessToken).okOrNull() ?: return null

        val session = AuthSession(
            msRefreshToken = ms.refreshToken,
            mcAccessToken = mc.accessToken,
            mcUuid = profile.uuid,
            mcUsername = profile.name,
            mcTokenObtainedAt = System.currentTimeMillis(),
            mcTokenExpiresIn = mc.expiresIn
        )
        store.saveSession(session)
        return session
    }

    private fun <T> AuthClient.Step<T>.okOrNull(): T? =
        (this as? AuthClient.Step.Ok)?.value

    private fun fail(reason: String) {
        _state.value = AuthState.Failed(reason)
    }
}