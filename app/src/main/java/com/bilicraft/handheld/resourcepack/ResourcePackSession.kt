package com.bilicraft.handheld.resourcepack

import com.bilicraft.handheld.protocol.MinecraftClient
import com.bilicraft.handheld.protocol.ResourcePackRequest
import com.bilicraft.handheld.protocol.ResourcePackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class PackStage { Consent, Downloading, Loaded, Declined, Failed }

data class PackEntry(
    val request: ResourcePackRequest,
    val stage: PackStage = PackStage.Consent,
    val downloaded: Long = 0,
    val total: Long? = null,
    val message: String? = null,
    val archive: ResourcePackArchive? = null
)

data class ResourcePackState(
    val serverId: String? = null,
    val entries: List<PackEntry> = emptyList(),
    val fonts: ResourcePackFonts? = null,
    val icons: ResourcePackItemIcons? = null
)

/** A separate lifetime for each connection prevents old downloads affecting a new server. */
class ResourcePackSession(
    private val repository: ResourcePackRepository,
    private val server: String,
    serverId: String?,
    private val client: MinecraftClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<ResourcePackRequest, Job>()
    private val _state = MutableStateFlow(ResourcePackState(serverId))
    val state = _state.asStateFlow()

    init {
        scope.launch {
            client.resourcePacks.collect { requests ->
                jobs.keys.filter { old -> requests.none { it === old } }.forEach { jobs.remove(it)?.cancel() }
                val previous = _state.value.entries
                val entries = requests.map { request ->
                    previous.find { it.request === request } ?: PackEntry(request)
                }
                val archives = entries.mapNotNull { it.archive }
                _state.value = _state.value.copy(entries = entries,
                    fonts = if (archives.isEmpty()) null else ResourcePackFonts(archives),
                    icons = ResourcePackItemIcons(listOfNotNull(repository.baseArchive) + archives, repository.headSkins))
                for (entry in _state.value.entries) {
                    if (entry.stage == PackStage.Consent && repository.hasApprovedCache(server, entry.request)) accept(entry.request)
                }
            }
        }
    }

    fun accept(request: ResourcePackRequest) {
        scope.launch {
            if (_state.value.entries.none { it.request === request && it.stage == PackStage.Consent }) return@launch
            if (request.url.toHttpUrlOrNull() == null) {
                client.respondResourcePack(request, ResourcePackStatus.InvalidUrl)
                update(request) { it.copy(stage = PackStage.Failed, message = "资源包地址必须是 HTTP 或 HTTPS") }
                return@launch
            }
            update(request) { it.copy(stage = PackStage.Downloading) }
            client.respondResourcePack(request, ResourcePackStatus.Accepted)
            jobs[request] = scope.launch {
                var downloaded = false
                try {
                    val file = repository.load(server, request, _state.value.entries.map { repository.cacheKey(server, it.request) }.toSet()) { received, total ->
                        update(request) { it.copy(downloaded = received, total = total) }
                    }
                    downloaded = true
                    client.respondResourcePack(request, ResourcePackStatus.Downloaded)
                    val archive = withContext(Dispatchers.IO) {
                        val archive = ResourcePackArchive(file)
                        // Prepare the ordinary chat font before reporting loaded. Other fonts load on demand.
                        val fonts = ResourcePackFonts(listOf(archive))
                        val providers = archive.fonts["minecraft:default"]?.optJSONArray("providers")
                        if (providers != null) {
                            for (index in 0 until providers.length()) {
                                val rows = providers.getJSONObject(index).optJSONArray("chars") ?: continue
                                val text = (0 until rows.length()).joinToString("") { rows.getString(it) }
                                fonts.resolve(listOf(com.bilicraft.handheld.protocol.ChatSpan(text)))
                            }
                        }
                        repository.markApproved(server, request)
                        archive
                    }
                    update(request) { it.copy(stage = PackStage.Loaded, archive = archive) }
                    val archives = _state.value.entries.mapNotNull { it.archive }
                    _state.value = _state.value.copy(fonts = ResourcePackFonts(archives), icons = ResourcePackItemIcons(listOfNotNull(repository.baseArchive) + archives, repository.headSkins))
                    client.respondResourcePack(request, ResourcePackStatus.Loaded)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    update(request) { it.copy(stage = PackStage.Failed, message = error.message ?: "资源包加载失败") }
                    client.respondResourcePack(request, if (downloaded) ResourcePackStatus.ReloadFailed else ResourcePackStatus.DownloadFailed)
                }
            }
        }
    }

    fun decline(request: ResourcePackRequest) {
        scope.launch {
            if (_state.value.entries.none { it.request === request && it.stage == PackStage.Consent }) return@launch
            update(request) { it.copy(stage = PackStage.Declined) }
            client.respondResourcePack(request, ResourcePackStatus.Declined)
        }
    }

    private fun update(request: ResourcePackRequest, transform: (PackEntry) -> PackEntry) {
        _state.update { state -> state.copy(entries = state.entries.map { if (it.request === request) transform(it) else it }) }
    }

    fun close() { scope.cancel() }
}
