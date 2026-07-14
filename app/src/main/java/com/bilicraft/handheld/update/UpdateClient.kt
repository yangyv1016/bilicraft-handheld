package com.bilicraft.handheld.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub 更新的 HTTP 变换实现。
 *
 * 设计与 AuthClient 一致：无状态、无副作用（下载除外，写入调用方指定的文件），
 * 编排逻辑放在 UpdateManager。本类只管「一次 HTTP 往返 + 解析」或「一次流式下载」。
 *
 * 只对接 GitHub 公开 Releases API，无需鉴权（匿名有速率限制，检查更新场景足够）。
 */
class UpdateClient(
    private val owner: String,
    private val repo: String
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 下载单独用长读超时的 client：APK 体积大，默认 30s 读超时不够。
    private val downloadHttp = http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)   // 0 = 不限，靠外层协程取消控制
        .build()

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val reason: String) : Result<Nothing>
    }

    /**
     * 查询最新正式 Release（GitHub 的 /releases/latest 天然排除 prerelease 与 draft）。
     * 解析出版本号与 .apk 资产链接；无 apk 资产视为失败。
     */
    suspend fun fetchLatestRelease(
        rewrite: (String) -> String = { it }
    ): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(rewrite("https://api.github.com/repos/$owner/$repo/releases/latest"))
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext Result.Err("检查更新失败(${resp.code})")
                parseRelease(JSONObject(text))
            }
        } catch (e: IOException) {
            Result.Err("网络错误：${e.message}")
        } catch (e: Exception) {
            Result.Err("解析失败：${e.message}")
        }
    }

    /**
     * 流式下载 APK 到 targetFile，onProgress 回调 0f..1f（总长未知时回调 -1f）。
     * 下载成功返回文件；失败返回原因。整段在 IO 线程，可被外层协程取消。
     */
    suspend fun downloadApk(
        url: String,
        targetFile: File,
        rewrite: (String) -> String = { it },
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(rewrite(url)).get().build()
        try {
            downloadHttp.newCall(req).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) {
                    return@withContext Result.Err("下载失败(${resp.code})")
                }
                val total = body.contentLength()
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var readTotal = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            readTotal += read
                            onProgress(if (total > 0) readTotal.toFloat() / total else -1f)
                        }
                    }
                }
                Result.Ok(targetFile)
            }
        } catch (e: IOException) {
            Result.Err("网络错误：${e.message}")
        } catch (e: Exception) {
            Result.Err("下载出错：${e.message}")
        }
    }

    private fun parseRelease(root: JSONObject): Result<ReleaseInfo> {
        val tag = root.getString("tag_name")
        val assets = root.optJSONArray("assets")
        val apkUrl = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return Result.Err("最新版本未附带安装包")
        return Result.Ok(
            ReleaseInfo(
                tagName = tag,
                versionName = tag.removePrefix("v"),
                releaseNotes = ReleaseNotes.humanize(root.optString("body", "")),
                apkDownloadUrl = apkUrl.getString("browser_download_url"),
                apkSizeBytes = apkUrl.optLong("size", 0L)
            )
        )
    }

    private companion object {
        const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
    }
}