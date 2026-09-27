package com.bilicraft.handheld.resourcepack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.bilicraft.handheld.protocol.Nbt
import com.bilicraft.handheld.protocol.NbtTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Shared skin/profile cache, with bounded downloads on the IO dispatcher. */
class PlayerHeadSkins(directory: File) {
    private val http = OkHttpClient.Builder().cache(Cache(directory, 32L * 1024 * 1024))
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val profiles = LruCache<PlayerHeadProfile, PlayerHeadProfile>(256)
    private val skins = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    // Bounded striped locks deduplicate requests for the same profile.
    private val locks = List(4) { Mutex() }

    suspend fun load(profile: PlayerHeadProfile, defaultSkin: (PlayerHeadProfile) -> Bitmap): Bitmap = withContext(Dispatchers.IO) {
        locks[Math.floorMod(profile.hashCode(), locks.size)].withLock {
            val resolved = if (!profile.dynamic) profile else profiles.get(profile) ?: run {
                val uuid = profile.id?.toString()?.replace("-", "") ?: run {
                    val name = requireNotNull(profile.name)
                    require(name.matches(Regex("[A-Za-z0-9_]{1,16}"))) { "玩家名无效" }
                    JSONObject(String(download("https://api.mojang.com/users/profiles/minecraft/$name"), Charsets.UTF_8)).getString("id")
                }
                require(uuid.matches(Regex("[a-fA-F0-9]{32}"))) { "玩家 UUID 无效" }
                val data = JSONObject(String(download("https://sessionserver.mojang.com/session/minecraft/profile/$uuid"), Charsets.UTF_8))
                val resolvedId = UUID(java.lang.Long.parseUnsignedLong(uuid.take(16), 16), java.lang.Long.parseUnsignedLong(uuid.takeLast(16), 16))
                profile.copy(id = resolvedId, properties = data.optJSONArray("properties")?.let { Nbt.fromJson(it) }
                    ?: NbtTag.NbtList(emptyList())).also { profiles.put(profile, it) }
            }
            val url = resolved.skinUrl() ?: return@withLock defaultSkin(resolved)
            skins.get(url)?.let { return@withLock it }
            val bytes = download(url, immutable = true)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth == 64 && bounds.outHeight in listOf(32, 64)) { "头颅皮肤必须为 64×32 或 64×64 PNG" }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("头颅皮肤无法解码")
            // Vanilla base head is opaque. Pre-1.8 skins can have a fully opaque, unused hat area.
            val pixels = IntArray(64 * bitmap.height)
            bitmap.getPixels(pixels, 0, 64, 0, 0, 64, bitmap.height)
            if (bitmap.height == 32 && (0 until 16).all { y -> (32 until 64).all { x -> pixels[y * 64 + x] ushr 24 >= 128 } }) {
                for (y in 0 until 16) for (x in 32 until 64) pixels[y * 64 + x] = pixels[y * 64 + x] and 0xffffff
            }
            for (y in 0 until 16) for (x in 0 until 32) pixels[y * 64 + x] = pixels[y * 64 + x] or 0xff000000.toInt()
            Bitmap.createBitmap(pixels, 64, bitmap.height, Bitmap.Config.ARGB_8888).also { skins.put(url, it) }
        }
    }

    private suspend fun download(url: String, immutable: Boolean = false): ByteArray {
        val request = Request.Builder().url(url)
        if (immutable) request.cacheControl(CacheControl.Builder().maxStale(365, TimeUnit.DAYS).build())
        http.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful) { "头颅皮肤下载失败：HTTP ${response.code}" }
            val body = response.body ?: error("头颅皮肤响应为空")
            require(body.contentLength() <= 262144) { "头颅皮肤响应过大" }
            return body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 262144) { "头颅皮肤响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
}
