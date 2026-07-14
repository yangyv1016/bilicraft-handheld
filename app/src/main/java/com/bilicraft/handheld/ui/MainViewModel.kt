package com.bilicraft.handheld.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bilicraft.handheld.AppContainer
import com.bilicraft.handheld.BuildConfig
import com.bilicraft.handheld.appicon.AppIcon
import com.bilicraft.handheld.appicon.AppIconCatalog
import com.bilicraft.handheld.auth.AccountSummary
import com.bilicraft.handheld.auth.AuthState
import com.bilicraft.handheld.config.QuickToolLink
import com.bilicraft.handheld.config.ServerConfig
import com.bilicraft.handheld.config.UiPreferences
import com.bilicraft.handheld.externalplugin.ExternalPluginEntry
import com.bilicraft.handheld.externalplugin.ExternalPluginEntrypoint
import com.bilicraft.handheld.externalplugin.ExternalPluginPanelHandle
import com.bilicraft.handheld.pluginmarket.OfficialPluginMarketState
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.ChatSigningMode
import com.bilicraft.handheld.protocol.CommandSuggestionState
import com.bilicraft.handheld.protocol.CommandSuggestions
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.service.ConnectionService
import com.bilicraft.handheld.session.SessionEvent
import com.bilicraft.handheld.update.DownloadSource
import com.bilicraft.handheld.update.ReleaseInfo
import com.bilicraft.handheld.update.UpdateState
import java.io.File
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单会话连接在多服务器 Tab 上的 UI 投影。
 * activeServerId 表示当前底层单连接归属哪个服务器配置；聊天日志按服务器配置隔离展示。
 */
data class ServerRuntimeUiState(
    val activeServerId: String? = null,
    val connectionStates: Map<String, ConnectionState> = emptyMap(),
    val chatLogs: Map<String, List<ChatEvent>> = emptyMap()
)

data class ActiveExternalPluginPanel(
    val pluginId: String,
    val entrypointId: String
)

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
    private val updateManager = AppContainer.updateManager
    private val appIconManager = AppContainer.appIconManager
    private val externalPluginManager = AppContainer.externalPluginManager
    private val officialPluginMarket = AppContainer.officialPluginMarket
    private val cdkRepository = AppContainer.cdkRepository

    val authState: StateFlow<AuthState> = auth.state
    val updateState: StateFlow<UpdateState> = updateManager.state
    val accounts: StateFlow<List<AccountSummary>> = auth.accounts
    val servers: StateFlow<List<ServerConfig>> = uiConfigRepo.servers
    val quickTools: StateFlow<List<QuickToolLink>> = uiConfigRepo.tools
    val preferences: StateFlow<UiPreferences> = uiConfigRepo.preferences
    val externalPlugins: StateFlow<List<ExternalPluginEntry>> = externalPluginManager.entries
    val externalPluginEntrypoints: StateFlow<List<ExternalPluginEntrypoint>> = externalPluginManager.entrypoints
    val officialMarket: StateFlow<OfficialPluginMarketState> = officialPluginMarket.state
    val cdkState = cdkRepository.state

    private val _serverRuntime = MutableStateFlow(ServerRuntimeUiState())
    val serverRuntime: StateFlow<ServerRuntimeUiState> = _serverRuntime.asStateFlow()

    private val _commandSuggestions = MutableStateFlow(CommandSuggestions.Empty)
    val commandSuggestions: StateFlow<CommandSuggestionState> = _commandSuggestions.asStateFlow()

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

    val appIcons: List<AppIcon> = AppIconCatalog.all
    private val _currentAppIcon = MutableStateFlow(appIconManager.current())
    val currentAppIcon: StateFlow<AppIcon> = _currentAppIcon.asStateFlow()

    private val _activeExternalPluginPanel = MutableStateFlow<ActiveExternalPluginPanel?>(null)
    val activeExternalPluginPanel: StateFlow<ActiveExternalPluginPanel?> = _activeExternalPluginPanel.asStateFlow()

    private var loginJob: Job? = null

    val currentAccountName: String
        get() = auth.currentSession()?.mcUsername ?: "未登录"

    val packageNameText: String
        get() = getApplication<Application>().packageName

    val versionNameText: String
        get() = BuildConfig.VERSION_NAME

    val pluginDropDirText: String
        get() = externalPluginManager.pluginDropDir.absolutePath

    init {
        viewModelScope.launch {
            uiConfigRepo.load()
            updateManager.checkForUpdate(silent = true, source = preferences.value.downloadSource)
        }
        viewModelScope.launch(Dispatchers.IO) {
            externalPluginManager.refresh()
            officialPluginMarket.loadCache()
            officialPluginMarket.syncInstalledState()
            officialPluginMarket.refresh()
        }
        viewModelScope.launch(Dispatchers.IO) {
            cdkRepository.loadCache()
            cdkRepository.refresh()
        }
        viewModelScope.launch { mirrorSessionEvents() }
        viewModelScope.launch { mirrorCommandSuggestions() }
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

    private suspend fun mirrorSessionEvents() {
        session.events.collect { event ->
            val serverId = event.serverId ?: _serverRuntime.value.activeServerId ?: return@collect
            when (event) {
                is SessionEvent.State -> markServerState(serverId, event.state)
                is SessionEvent.Chat -> appendServerChat(serverId, event.event)
            }
        }
    }

    private suspend fun mirrorCommandSuggestions() {
        session.commandSuggestions.collect { suggestions ->
            _commandSuggestions.value = suggestions
        }
    }

    private fun appendServerChat(serverId: String, event: ChatEvent) {
        _serverRuntime.update { current ->
            val currentLog = current.chatLogs[serverId].orEmpty()
            current.copy(
                chatLogs = current.chatLogs + (serverId to (currentLog + event).takeLast(MAX_UI_LOG))
            )
        }
    }

    private fun markServerState(serverId: String, state: ConnectionState) {
        _serverRuntime.update { current ->
            current.copy(
                connectionStates = current.connectionStates + (serverId to state)
            )
        }
    }

    private fun activateServer(serverId: String) {
        _serverRuntime.update { current -> current.copy(activeServerId = serverId) }
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

    fun setChatAutoScroll(enabled: Boolean) {
        viewModelScope.launch {
            uiConfigRepo.setChatAutoScroll(enabled)
            _uiMessage.value = if (enabled) "聊天将自动滚动到最新消息" else "聊天自动滚动已关闭"
        }
    }

    fun setCommandCompletionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            uiConfigRepo.setCommandCompletionEnabled(enabled)
            if (!enabled) {
                _commandSuggestions.value = CommandSuggestions.Empty
                session.requestCommandSuggestions("")
            }
            _uiMessage.value = if (enabled) "命令补全已开启" else "命令补全已关闭"
        }
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
            _serverRuntime.update { current ->
                current.copy(
                    activeServerId = current.activeServerId.takeIf { it != id },
                    connectionStates = current.connectionStates - id,
                    chatLogs = current.chatLogs - id
                )
            }
            _uiMessage.value = "服务器配置已删除"
        }
    }

    fun connect(config: ServerConfig) {
        _commandSuggestions.value = CommandSuggestions.Empty
        val previousActiveId = _serverRuntime.value.activeServerId
        if (previousActiveId != null && previousActiveId != config.id) {
            markServerState(previousActiveId, ConnectionState.Disconnected)
        }
        activateServer(config.id)
        markServerState(config.id, ConnectionState.Connecting)
        _selectedVersion.value = config.toMcVersion()
        _forceSigning.value = config.signingRequired
        startConnection(config.id, config.host, config.port, config.toMcVersion(), config.signingRequired)
    }

    private fun startConnection(serverId: String, host: String, port: Int, version: McVersion, signing: Boolean) {
        if (host.isBlank()) {
            _uiMessage.value = "服务器地址不能为空"
            return
        }
        val ctx = getApplication<Application>()
        val mode = if (signing) ChatSigningMode.SIGNED else ChatSigningMode.UNSIGNED
        val intent = ConnectionService.startIntent(ctx, serverId, host.trim(), port, version, mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }

    fun stopConnection() {
        _commandSuggestions.value = CommandSuggestions.Empty
        val activeId = _serverRuntime.value.activeServerId
        val ctx = getApplication<Application>()
        ctx.startService(ConnectionService.stopIntent(ctx))
        _serverRuntime.update { current ->
            if (activeId == null) current else current.copy(
                activeServerId = null,
                connectionStates = current.connectionStates + (activeId to ConnectionState.Disconnected)
            )
        }
    }

    fun sendChat(serverId: String, text: String) {
        if (text.isBlank() || _serverRuntime.value.activeServerId != serverId) return
        _commandSuggestions.value = CommandSuggestions.Empty
        session.sendChat(text)
    }

    fun respawn() {
        val activeId = _serverRuntime.value.activeServerId
        if (activeId == null || _serverRuntime.value.connectionStates[activeId] !is ConnectionState.Connected) {
            _uiMessage.value = "请先连接服务器再复活"
            return
        }
        session.respawn()
        _uiMessage.value = "已发送复活请求"
    }

    fun requestCommandSuggestions(serverId: String, input: String) {
        if (_serverRuntime.value.activeServerId != serverId || !preferences.value.commandCompletionEnabled) {
            _commandSuggestions.value = CommandSuggestions.Empty
            return
        }
        if (!input.startsWith("/")) {
            _commandSuggestions.value = CommandSuggestions.clearFor(input)
            session.requestCommandSuggestions(input)
            return
        }
        session.requestCommandSuggestions(input)
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

    /**
     * 切换活跃账户。凭证随账户改变，因此先断开当前连接，切换后由用户重新连接。
     * 切换会对新账户做一次静默刷新（token 可能已过期）。
     */
    fun switchAccount(uuid: String) {
        val current = auth.currentSession()
        if (current?.mcUuid == uuid) return
        viewModelScope.launch {
            stopConnection()
            val switched = auth.switchAccount(uuid)
            _loggedIn.value = switched != null
            _uiMessage.value = when {
                switched == null -> "切换账号失败，请重新登录该账号"
                else -> "已切换到 ${switched.mcUsername}"
            }
        }
    }

    /**
     * 移除指定账户。若移除的是当前活跃账户，先断开连接；
     * 移除后若已无任何账户，回到未登录态。
     */
    fun removeAccount(uuid: String) {
        val wasActive = auth.currentSession()?.mcUuid == uuid
        if (wasActive) stopConnection()
        val remainingActive = auth.removeAccount(uuid)
        _loggedIn.value = remainingActive != null
        _uiMessage.value = if (remainingActive != null) {
            "账号已移除，当前账号：${remainingActive.mcUsername}"
        } else {
            "账号已移除"
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            updateManager.checkForUpdate(silent = false, source = preferences.value.downloadSource)
        }
    }

    fun downloadUpdate(info: ReleaseInfo) {
        viewModelScope.launch { updateManager.download(info, preferences.value.downloadSource) }
    }

    fun setDownloadSource(source: DownloadSource) {
        viewModelScope.launch {
            uiConfigRepo.setDownloadSource(source)
            _uiMessage.value = "下载线路已切换为「${source.displayName}」"
        }
    }

    fun selectAppIcon(icon: AppIcon) {
        if (icon.id == _currentAppIcon.value.id) return
        appIconManager.apply(icon)
        _currentAppIcon.value = icon
        _uiMessage.value = "启动图标已切换为「${icon.displayName}」，桌面图标可能稍后刷新"
    }

    fun installUpdate(apkFile: File) {
        updateManager.installApk(apkFile)
    }

    fun dismissUpdate() {
        updateManager.dismiss()
    }

    fun refreshOfficialPluginMarket() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { officialPluginMarket.refresh() }
            _uiMessage.value = result.fold(
                onSuccess = { "插件市场已刷新，共 $it 个插件" },
                onFailure = { "插件市场刷新失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    fun refreshCdk() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { cdkRepository.refresh() }
            _uiMessage.value = result.fold(
                onSuccess = { count -> if (count > 0) "CDK 已刷新，当前可显示 $count 个" else "CDK 已刷新，当前没有可显示内容" },
                onFailure = { "CDK 刷新失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    fun refreshCdkActiveWindow() {
        cdkRepository.refreshActiveWindow()
    }

    fun installOfficialPlugin(pluginId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { officialPluginMarket.install(pluginId) }
            _uiMessage.value = result.fold(
                onSuccess = { "官方插件已安装：${it.name}" },
                onFailure = { "官方插件安装失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    fun refreshExternalPlugins() {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) { externalPluginManager.refresh() }
            officialPluginMarket.syncInstalledState()
            _uiMessage.value = if (imported > 0) {
                "已导入 $imported 个外部插件包"
            } else {
                "外部插件目录已重新扫描"
            }
        }
    }

    fun importExternalPlugin(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val importDir = File(getApplication<Application>().cacheDir, "plugin-imports").apply { mkdirs() }
                    val temp = File(importDir, "import-${System.currentTimeMillis()}.bhplugin")
                    resolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取插件文件")
                    externalPluginManager.installPackage(temp).getOrThrow().also {
                        officialPluginMarket.syncInstalledState()
                    }
                }
            }
            _uiMessage.value = result.fold(
                onSuccess = { "外部插件已加载：${it.name}" },
                onFailure = { "外部插件加载失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    fun openExternalPluginEntrypoint(pluginId: String, entrypointId: String) {
        val entry = externalPluginEntrypoints.value.firstOrNull {
            it.pluginId == pluginId && it.entrypointId == entrypointId
        }
        if (entry == null) {
            _uiMessage.value = "插件入口不可用，请确认插件已启用"
            return
        }
        _activeExternalPluginPanel.value = ActiveExternalPluginPanel(pluginId, entrypointId)
    }

    fun closeExternalPlugin() {
        _activeExternalPluginPanel.value = null
    }

    fun externalPluginPanel(pluginId: String, entrypointId: String): ExternalPluginPanelHandle? =
        externalPluginManager.panel(pluginId, entrypointId)

    fun setExternalPluginEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { externalPluginManager.setEnabled(pluginId, enabled) }
            officialPluginMarket.syncInstalledState()
            if (!enabled && _activeExternalPluginPanel.value?.pluginId == pluginId) {
                _activeExternalPluginPanel.value = null
            }
            _uiMessage.value = result.fold(
                onSuccess = { if (enabled) "插件已启用：${it.name}" else "插件已禁用：${it.name}" },
                onFailure = { "插件启停失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    fun uninstallExternalPlugin(pluginId: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { externalPluginManager.uninstall(pluginId) }
            officialPluginMarket.syncInstalledState()
            if (_activeExternalPluginPanel.value?.pluginId == pluginId) _activeExternalPluginPanel.value = null
            _uiMessage.value = if (removed) "外部插件已移除" else "外部插件已卸载"
        }
    }

    private companion object {
        const val MAX_UI_LOG = 500
    }
}