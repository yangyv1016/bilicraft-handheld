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
data class UiPreferences(
    val chatAutoScroll: Boolean = true,
    val downloadSource: DownloadSource = DownloadSource.DEFAULT
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
    private val preferencesFile = File(context.filesDir, "ui_preferences.json")
    private val officialSigningMigrationFile = File(context.filesDir, "ui_official_server_signing_v1.migrated")

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _tools = MutableStateFlow<List<QuickToolLink>>(emptyList())
    val tools: StateFlow<List<QuickToolLink>> = _tools.asStateFlow()

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
        _tools.value = loadList(toolsFile) ?: defaultTools().also { saveList(toolsFile, it) }
        _preferences.value = loadValue(preferencesFile) ?: UiPreferences().also { saveValue(preferencesFile, it) }
    }

    suspend fun setChatAutoScroll(enabled: Boolean) = withContext(Dispatchers.IO) {
        val next = _preferences.value.copy(chatAutoScroll = enabled)
        _preferences.value = next
        saveValue(preferencesFile, next)
    }

    suspend fun setDownloadSource(source: DownloadSource) = withContext(Dispatchers.IO) {
        val next = _preferences.value.copy(downloadSource = source)
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

    private companion object {
        const val OFFICIAL_SERVER_ID = "bilicraft-official-main"
    }
}