package com.bilicraft.handheld.config

import android.content.Context
import com.bilicraft.handheld.update.DownloadSource
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.ProtocolTable
import com.bilicraft.handheld.version.VersionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val versionId: String,
    val protocolNumber: Int? = null,
    val signingRequired: Boolean = false
) {
    fun toMcVersion(): McVersion = McVersion(
        id = versionId,
        type = VersionType.RELEASE,
        protocolNumber = ProtocolTable.byId[versionId] ?: protocolNumber
    )
}

@Serializable
data class QuickToolLink(
    val id: String,
    val title: String,
    val url: String,
    val description: String = ""
)

@Serializable
data class QuickCommandConfig(
    val id: String,
    val serverId: String,
    val name: String,
    val command: String
) {
    companion object {
        /**
         * 快捷内容既可以是斜杠命令，也可以是普通聊天文本。
         * 旧配置中的命令本来就带有斜杠，因此这里只清理首尾空白即可保持兼容。
         */
        fun normalizeCommand(input: String): String = input.trim()
    }
}

@Serializable
data class PluginServerBinding(
    val serverId: String,
    val pluginId: String
)

internal fun replacePluginServerBindings(
    current: List<PluginServerBinding>,
    pluginId: String,
    serverIds: Set<String>
): List<PluginServerBinding> =
    current.filterNot { it.pluginId == pluginId } +
        serverIds.sorted().map { serverId -> PluginServerBinding(serverId, pluginId) }

@Serializable
enum class ThemeMode(val displayName: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色")
}

@Serializable
data class UiPreferences(
    val chatAutoScroll: Boolean = true,
    val commandCompletionEnabled: Boolean = true,
    val downloadSource: DownloadSource = DownloadSource.DEFAULT,
    val themeMode: ThemeMode = ThemeMode.System
)

/**
 * UI-facing 本地配置仓库。
 *
 * 依赖现有逻辑：连接仍由 SessionController / ConnectionService 执行，仓库只保存 UI 可选项。
 * 纯 UI 补足：服务器配置与快捷工具目前没有既有 Repository，因此这里用 app 私有 JSON 文件承载。
 * 边界约束：不读写 SecureStore，不实例化 Room，不改变微软登录、协议、插件或 Token 状态机。
 */
class UiConfigRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serverFile = File(context.filesDir, "ui_servers.json")
    private val toolsFile = File(context.filesDir, "ui_tools.json")
    private val commandsFile = File(context.filesDir, "ui_commands.json")
    private val pluginServersFile = File(context.filesDir, "ui_plugin_servers.json")
    private val preferencesFile = File(context.filesDir, "ui_preferences.json")
    private val officialSigningMigrationFile = File(context.filesDir, "ui_official_server_signing_v1.migrated")
    private val railwayToolMigrationFile = File(context.filesDir, "ui_railway_tool_v1.migrated")

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _tools = MutableStateFlow<List<QuickToolLink>>(emptyList())
    val tools: StateFlow<List<QuickToolLink>> = _tools.asStateFlow()

    private val _quickCommands = MutableStateFlow<List<QuickCommandConfig>>(emptyList())
    val quickCommands: StateFlow<List<QuickCommandConfig>> = _quickCommands.asStateFlow()

    private val _pluginServerBindings = MutableStateFlow<List<PluginServerBinding>>(emptyList())
    val pluginServerBindings: StateFlow<List<PluginServerBinding>> = _pluginServerBindings.asStateFlow()

    private val _preferences = MutableStateFlow(UiPreferences())
    val preferences: StateFlow<UiPreferences> = _preferences.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _servers.value = migrateOfficialServerSigning(
            loadList(serverFile) ?: defaultServers().also { saveList(serverFile, it) }
        ).let { servers ->
            servers.map(::normalizeServerProtocol).also { normalized ->
                if (normalized != servers) saveList(serverFile, normalized)
            }
        }
        val loadedTools = loadList<QuickToolLink>(toolsFile) ?: defaultTools().also { saveList(toolsFile, it) }
        _tools.value = migrateRailwayTool(loadedTools)
        _quickCommands.value = loadList<QuickCommandConfig>(commandsFile)
            ?: defaultQuickCommands().also { saveList(commandsFile, it) }
        _pluginServerBindings.value = loadList<PluginServerBinding>(pluginServersFile)
            ?: emptyList<PluginServerBinding>().also { saveList(pluginServersFile, it) }
        _preferences.value = loadValue(preferencesFile) ?: UiPreferences().also { saveValue(preferencesFile, it) }
    }

    suspend fun setChatAutoScroll(enabled: Boolean) = withContext(Dispatchers.IO) {
        val next = _preferences.value.copy(chatAutoScroll = enabled)
        _preferences.value = next
        saveValue(preferencesFile, next)
    }

    suspend fun setCommandCompletionEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val next = _preferences.value.copy(commandCompletionEnabled = enabled)
        _preferences.value = next
        saveValue(preferencesFile, next)
    }

    suspend fun setDownloadSource(source: DownloadSource) = withContext(Dispatchers.IO) {
        val next = _preferences.value.copy(downloadSource = source)
        _preferences.value = next
        saveValue(preferencesFile, next)
    }

    suspend fun setThemeMode(themeMode: ThemeMode) = withContext(Dispatchers.IO) {
        val next = _preferences.value.copy(themeMode = themeMode)
        _preferences.value = next
        saveValue(preferencesFile, next)
    }

    suspend fun upsertServer(config: ServerConfig) = withContext(Dispatchers.IO) {
        val normalizedConfig = normalizeServerProtocol(config)
        val current = _servers.value
        val next = if (current.any { it.id == normalizedConfig.id }) {
            current.map { if (it.id == normalizedConfig.id) normalizedConfig else it }
        } else {
            current + normalizedConfig
        }
        _servers.value = next
        saveList(serverFile, next)
    }

    suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        val next = _servers.value.filterNot { it.id == id }
        _servers.value = next
        saveList(serverFile, next)

        val nextCommands = _quickCommands.value.filterNot { it.serverId == id }
        if (nextCommands.size != _quickCommands.value.size) {
            _quickCommands.value = nextCommands
            saveList(commandsFile, nextCommands)
        }

        val nextBindings = _pluginServerBindings.value.filterNot { it.serverId == id }
        if (nextBindings.size != _pluginServerBindings.value.size) {
            _pluginServerBindings.value = nextBindings
            saveList(pluginServersFile, nextBindings)
        }
    }

    suspend fun setPluginServers(pluginId: String, serverIds: Set<String>) = withContext(Dispatchers.IO) {
        val validServerIds = _servers.value.mapTo(mutableSetOf()) { it.id }
        val next = replacePluginServerBindings(
            current = _pluginServerBindings.value,
            pluginId = pluginId,
            serverIds = serverIds.filterTo(mutableSetOf()) { it in validServerIds }
        )
        _pluginServerBindings.value = next
        saveList(pluginServersFile, next)
    }

    suspend fun clearPluginServerBindings(pluginId: String) = withContext(Dispatchers.IO) {
        val next = _pluginServerBindings.value.filterNot { it.pluginId == pluginId }
        if (next.size != _pluginServerBindings.value.size) {
            _pluginServerBindings.value = next
            saveList(pluginServersFile, next)
        }
    }

    suspend fun upsertQuickCommand(config: QuickCommandConfig) = withContext(Dispatchers.IO) {
        val normalized = config.copy(command = QuickCommandConfig.normalizeCommand(config.command))
        val current = _quickCommands.value
        val next = if (current.any { it.id == normalized.id }) {
            current.map { if (it.id == normalized.id) normalized else it }
        } else {
            current + normalized
        }
        _quickCommands.value = next
        saveList(commandsFile, next)
    }

    suspend fun deleteQuickCommand(id: String) = withContext(Dispatchers.IO) {
        val next = _quickCommands.value.filterNot { it.id == id }
        _quickCommands.value = next
        saveList(commandsFile, next)
    }

    suspend fun upsertTool(link: QuickToolLink) = withContext(Dispatchers.IO) {
        val current = _tools.value
        val next = if (current.any { it.id == link.id }) {
            current.map { if (it.id == link.id) link else it }
        } else {
            current + link
        }
        _tools.value = next
        saveList(toolsFile, next)
    }

    suspend fun deleteTool(id: String) = withContext(Dispatchers.IO) {
        val next = _tools.value.filterNot { it.id == id }
        _tools.value = next
        saveList(toolsFile, next)
    }

    suspend fun moveTool(fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        val current = _tools.value.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return@withContext
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        _tools.value = current
        saveList(toolsFile, current)
    }

    private fun normalizeServerProtocol(config: ServerConfig): ServerConfig = config.copy(
        protocolNumber = ProtocolTable.byId[config.versionId] ?: config.protocolNumber
    )

    fun newServer(
        name: String,
        host: String,
        port: Int,
        version: McVersion,
        signingRequired: Boolean
    ): ServerConfig = ServerConfig(
        id = UUID.randomUUID().toString(),
        name = name,
        host = host,
        port = port,
        versionId = version.id,
        protocolNumber = version.protocolNumber,
        signingRequired = signingRequired
    )

    fun newTool(title: String, url: String, description: String): QuickToolLink = QuickToolLink(
        id = UUID.randomUUID().toString(),
        title = title,
        url = url,
        description = description
    )

    fun newQuickCommand(serverId: String, name: String, command: String): QuickCommandConfig =
        QuickCommandConfig(
            id = UUID.randomUUID().toString(),
            serverId = serverId,
            name = name,
            command = QuickCommandConfig.normalizeCommand(command)
        )

    private inline fun <reified T> loadList(file: File): List<T>? =
        runCatching {
            if (!file.exists()) null else json.decodeFromString<List<T>>(file.readText())
        }.getOrNull()

    private inline fun <reified T> loadValue(file: File): T? =
        runCatching {
            if (!file.exists()) null else json.decodeFromString<T>(file.readText())
        }.getOrNull()

    private inline fun <reified T> saveList(file: File, value: List<T>) {
        file.writeText(json.encodeToString(value))
    }

    private inline fun <reified T> saveValue(file: File, value: T) {
        file.writeText(json.encodeToString(value))
    }

    /**
     * UI 配置迁移：旧版本默认主服曾写成未强制签名，这里只迁移一次。
     * 用户后续如果手动关闭强制签名，不会被下一次启动重新覆盖。
     */
    private fun migrateOfficialServerSigning(servers: List<ServerConfig>): List<ServerConfig> {
        if (officialSigningMigrationFile.exists()) return servers
        val next = servers.map { server ->
            if (server.id == OFFICIAL_SERVER_ID && !server.signingRequired) {
                server.copy(signingRequired = true)
            } else {
                server
            }
        }
        saveList(serverFile, next)
        officialSigningMigrationFile.writeText("done")
        return next
    }

    /**
     * 一次性补入铁路实时线路图。迁移标记写入后尊重用户删除，不会在后续启动强制恢复。
     * 已通过 id 或 URL 存在时只写迁移标记，避免用户手动添加过同一网站后出现重复项。
     */
    private fun migrateRailwayTool(tools: List<QuickToolLink>): List<QuickToolLink> {
        if (railwayToolMigrationFile.exists()) return tools
        val railway = railwayTool()
        val alreadyExists = tools.any { it.id == railway.id || it.url.equals(railway.url, ignoreCase = true) }
        val next = if (alreadyExists) {
            tools
        } else {
            val insertAt = tools.indexOfFirst { it.id == "bilicraft-map" }
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: tools.size
            tools.take(insertAt) + railway + tools.drop(insertAt)
        }
        saveList(toolsFile, next)
        railwayToolMigrationFile.writeText("done")
        return next
    }

    private fun railwayTool() = QuickToolLink(
        id = RAILWAY_TOOL_ID,
        title = "铁路实时线路图",
        url = RAILWAY_TOOL_URL,
        description = "帕拉伦铁路实时线路与列车位置"
    )

    private fun defaultServers(): List<ServerConfig> = listOf(
        ServerConfig(
            id = OFFICIAL_SERVER_ID,
            name = "碧玺官方主服",
            host = "mc.bilicraft.com",
            port = 25577,
            versionId = "1.21.11",
            protocolNumber = ProtocolTable.byId["1.21.11"],
            signingRequired = true
        )
    )

    private fun defaultTools(): List<QuickToolLink> = listOf(
        QuickToolLink(
            id = "bilicraft-map",
            title = "卫星地图",
            url = "https://map.bilicraft.com/s2/hyperion/#",
            description = "碧玺服务器在线地图"
        ),
        railwayTool(),
        QuickToolLink(
            id = "bilicraft-wiki",
            title = "Wiki",
            url = "https://www.yuque.com/sasanarx/bilicraft",
            description = "服务器规则与玩法资料"
        ),
        QuickToolLink(
            id = "bilicraft-bbs",
            title = "BBS",
            url = "https://bbs.bilicraft.com/",
            description = "社区论坛"
        )
    )

    private fun defaultQuickCommands(): List<QuickCommandConfig> = listOf(
        QuickCommandConfig(
            id = "bilicraft-signin",
            serverId = OFFICIAL_SERVER_ID,
            name = "一键签到",
            command = "/signin click"
        )
    )

    private companion object {
        const val OFFICIAL_SERVER_ID = "bilicraft-official-main"
        const val RAILWAY_TOOL_ID = "paralun-railway-map"
        const val RAILWAY_TOOL_URL = "https://railwaymap.big-brother.top/"
    }
}
