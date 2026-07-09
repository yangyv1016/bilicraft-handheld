package com.bilicraft.handheld.version

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 版本仓库：向 UI 提供「分组后的完整版本列表」，向协议层提供「协议号解析」。
 *
 * 数据流：
 *   内置 ProtocolTable ──┐
 *                        ├─► 合并去重 ─► 分组 ─► 下拉框
 *   Mojang manifest ─────┘  (缓存到本地文件，离线可用)
 *
 * 「自动识别」永远置于列表最顶，作为默认项。
 */
class VersionRepository(context: Context) {

    private val cacheFile = File(context.cacheDir, "version_manifest.json")
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * 分组结果，直接对应下拉框的分组结构。
     * 顺序：自动识别（单独） → 最新版 → Release → Snapshot → Old。
     */
    data class Grouped(
        val autoDetect: McVersion,
        val latest: McVersion,
        val releases: List<McVersion>,
        val snapshots: List<McVersion>,
        val oldVersions: List<McVersion>
    )

    /** 首屏立即可用：只用内置表，不触网，保证「一键启动」不卡在网络上 */
    fun builtInGrouped(): Grouped = group(ProtocolTable.entries)

    /**
     * 在线刷新：拉 Mojang manifest，与内置表合并后缓存。
     * 失败则回退到缓存或内置表，绝不因网络问题让下拉框空掉。
     */
    suspend fun refreshFromMojang(): Grouped = withContext(Dispatchers.IO) {
        val fetched = runCatching { fetchManifest() }.getOrNull()
        if (fetched != null) {
            runCatching { cacheFile.writeText(json.encodeToString(fetched)) }
        }
        val remote = fetched ?: loadCache() ?: emptyList()
        group(mergeWithBuiltIn(remote))
    }

    /**
     * 协议号解析（协议层用）：
     * - 自动识别 → null（调用方走 ping 逻辑）
     * - 内置表命中 → 直接返回
     * - 否则 → null（未知版本，同样交给自动识别兜底）
     */
    fun resolveProtocol(version: McVersion): Int? = when {
        version.protocolNumber == AUTO_DETECT.protocolNumber -> null
        version.protocolNumber != null -> version.protocolNumber
        else -> ProtocolTable.byId[version.id]
    }

    /** UI 设置项使用：只清除版本 manifest 缓存，不影响协议表和连接状态机。 */
    fun clearCache() {
        runCatching { if (cacheFile.exists()) cacheFile.delete() }
    }

    // ---- 内部 ----

    private fun fetchManifest(): List<McVersion> {
        val req = Request.Builder().url(MANIFEST_URL).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = JSONObject(resp.body?.string().orEmpty())
            val arr = root.getJSONArray("versions")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                McVersion(
                    id = id,
                    type = VersionType.fromManifest(o.getString("type")),
                    protocolNumber = ProtocolTable.byId[id],   // 能对上内置表就补协议号
                    releaseTime = o.optString("releaseTime", "")
                )
            }
        }
    }

    private fun loadCache(): List<McVersion>? =
        runCatching {
            if (!cacheFile.exists()) null
            else json.decodeFromString<List<McVersion>>(cacheFile.readText())
        }.getOrNull()

    /** 合并：manifest 为全集，内置表补协议号；同 id 去重，内置协议号优先 */
    private fun mergeWithBuiltIn(remote: List<McVersion>): List<McVersion> {
        val byId = LinkedHashMap<String, McVersion>()
        remote.forEach { byId[it.id] = it }
        ProtocolTable.entries.forEach { built ->
            val existing = byId[built.id]
            byId[built.id] = existing?.copy(protocolNumber = built.protocolNumber) ?: built
        }
        return byId.values.toList()
    }

    private fun group(all: List<McVersion>): Grouped {
        val releases = all.filter { it.type == VersionType.RELEASE }
        val snapshots = all.filter { it.type == VersionType.SNAPSHOT }
        val old = all.filter { it.type == VersionType.OLD_BETA || it.type == VersionType.OLD_ALPHA }
        val latest = releases.firstOrNull() ?: ProtocolTable.latestRelease
        return Grouped(
            autoDetect = AUTO_DETECT,
            latest = latest,
            releases = releases,
            snapshots = snapshots,
            oldVersions = old
        )
    }

    private companion object {
        const val MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    }
}