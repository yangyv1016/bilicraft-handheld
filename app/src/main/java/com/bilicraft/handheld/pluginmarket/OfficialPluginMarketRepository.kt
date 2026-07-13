package com.bilicraft.handheld.pluginmarket

import android.content.Context
import com.bilicraft.handheld.BuildConfig
import com.bilicraft.handheld.externalplugin.ExternalPluginEntry
import com.bilicraft.handheld.externalplugin.ExternalPluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class OfficialPluginMarketIndex(
    val schemaVersion: Int = 1,
    val updatedAt: String? = null,
    val plugins: List<OfficialMarketPlugin> = emptyList()
)

@Serializable
data class OfficialMarketPlugin(
    val id: String,
    val name: String,
    val summary: String = "",
    val description: String = "",
    val repo: String = "",
    val author: String = "",
    val category: String = "tools",
    val latestVersion: String,
    val minAppVersion: String = "",
    val minApiVersion: Int = 1,
    val permissions: List<String> = emptyList(),
    val releases: List<OfficialMarketRelease> = emptyList()
)

@Serializable
data class OfficialMarketRelease(
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val size: Long? = null,
    val changelog: String = ""
)

data class OfficialPluginMarketEntry(
    val id: String,
    val name: String,
    val summary: String,
    val description: String,
    val author: String,
    val category: String,
    val latestVersion: String,
    val installedVersion: String?,
    val compatible: Boolean,
    val permissions: List<String>,
    val changelog: String,
    val downloadSize: Long?,
    val repo: String
) {
    val installed: Boolean get() = installedVersion != null
    val updateAvailable: Boolean get() = installedVersion != null && installedVersion != latestVersion
}

data class OfficialPluginMarketState(
    val loading: Boolean = false,
    val sourceUrl: String = OfficialPluginMarketRepository.DEFAULT_INDEX_URL,
    val updatedAt: String? = null,
    val entries: List<OfficialPluginMarketEntry> = emptyList(),
    val errorMessage: String? = null
)

class OfficialPluginMarketRepository(
    context: Context,
    private val externalPluginManager: ExternalPluginManager,
    private val indexUrl: String = DEFAULT_INDEX_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "bhplugins/market").apply { mkdirs() }
    private val indexCacheFile = File(cacheDir, "official-index.json")
    private val downloadDir = File(cacheDir, "downloads").apply { mkdirs() }
    private var currentIndex: OfficialPluginMarketIndex? = null

    private val _state = MutableStateFlow(OfficialPluginMarketState(sourceUrl = indexUrl))
    val state: StateFlow<OfficialPluginMarketState> = _state.asStateFlow()

    fun loadCache() {
        val cached = runCatching {
            if (!indexCacheFile.exists()) null else json.decodeFromString<OfficialPluginMarketIndex>(indexCacheFile.readText())
        }.getOrNull()
        if (cached != null) {
            currentIndex = cached
            publish(cached, loading = false, errorMessage = null)
        }
    }

    suspend fun refresh(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            val request = Request.Builder().url(indexUrl).build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string() ?: throw IOException("empty body")
            }
            val index = json.decodeFromString<OfficialPluginMarketIndex>(body)
            indexCacheFile.writeText(json.encodeToString(OfficialPluginMarketIndex.serializer(), index))
            currentIndex = index
            publish(index, loading = false, errorMessage = null)
            index.plugins.size
        }.onFailure { error ->
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = "官方插件源不可用：${error.message ?: "网络请求失败"}"
            )
        }
    }

    fun syncInstalledState() {
        currentIndex?.let { publish(it, loading = _state.value.loading, errorMessage = _state.value.errorMessage) }
    }

    suspend fun install(pluginId: String): Result<ExternalPluginEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val index = currentIndex ?: error("官方源尚未加载")
            val plugin = index.plugins.firstOrNull { it.id == pluginId } ?: error("官方源中不存在插件：$pluginId")
            require(plugin.isCompatible()) { "插件要求 App ${plugin.minAppVersion.ifBlank { "unknown" }} / API ${plugin.minApiVersion}" }
            val release = plugin.releases.firstOrNull { it.version == plugin.latestVersion }
                ?: plugin.releases.firstOrNull()
                ?: error("插件没有可安装版本")
            val packageFile = download(plugin, release)
            verifySha256(packageFile, release.sha256)
            val manifest = externalPluginManager.inspectPackage(packageFile).getOrThrow()
            require(manifest.id == plugin.id) { "插件包 id(${manifest.id}) 与官方源 id(${plugin.id}) 不一致" }
            require(manifest.version == release.version) { "插件包版本(${manifest.version}) 与官方源版本(${release.version}) 不一致" }
            require(manifest.apiVersion == plugin.minApiVersion) {
                "插件包 API(${manifest.apiVersion}) 与官方源 API(${plugin.minApiVersion}) 不一致"
            }
            val undeclared = manifest.permissions.toSet() - plugin.permissions.toSet()
            require(undeclared.isEmpty()) { "插件包声明了官方源未登记的权限：${undeclared.joinToString()}" }
            externalPluginManager.installPackage(packageFile).getOrThrow().also {
                syncInstalledState()
            }
        }.onFailure { error ->
            _state.value = _state.value.copy(errorMessage = error.message ?: "插件安装失败")
        }
    }

    private fun publish(index: OfficialPluginMarketIndex, loading: Boolean, errorMessage: String?) {
        val installed = externalPluginManager.entries.value.associateBy { it.id }
        _state.value = OfficialPluginMarketState(
            loading = loading,
            sourceUrl = indexUrl,
            updatedAt = index.updatedAt,
            entries = index.plugins.map { plugin -> plugin.toEntry(installed[plugin.id]) }.sortedBy { it.name.lowercase() },
            errorMessage = errorMessage
        )
    }

    private fun OfficialMarketPlugin.toEntry(installed: ExternalPluginEntry?): OfficialPluginMarketEntry {
        val release = releases.firstOrNull { it.version == latestVersion } ?: releases.firstOrNull()
        return OfficialPluginMarketEntry(
            id = id,
            name = name,
            summary = summary,
            description = description,
            author = author,
            category = category,
            latestVersion = latestVersion,
            installedVersion = installed?.version,
            compatible = isCompatible(),
            permissions = permissions,
            changelog = release?.changelog.orEmpty(),
            downloadSize = release?.size,
            repo = repo
        )
    }

    private fun OfficialMarketPlugin.isCompatible(): Boolean {
        if (minApiVersion > com.bilicraft.handheld.pluginapi.BH_PLUGIN_API_VERSION) return false
        return minAppVersion.isBlank() || compareVersions(BuildConfig.VERSION_NAME, minAppVersion) >= 0
    }

    private fun download(plugin: OfficialMarketPlugin, release: OfficialMarketRelease): File {
        val target = File(downloadDir, "${plugin.id}-${release.version}.bhplugin")
        if (target.exists()) target.delete()
        val request = Request.Builder().url(release.downloadUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            target.outputStream().use { output -> body.byteStream().copyTo(output) }
        }
        return target
    }

    private fun verifySha256(file: File, expected: String) {
        val actual = file.sha256()
        require(actual.equals(expected, ignoreCase = true)) {
            "SHA-256 不匹配：expected=$expected actual=$actual"
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun compareVersions(left: String, right: String): Int {
        val l = left.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val r = right.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val size = maxOf(l.size, r.size)
        repeat(size) { index ->
            val diff = (l.getOrNull(index) ?: 0) - (r.getOrNull(index) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }

    companion object {
        const val DEFAULT_INDEX_URL = "https://bccdn.yanguiofficial.cn/plugin-market/index.json"
    }
}
