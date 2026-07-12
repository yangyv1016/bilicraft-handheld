package com.bilicraft.handheld.externalplugin

import android.content.Context
import com.bilicraft.handheld.pluginapi.BH_PLUGIN_API_VERSION
import com.bilicraft.handheld.pluginapi.BhPlugin
import com.bilicraft.handheld.pluginapi.BhPluginEntrypoint
import com.bilicraft.handheld.pluginapi.BhPluginHost
import com.bilicraft.handheld.pluginapi.BhPluginPanel
import com.bilicraft.handheld.session.SessionController
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

class ExternalPluginManager(
    private val appContext: Context,
    private val session: SessionController
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val installedDir = File(appContext.filesDir, "bhplugins/installed")
    private val dataRoot = File(appContext.filesDir, "bhplugins/data")
    private val optimizedRoot = File(appContext.codeCacheDir, "bhplugins/optimized")
    val pluginDropDir: File = appContext.getExternalFilesDir("plugins") ?: File(appContext.filesDir, "bhplugins/inbox")

    private val loaded = linkedMapOf<String, LoadedPluginRuntime>()
    private val disabled = linkedMapOf<String, ExternalPluginEntry>()
    private val failures = linkedMapOf<String, ExternalPluginEntry>()
    private val _entries = MutableStateFlow<List<ExternalPluginEntry>>(emptyList())
    val entries: StateFlow<List<ExternalPluginEntry>> = _entries.asStateFlow()
    private val _entrypoints = MutableStateFlow<List<ExternalPluginEntrypoint>>(emptyList())
    val entrypoints: StateFlow<List<ExternalPluginEntrypoint>> = _entrypoints.asStateFlow()

    init {
        listOf(installedDir, dataRoot, optimizedRoot, pluginDropDir).forEach { it.mkdirs() }
    }

    @Synchronized
    fun refresh(): Int {
        val imported = importDroppedPackagesLocked()
        loadInstalledPackagesLocked()
        publishEntriesLocked()
        return imported
    }

    @Synchronized
    fun inspectPackage(source: File): Result<ExternalPluginManifest> = runCatching {
        readManifest(source).also(::validateManifest)
    }

    @Synchronized
    fun installPackage(source: File): Result<ExternalPluginEntry> = runCatching {
        val manifest = readManifest(source)
        validateManifest(manifest)
        unloadLocked(manifest.id)
        installedPluginPackages().forEach { existing ->
            val existingManifest = runCatching { readManifest(existing) }.getOrNull()
            if (existingManifest?.id == manifest.id) {
                existing.setWritable(true)
                existing.delete()
            }
        }
        val packageFile = installedPackageFile(manifest)
        if (packageFile.exists()) {
            packageFile.setWritable(true)
            packageFile.delete()
        }
        source.copyTo(packageFile, overwrite = true)
        packageFile.setReadOnly()
        setEnabledLocked(manifest.id, true)
        disabled.remove(manifest.id)
        failures.remove(manifest.id)
        val runtime = loadPackageLocked(packageFile, manifest)
        publishEntriesLocked()
        runtime.toEntry()
    }

    @Synchronized
    fun uninstall(pluginId: String): Boolean {
        unloadLocked(pluginId)
        var removed = false
        installedPluginPackages().forEach { file ->
            val manifest = runCatching { readManifest(file) }.getOrNull()
            if (manifest?.id == pluginId || file.nameWithoutExtension == pluginId) {
                file.setWritable(true)
                removed = file.delete() || removed
            }
        }
        disabled.remove(pluginId)
        failures.remove(pluginId)
        publishEntriesLocked()
        return removed
    }

    @Synchronized
    fun setEnabled(pluginId: String, enabled: Boolean): Result<ExternalPluginEntry> = runCatching {
        val packageFile = findInstalledPackageLocked(pluginId) ?: error("插件未安装：$pluginId")
        val manifest = readManifest(packageFile)
        validateManifest(manifest)
        setEnabledLocked(pluginId, enabled)
        if (!enabled) {
            unloadLocked(pluginId)
            failures.remove(pluginId)
            val entry = manifest.toEntry(packageFile.name, "已禁用", enabled = false)
            disabled[pluginId] = entry
            publishEntriesLocked()
            return@runCatching entry
        }
        disabled.remove(pluginId)
        failures.remove(pluginId)
        val runtime = loaded[pluginId] ?: loadPackageLocked(packageFile, manifest)
        publishEntriesLocked()
        runtime.toEntry()
    }

    @Synchronized
    fun panel(pluginId: String, entrypointId: String = DEFAULT_ENTRYPOINT_ID): ExternalPluginPanelHandle? {
        val runtime = loaded[pluginId] ?: return null
        val registered = runtime.entrypoints().firstOrNull { it.entrypointId == entrypointId }
        val panel = runCatching { runtime.plugin.createPanel(runtime.host, entrypointId) }.getOrNull() ?: return null
        return ExternalPluginPanelHandle(
            pluginId = pluginId,
            entrypointId = entrypointId,
            title = registered?.title ?: runtime.descriptor.name,
            panel = panel,
            host = runtime.host
        )
    }

    @Synchronized
    fun loadedNames(): List<String> = loaded.values.map { it.descriptor.name }

    private fun importDroppedPackagesLocked(): Int {
        var imported = 0
        val candidates = pluginDropDir.listFiles { file ->
            file.isFile && file.extension.equals("bhplugin", ignoreCase = true)
        }.orEmpty()
        candidates.forEach { file ->
            installPackage(file).onSuccess { imported++ }
        }
        return imported
    }

    private fun loadInstalledPackagesLocked() {
        val packages = installedPluginPackages()
        val installedIds = mutableSetOf<String>()
        packages.forEach { file ->
            val manifest = runCatching { readManifest(file) }.getOrElse { error ->
                failures[file.nameWithoutExtension] = ExternalPluginEntry(
                    id = file.nameWithoutExtension,
                    name = file.name,
                    description = "插件包 manifest 无法读取",
                    version = "unknown",
                    loaded = false,
                    packageName = file.name,
                    statusMessage = error.message
                )
                return@forEach
            }
            installedIds += manifest.id
            if (!isEnabledLocked(manifest.id)) {
                unloadLocked(manifest.id)
                failures.remove(manifest.id)
                disabled[manifest.id] = manifest.toEntry(file.name, "已禁用", enabled = false)
                return@forEach
            }
            disabled.remove(manifest.id)
            if (loaded.containsKey(manifest.id)) return@forEach
            runCatching {
                validateManifest(manifest)
                loadPackageLocked(file, manifest)
            }.onFailure { error ->
                failures[manifest.id] = manifest.toEntry(file.name, error.message)
            }
        }
        loaded.keys.filterNot { it in installedIds }.forEach(::unloadLocked)
        disabled.keys.filterNot { it in installedIds }.forEach { disabled.remove(it) }
        failures.keys.filterNot { it in installedIds }.forEach { failures.remove(it) }
    }

    private fun loadPackageLocked(packageFile: File, manifest: ExternalPluginManifest): LoadedPluginRuntime {
        packageFile.setReadOnly()
        val optimizedDir = File(optimizedRoot, manifest.id.toSafeFileSegment()).apply { mkdirs() }
        val loader = DexClassLoader(
            packageFile.absolutePath,
            optimizedDir.absolutePath,
            null,
            appContext.classLoader
        )
        val clazz = loader.loadClass(manifest.entryClass)
        val plugin = instantiate(clazz) as? BhPlugin
            ?: error("${manifest.entryClass} 没有实现 BhPlugin")
        val descriptor = plugin.descriptor
        require(descriptor.id == manifest.id) {
            "descriptor.id(${descriptor.id}) 与 plugin.json.id(${manifest.id}) 不一致"
        }
        require(descriptor.minApiVersion <= BH_PLUGIN_API_VERSION) {
            "插件要求 API ${descriptor.minApiVersion}，本体只支持 $BH_PLUGIN_API_VERSION"
        }
        val host = AppBhPluginHost(
            appContext = appContext,
            session = session,
            pluginDataDir = File(dataRoot, manifest.id.toSafeFileSegment()),
            pluginId = manifest.id
        )
        runCatching { plugin.onLoad(host) }.getOrElse { error ->
            host.close()
            throw error
        }
        return LoadedPluginRuntime(manifest, descriptor, packageFile, plugin, host).also {
            loaded[manifest.id] = it
            failures.remove(manifest.id)
        }
    }

    private fun unloadLocked(pluginId: String) {
        val runtime = loaded.remove(pluginId) ?: return
        runCatching { runtime.plugin.onUnload(runtime.host) }
        runtime.host.close()
    }

    private fun publishEntriesLocked() {
        val loadedEntries = loaded.values.map { it.toEntry() }
        val disabledEntries = disabled.values.filterNot { loaded.containsKey(it.id) }
        val failedEntries = failures.values.filterNot { loaded.containsKey(it.id) || disabled.containsKey(it.id) }
        _entries.value = (loadedEntries + disabledEntries + failedEntries).sortedBy { it.name.lowercase() }
        _entrypoints.value = loaded.values
            .flatMap { it.entrypoints() }
            .sortedWith(compareBy<ExternalPluginEntrypoint> { it.order }.thenBy { it.pluginName.lowercase() }.thenBy { it.title.lowercase() })
    }

    private fun findInstalledPackageLocked(pluginId: String): File? = installedPluginPackages().firstOrNull { file ->
        val manifest = runCatching { readManifest(file) }.getOrNull()
        manifest?.id == pluginId || file.nameWithoutExtension == pluginId.toSafeFileSegment()
    }

    private fun isEnabledLocked(pluginId: String): Boolean = prefs.getBoolean(enabledPrefKey(pluginId), true)

    private fun setEnabledLocked(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean(enabledPrefKey(pluginId), enabled).apply()
    }

    private fun enabledPrefKey(pluginId: String): String = "enabled.${pluginId.toSafeFileSegment()}"

    private fun installedPluginPackages(): List<File> = installedDir
        .listFiles { file -> file.isFile && file.extension.equals("bhplugin", ignoreCase = true) }
        .orEmpty()
        .sortedBy { it.name.lowercase() }

    private fun installedPackageFile(manifest: ExternalPluginManifest): File = File(
        installedDir,
        "${manifest.id.toSafeFileSegment()}-${manifest.version.toSafeFileSegment()}.bhplugin"
    )

    private fun readManifest(packageFile: File): ExternalPluginManifest = ZipFile(packageFile).use { zip ->
        val entry = zip.getEntry("plugin.json") ?: error("缺少 plugin.json")
        zip.getInputStream(entry).bufferedReader().use { reader ->
            json.decodeFromString(reader.readText())
        }
    }

    private fun validateManifest(manifest: ExternalPluginManifest) {
        require(manifest.id.matches(PLUGIN_ID_PATTERN)) { "插件 id 只能包含字母、数字、点、下划线和横线" }
        require(manifest.name.isNotBlank()) { "插件名称不能为空" }
        require(manifest.version.isNotBlank()) { "插件版本不能为空" }
        require(manifest.entryClass.isNotBlank()) { "插件入口类不能为空" }
        require(manifest.apiVersion <= BH_PLUGIN_API_VERSION) {
            "插件包要求 API ${manifest.apiVersion}，本体只支持 $BH_PLUGIN_API_VERSION"
        }
    }

    private fun instantiate(clazz: Class<*>): Any {
        runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()?.let { return it }
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private data class LoadedPluginRuntime(
        val manifest: ExternalPluginManifest,
        val descriptor: com.bilicraft.handheld.pluginapi.BhPluginDescriptor,
        val packageFile: File,
        val plugin: BhPlugin,
        val host: AppBhPluginHost
    ) {
        fun toEntry(): ExternalPluginEntry = ExternalPluginEntry(
            id = descriptor.id,
            name = descriptor.name,
            description = descriptor.description,
            version = descriptor.version,
            loaded = true,
            enabled = true,
            packageName = packageFile.name,
            statusMessage = "已从外部插件包加载"
        )

        fun entrypoints(): List<ExternalPluginEntrypoint> {
            val declared = runCatching { plugin.entrypoints(host) }
                .getOrElse { error ->
                    host.log("入口注册失败：${error.message ?: error::class.java.simpleName}")
                    listOf(defaultEntrypoint())
                }
            return declared
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .ifEmpty { listOf(defaultEntrypoint()) }
                .distinctBy { it.id }
                .map { entry ->
                    ExternalPluginEntrypoint(
                        pluginId = descriptor.id,
                        entrypointId = entry.id,
                        title = entry.title,
                        description = entry.description,
                        pluginName = descriptor.name,
                        pluginVersion = descriptor.version,
                        order = entry.order
                    )
                }
        }

        private fun defaultEntrypoint(): BhPluginEntrypoint = BhPluginEntrypoint(
            id = DEFAULT_ENTRYPOINT_ID,
            title = descriptor.name,
            description = descriptor.description
        )
    }

    private fun ExternalPluginManifest.toEntry(packageName: String?, message: String?, enabled: Boolean = true): ExternalPluginEntry =
        ExternalPluginEntry(
            id = id,
            name = name,
            description = description,
            version = version,
            loaded = false,
            enabled = enabled,
            packageName = packageName,
            statusMessage = message
        )

    private fun String.toSafeFileSegment(): String = map { char ->
        if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
    }.joinToString("")

    companion object {
        private const val PREFS_NAME = "external_plugins"
        private const val DEFAULT_ENTRYPOINT_ID = "main"
        private val PLUGIN_ID_PATTERN = Regex("[A-Za-z0-9_.-]+")
    }
}

data class ExternalPluginPanelHandle(
    val pluginId: String,
    val entrypointId: String,
    val title: String,
    val panel: BhPluginPanel,
    val host: BhPluginHost
)