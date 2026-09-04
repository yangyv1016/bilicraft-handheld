package com.bilicraft.handheld.announcement

import android.content.Context
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
import java.util.concurrent.TimeUnit

@Serializable
data class AnnouncementIndex(
    val schemaVersion: Int = 1,
    val updatedAt: String? = null,
    val announcements: List<AnnouncementEntry> = emptyList()
)

@Serializable
data class AnnouncementEntry(
    val id: String,
    val type: String = TYPE_MANUAL,
    val title: String,
    val content: String,
    val publishedAt: String,
    val versionName: String? = null
) {
    companion object {
        const val TYPE_RELEASE = "release"
        const val TYPE_MANUAL = "manual"
    }
}

data class AnnouncementState(
    val loading: Boolean = false,
    val entries: List<AnnouncementEntry> = emptyList(),
    val updatedAt: String? = null,
    val errorMessage: String? = null
)

internal fun normalizeAnnouncements(entries: List<AnnouncementEntry>): List<AnnouncementEntry> {
    val seenIds = mutableSetOf<String>()
    return entries.filter { entry ->
        entry.id.isNotBlank() &&
            entry.title.isNotBlank() &&
            entry.content.isNotBlank() &&
            entry.publishedAt.isNotBlank() &&
            seenIds.add(entry.id)
    }
}

class AnnouncementRepository(
    context: Context,
    private val indexUrls: List<String> = DEFAULT_INDEX_URLS,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    private val cacheDir = File(context.applicationContext.filesDir, "announcements").apply { mkdirs() }
    private val cacheFile = File(cacheDir, "index.json")
    private val _state = MutableStateFlow(AnnouncementState())
    val state: StateFlow<AnnouncementState> = _state.asStateFlow()

    fun loadCache() {
        val cached = runCatching {
            if (!cacheFile.exists()) null else json.decodeFromString<AnnouncementIndex>(cacheFile.readText())
        }.getOrNull() ?: return
        publish(cached, loading = false, errorMessage = null)
    }

    suspend fun refresh(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            val body = downloadIndex()
            val index = json.decodeFromString<AnnouncementIndex>(body)
            cacheFile.writeText(json.encodeToString(AnnouncementIndex.serializer(), index))
            publish(index, loading = false, errorMessage = null)
            _state.value.entries.size
        }.onFailure {
            val hasCachedAnnouncements = _state.value.entries.isNotEmpty()
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = if (hasCachedAnnouncements) {
                    "刷新失败，当前显示本地公告"
                } else {
                    "公告加载失败，请检查网络后重试"
                }
            )
        }
    }

    private fun downloadIndex(): String {
        var lastFailure: IOException? = null
        for (indexUrl in indexUrls.distinct()) {
            try {
                return client.newCall(Request.Builder().url(indexUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body?.string() ?: throw IOException("empty body")
                }
            } catch (failure: IOException) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: IOException("no announcement source configured")
    }

    private fun publish(index: AnnouncementIndex, loading: Boolean, errorMessage: String?) {
        _state.value = AnnouncementState(
            loading = loading,
            entries = normalizeAnnouncements(index.announcements),
            updatedAt = index.updatedAt,
            errorMessage = errorMessage
        )
    }

    companion object {
        const val DEFAULT_INDEX_URL = "https://bccdn.yanguiofficial.cn/announcements/index.json"
        const val PAGES_INDEX_URL = "https://bilicraft-cdn.pages.dev/announcements/index.json"
        val DEFAULT_INDEX_URLS = listOf(DEFAULT_INDEX_URL, PAGES_INDEX_URL)
    }
}
