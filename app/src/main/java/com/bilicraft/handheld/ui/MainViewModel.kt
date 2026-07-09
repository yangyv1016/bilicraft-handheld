package com.bilicraft.handheld.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bilicraft.handheld.AppContainer
import com.bilicraft.handheld.BuildConfig
import com.bilicraft.handheld.auth.AuthState
import com.bilicraft.handheld.config.QuickToolLink
import com.bilicraft.handheld.config.ServerConfig
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.ChatSigningMode
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.service.ConnectionService
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI 状态聚合层。
 *
 * 依赖现有逻辑：登录、Token 刷新、连接、聊天、插件生命周期仍由既有模块负责。
 * 纯 UI 补足：服务器配置与快捷工具通过 UiConfigRepository 保存为应用私有 JSON。
 * 这个 ViewModel 不计算 Token、不解析协议、不直接操作加密存储。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = AppContainer.authManager
    private val session = AppContainer.session
    private val versionRepo = AppContainer.versionRepo
    private val uiConfigRepo = AppContainer.uiConfigRepo

    val authState: StateFlow<AuthState> = auth.state
    val connState: StateFlow<ConnectionState> = session.connState
    val chatLog: StateFlow<List<ChatEvent>> = session.log
    val servers: StateFlow<List<ServerConfig>> = uiConfigRepo.servers
    val quickTools: StateFlow<List<QuickToolLink>> = uiConfigRepo.tools

    private val _versions = MutableStateFlow(versionRepo.builtInGrouped())
    val versions: StateFlow<VersionRepository.Grouped> = _versions.asStateFlow()

    private val _selectedVersion = MutableStateFlow(versionRepo.builtInGrouped().autoDetect)
    val selectedVersion: StateFlow<McVersion> = _selectedVersion.asStateFlow()

    private val _loggedIn = MutableStateFlow(auth.currentSession() != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _forceSigning = MutableStateFlow(false)
    val forceSigning: StateFlow<Boolean> = _forceSigning.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _loginOverlay = MutableStateFlow(false)
    val loginOverlay: StateFlow<Boolean> = _loginOverlay.asStateFlow()

    private var loginJob: Job? = null

    val currentAccountName: String
        get() = auth.currentSession()?.mcUsername ?: "未登录"

    val packageNameText: String
        get() = getApplication<Application>().packageName

    val versionNameText: String
        get() = BuildConfig.VERSION_NAME

    val pluginNames: List<String>
        get() = session.pluginManager.loadedNames()

    init {
        viewModelScope.launch { uiConfigRepo.load() }
        viewModelScope.launch {
            if (auth.currentSession() != null) {
                val refreshed = auth.silentRefresh()
                _loggedIn.value = refreshed != null
            }
        }
        refreshVersions(silent = true)
    }

    fun consumeUiMessage() {
        _uiMessage.value = null
    }

    fun startLogin() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            auth.startDeviceLogin()
            _loggedIn.value = auth.currentSession() != null
            _loginOverlay.value = false
        }
    }

    fun cancelLoginOverlay() {
        loginJob?.cancel()
        loginJob = null
        _loginOverlay.value = false
        _loggedIn.value = auth.currentSession() != null
    }

    fun refreshToken() {
        viewModelScope.launch {
            val refreshed = auth.silentRefresh()
            _loggedIn.value = refreshed != null
            _uiMessage.value = if (refreshed != null) "Token 已刷新" else "Token 刷新失败，请重新登录"
        }
    }

    fun logout() {
        stopConnection()
        auth.logout()
        _loggedIn.value = false
    }

    fun selectVersion(v: McVersion) {
        _selectedVersion.value = v
    }

    fun setForceSigning(on: Boolean) {
        _forceSigning.value = on
    }

    fun refreshVersions(silent: Boolean = false) {
        viewModelScope.launch {
            val result = runCatching { versionRepo.refreshFromMojang() }
            result.getOrNull()?.let { _versions.value = it }
            if (!silent) {
                _uiMessage.value = if (result.isSuccess) "版本列表已刷新" else "版本列表刷新失败，已保留本地列表"
            }
        }
    }

    fun clearVersionCache() {
        versionRepo.clearCache()
        _versions.value = versionRepo.builtInGrouped()
        _selectedVersion.value = _versions.value.autoDetect
        _uiMessage.value = "版本缓存已清除"
    }

    fun saveServer(config: ServerConfig) {
        viewModelScope.launch {
            uiConfigRepo.upsertServer(config)
            _uiMessage.value = "服务器配置已保存"
        }
    }

    fun createServer(
        name: String,
        host: String,
        port: Int,
        version: McVersion,
        signingRequired: Boolean
    ) {
        val safeName = name.ifBlank { host.ifBlank { "未命名服务器" } }
        if (host.isBlank()) {
            _uiMessage.value = "服务器地址不能为空"
            return
        }
        saveServer(
            uiConfigRepo.newServer(
                name = safeName,
                host = host.trim(),
                port = port.takeIf { it in 1..65535 } ?: 25565,
                version = version,
                signingRequired = signingRequired
            )
        )
    }

    fun deleteServer(id: String) {
        viewModelScope.launch {
            uiConfigRepo.deleteServer(id)
            _uiMessage.value = "服务器配置已删除"
        }
    }

    fun connect(config: ServerConfig) {
        _selectedVersion.value = config.toMcVersion()
        _forceSigning.value = config.signingRequired
        connect(config.host, config.port, config.toMcVersion(), config.signingRequired)
    }

    fun connect(host: String, port: Int, version: McVersion = _selectedVersion.value, signing: Boolean = _forceSigning.value) {
        if (host.isBlank()) {
            _uiMessage.value = "服务器地址不能为空"
            return
        }
        val ctx = getApplication<Application>()
        val mode = if (signing) ChatSigningMode.SIGNED else ChatSigningMode.UNSIGNED
        val intent = ConnectionService.startIntent(ctx, host.trim(), port, version, mode)
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

    fun createTool(title: String, url: String, description: String = "") {
        if (title.isBlank() || url.isBlank()) {
            _uiMessage.value = "名称和链接不能为空"
            return
        }
        viewModelScope.launch {
            uiConfigRepo.upsertTool(uiConfigRepo.newTool(title.trim(), url.trim(), description.trim()))
            _uiMessage.value = "快捷工具已保存"
        }
    }

    fun saveTool(link: QuickToolLink) {
        viewModelScope.launch {
            uiConfigRepo.upsertTool(link)
            _uiMessage.value = "快捷工具已保存"
        }
    }

    fun deleteTool(id: String) {
        viewModelScope.launch {
            uiConfigRepo.deleteTool(id)
            _uiMessage.value = "快捷工具已删除"
        }
    }

    fun moveTool(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { uiConfigRepo.moveTool(fromIndex, toIndex) }
    }

    fun addAccount() {
        _loginOverlay.value = true
        startLogin()
    }

    fun switchAccount() {
        _uiMessage.value = "当前底层仅暴露单账号会话，暂不支持多账号切换"
    }

    fun openPluginLog(name: String) {
        _uiMessage.value = "$name 的运行日志已输出到连接日志"
    }

    fun setPluginEnabled(name: String, enabled: Boolean) {
        _uiMessage.value = if (enabled) {
            "$name 是内置插件，已随会话加载"
        } else {
            "$name 是内置插件，当前不提供 UI 禁用接口"
        }
    }
}