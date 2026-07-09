package com.bilicraft.handheld.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bilicraft.handheld.AppContainer
import com.bilicraft.handheld.auth.AuthState
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.ChatSigningMode
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.service.ConnectionService
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI 状态聚合。ViewModel 只做「把各模块的 Flow 汇聚给 Compose + 转发用户意图」，
 * 不含业务逻辑（业务都在 AuthManager / SessionController）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = AppContainer.authManager
    private val session = AppContainer.session
    private val versionRepo = AppContainer.versionRepo

    val authState: StateFlow<AuthState> = auth.state
    val connState: StateFlow<ConnectionState> = session.connState
    val chatLog: StateFlow<List<ChatEvent>> = session.log

    // 版本下拉：先用内置表秒开，再异步用 manifest 刷新
    private val _versions = MutableStateFlow(versionRepo.builtInGrouped())
    val versions: StateFlow<VersionRepository.Grouped> = _versions.asStateFlow()

    private val _selectedVersion = MutableStateFlow(versionRepo.builtInGrouped().autoDetect)
    val selectedVersion: StateFlow<McVersion> = _selectedVersion.asStateFlow()

    // 已登录会话是否存在（决定首屏是登录页还是主控页）
    private val _loggedIn = MutableStateFlow(auth.currentSession() != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    val pluginNames: List<String> get() = session.pluginManager.loadedNames()

    init {
        // 启动即静默刷新 token（用户无感知）
        viewModelScope.launch {
            if (auth.currentSession() != null) {
                val refreshed = auth.silentRefresh()
                _loggedIn.value = refreshed != null
            }
        }
        // 异步拉取完整版本列表
        viewModelScope.launch {
            runCatching { versionRepo.refreshFromMojang() }.getOrNull()?.let { _versions.value = it }
        }
    }

    fun startLogin() {
        viewModelScope.launch {
            auth.startDeviceLogin()
            _loggedIn.value = auth.currentSession() != null
        }
    }

    fun logout() {
        stopConnection()
        auth.logout()
        _loggedIn.value = false
    }

    fun selectVersion(v: McVersion) { _selectedVersion.value = v }

    // 强制签名开关（默认关闭）
    private val _forceSigning = MutableStateFlow(false)
    val forceSigning: StateFlow<Boolean> = _forceSigning.asStateFlow()
    fun setForceSigning(on: Boolean) { _forceSigning.value = on }

    /** 一键连接：通过前台 Service 启动，保证锁屏存活 */
    fun connect(host: String, port: Int) {
        val ctx = getApplication<Application>()
        val mode = if (_forceSigning.value) ChatSigningMode.SIGNED else ChatSigningMode.UNSIGNED
        val intent = ConnectionService.startIntent(ctx, host, port, _selectedVersion.value, mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }

    fun stopConnection() {
        val ctx = getApplication<Application>()
        ctx.startService(ConnectionService.stopIntent(ctx))
    }

    fun sendChat(text: String) {
        if (text.isNotBlank()) session.sendChat(text)
    }
}