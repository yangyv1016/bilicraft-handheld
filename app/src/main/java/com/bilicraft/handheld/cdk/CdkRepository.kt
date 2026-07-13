package com.bilicraft.handheld.cdk

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
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
data class CdkIndex(
    val schemaVersion: Int = 1,
    val updatedAt: String? = null,
    val entries: List<CdkRemoteEntry> = emptyList()
)

@Serializable
data class CdkRemoteEntry(
    val id: String,
    val title: String = "CDK",
    val code: String,
    val description: String = "",
    val startsAt: String? = null,
    val endsAt: String? = null
)

data class CdkEntry(
    val id: String,
    val title: String,
    val code: String,
    val description: String,
    val startsAt: String?,
    val endsAt: String?
)

data class CdkState(
    val loading: Boolean = false,
    val sourceUrl: String = CdkRepository.DEFAULT_INDEX_URL,
    val updatedAt: String? = null,
    val entries: List<CdkEntry> = emptyList(),
    val errorMessage: String? = null
)

class CdkRepository(
    context: Context,
    private val indexUrl: String = DEFAULT_INDEX_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "cdk").apply { mkdirs() }
    private val indexCacheFile = File(cacheDir, "index.json")
    private var currentIndex: CdkIndex? = null

    private val _state = MutableStateFlow(CdkState(sourceUrl = indexUrl))
    val state: StateFlow<CdkState> = _state.asStateFlow()

    fun loadCache() {
        val cached = runCatching {
            if (!indexCacheFile.exists()) null else json.decodeFromString<CdkIndex>(indexCacheFile.readText())
        }.getOrNull()
        if (cached != null) {
            currentIndex = cached
            publish(cached, loading = false, errorMessage = null)
        }
    }

    suspend fun refresh(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            val body = client.newCall(Request.Builder().url(indexUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string() ?: throw IOException("empty body")
            }
            val index = json.decodeFromString<CdkIndex>(body)
            indexCacheFile.writeText(json.encodeToString(CdkIndex.serializer(), index))
            currentIndex = index
            publish(index, loading = false, errorMessage = null)
            _state.value.entries.size
        }.onFailure { error ->
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = "CDK 配置不可用：${error.message ?: "网络请求失败"}"
            )
        }
    }

    fun refreshActiveWindow() {
        currentIndex?.let { publish(it, loading = _state.value.loading, errorMessage = _state.value.errorMessage) }
    }

    private fun publish(index: CdkIndex, loading: Boolean, errorMessage: String?) {
        val nowMillis = System.currentTimeMillis()
        _state.value = CdkState(
            loading = loading,
            sourceUrl = indexUrl,
            updatedAt = index.updatedAt,
            entries = index.entries
                .mapNotNull { it.toActiveEntryOrNull(nowMillis) }
                .sortedBy { it.startsAt.orEmpty() },
            errorMessage = errorMessage
        )
    }

    private fun CdkRemoteEntry.toActiveEntryOrNull(nowMillis: Long): CdkEntry? {
        if (id.isBlank() || code.isBlank()) return null
        val startMillis = startsAt.parseIsoMillisOrNull()
        val endMillis = endsAt.parseIsoMillisOrNull()
        if (!startsAt.isNullOrBlank() && startMillis == null) return null
        if (!endsAt.isNullOrBlank() && endMillis == null) return null
        if (startMillis != null && nowMillis < startMillis) return null
        if (endMillis != null && nowMillis > endMillis) return null
        return CdkEntry(
            id = id,
            title = title.ifBlank { "CDK" },
            code = code,
            description = description,
            startsAt = startsAt,
            endsAt = endsAt
        )
    }

    private fun String?.parseIsoMillisOrNull(): Long? = this
        ?.takeIf { it.isNotBlank() }
        ?.let { value -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() }

    companion object {
        const val DEFAULT_INDEX_URL = "https://bccdn.yanguiofficial.cn/cdk/index.json"
    }
}