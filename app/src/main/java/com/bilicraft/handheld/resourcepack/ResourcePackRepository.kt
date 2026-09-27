package com.bilicraft.handheld.resourcepack

import com.bilicraft.handheld.protocol.ResourcePackRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class ResourcePackRepository(
    private val directory: File, val baseArchive: ResourcePackArchive? = null,
    val headSkins: PlayerHeadSkins? = null
) {
    private val downloadLock = Mutex()
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()

    fun cacheKey(server: String, request: ResourcePackRequest): String {
        val identity = if (request.sha1.matches(Regex("[a-fA-F0-9]{40}"))) {
            "sha1:${request.sha1.lowercase()}"
        } else {
            "url:${request.url}"
        }
        return digest("SHA-256", "$server\n$identity".toByteArray())
    }

    fun hasApprovedCache(server: String, request: ResourcePackRequest): Boolean {
        val key = cacheKey(server, request)
        return request.sha1.matches(Regex("[a-fA-F0-9]{40}")) &&
            File(directory, "$key.zip").isFile && File(directory, "$key.approved").isFile
    }

    fun markApproved(server: String, request: ResourcePackRequest) {
        File(directory, "${cacheKey(server, request)}.approved").writeText("1")
    }

    suspend fun load(server: String, request: ResourcePackRequest, retainedKeys: Set<String>, onProgress: (Long, Long?) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadLock.withLock {
                val url = request.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("资源包地址必须是 HTTP 或 HTTPS")
                require(request.sha1.isEmpty() || request.sha1.matches(Regex("[a-fA-F0-9]{40}"))) { "服务器提供的 SHA-1 无效" }
                directory.mkdirs()
                val target = File(directory, "${cacheKey(server, request)}.zip")
                if (request.sha1.isNotEmpty() && target.isFile) {
                    val actualHash = target.inputStream().use { input ->
                        val hash = MessageDigest.getInstance("SHA-1")
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            hash.update(buffer, 0, count)
                        }
                        hash.digest().toHex()
                    }
                    if (actualHash.equals(request.sha1, ignoreCase = true)) return@withContext target
                }
                val cacheFiles = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.zip")) }
                var used = cacheFiles.sumOf { it.length() }
                for (old in cacheFiles.sortedBy { it.lastModified() }) {
                    if (used + MAX_DOWNLOAD <= MAX_CACHE) break
                    if (old.nameWithoutExtension in retainedKeys || old == target) continue
                    val bytes = old.length()
                    if (old.delete()) {
                        used -= bytes
                        File(directory, "${old.nameWithoutExtension}.approved").delete()
                    }
                }
                require(used + MAX_DOWNLOAD <= MAX_CACHE) { "资源包缓存达到 512 MiB 上限" }
                val temporary = File.createTempFile("download-", ".part", directory)
                try {
                    val hash = MessageDigest.getInstance("SHA-1")
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        check(response.isSuccessful) { "资源包下载失败：HTTP ${response.code}" }
                        val body = response.body ?: error("资源包下载内容为空")
                        val total = body.contentLength().takeIf { it >= 0 }
                        require(total == null || total <= MAX_DOWNLOAD) { "资源包超过 128 MiB" }
                        var downloaded = 0L
                        body.byteStream().use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    downloaded += read
                                    require(downloaded <= MAX_DOWNLOAD) { "资源包超过 128 MiB" }
                                    hash.update(buffer, 0, read)
                                    output.write(buffer, 0, read)
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                    }
                    require(request.sha1.isEmpty() || hash.digest().toHex().equals(request.sha1, true)) { "资源包 SHA-1 校验失败" }
                    check(!target.exists() || target.delete()) { "无法替换资源包缓存" }
                    check(temporary.renameTo(target)) { "无法保存资源包缓存" }
                    target
                } finally {
                    temporary.delete()
                }
            }
        }

    companion object {
        private const val MAX_CACHE = 512L * 1024 * 1024
        private const val MAX_DOWNLOAD = 128L * 1024 * 1024
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun digest(algorithm: String, bytes: ByteArray) = MessageDigest.getInstance(algorithm).digest(bytes).toHex()
    }
}
