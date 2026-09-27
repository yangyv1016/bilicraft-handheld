package com.bilicraft.handheld.resourcepack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.LruCache
import com.bilicraft.handheld.protocol.ChatSpan
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.floor

data class FontCharacter(val font: String, val codePoint: Int)

data class PackGlyph(
    val bitmap: Bitmap?, val source: Rect,
    val height: Float, val ascent: Float, val advance: Float
)

/** Resource lookup is shared by chat, item names and Lore. Call off the UI thread. */
class ResourcePackFonts(private val archives: List<ResourcePackArchive>) {
    private val _warnings = MutableStateFlow<Set<String>>(emptySet())
    val warnings = _warnings.asStateFlow()
    private val images = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val glyphs = object : LruCache<FontCharacter, PackGlyph>(32 * 1024 * 1024) {
        override fun sizeOf(key: FontCharacter, value: PackGlyph) = (value.bitmap?.allocationByteCount ?: 0) + 128
    }
    private val missing = mutableSetOf<FontCharacter>()

    @Synchronized
    fun resolve(spans: List<ChatSpan>): Map<FontCharacter, PackGlyph> {
        val result = mutableMapOf<FontCharacter, PackGlyph>()
        for (span in spans) {
            var offset = 0
            while (offset < span.text.length) {
                val codePoint = span.text.codePointAt(offset)
                offset += Character.charCount(codePoint)
                if (codePoint == 10 || codePoint == 13) continue
                val key = FontCharacter(normalizeId(span.font), codePoint)
                if (key in result || key in missing) continue
                val glyph = try {
                    glyphs.get(key) ?: findGlyph(key, mutableSetOf())
                } catch (error: Exception) {
                    _warnings.value = (_warnings.value + "${key.font}：${error.message}").take(20).toSet()
                    null
                }
                if (glyph != null) {
                    glyphs.put(key, glyph)
                    result[key] = glyph
                } else {
                    if (missing.size >= 8192) missing.clear()
                    missing.add(key)
                }
            }
        }
        return result
    }

    private fun findGlyph(key: FontCharacter, visited: MutableSet<String>): PackGlyph? {
        if (!visited.add(key.font)) return null
        try {
            for (archive in archives.asReversed()) {
                val providers = archive.fonts[key.font]?.optJSONArray("providers") ?: continue
                for (index in 0 until providers.length()) {
                    val provider = providers.getJSONObject(index)
                    val filter = provider.optJSONObject("filter")
                    // The app uses the ordinary (non-uniform, non-Japanese-variant) font mode.
                    if (filter != null && filter.keys().asSequence().any { filter.optBoolean(it) }) continue
                    when (provider.getString("type").removePrefix("minecraft:")) {
                        "reference" -> findGlyph(key.copy(font = normalizeId(provider.getString("id"))), visited)?.let { return it }
                        "space" -> {
                            val advances = provider.getJSONObject("advances")
                            val character = String(Character.toChars(key.codePoint))
                            if (advances.has(character)) {
                                val advance = advances.getDouble(character).toFloat()
                                require(advance.isFinite() && advance in -32768f..32768f) { "字形间距超出限制" }
                                return PackGlyph(null, Rect(), 0f, 0f, advance)
                            }
                        }
                        "bitmap" -> bitmapGlyph(provider, key.codePoint)?.let { return it }
                        else -> _warnings.value = _warnings.value + "未支持字体类型：${provider.getString("type")}"
                    }
                }
            }
            return null
        } finally {
            visited.remove(key.font)
        }
    }

    private fun bitmapGlyph(provider: JSONObject, codePoint: Int): PackGlyph? {
        val rows = provider.getJSONArray("chars")
        val character = String(Character.toChars(codePoint))
        var rowIndex = -1
        var columnIndex = -1
        var columns = 0
        for (row in 0 until rows.length()) {
            val text = rows.getString(row)
            val offset = text.indexOf(character)
            if (offset >= 0) {
                rowIndex = row
                columnIndex = text.codePointCount(0, offset)
                columns = text.codePointCount(0, text.length)
                break
            }
        }
        if (rowIndex < 0 || codePoint == 0) return null
        val height = provider.optDouble("height", 8.0).toFloat()
        val ascent = provider.getDouble("ascent").toFloat()
        // HUD shader coordinates cannot be represented in a chat line. Leave those glyphs as text.
        if (height <= 0 || height > 256 || !ascent.isFinite() || ascent !in -256f..256f) {
            _warnings.value = _warnings.value + "HUD 着色器字形不适用于聊天，保留原文字"
            return null
        }
        val file = normalizeId(provider.getString("file"))
        val image = images.get(file) ?: run {
            val data = archives.asReversed().firstNotNullOfOrNull { it.readAsset(file, "textures") } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304) {
                "字形图片尺寸超出限制"
            }
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("字形图片无法解码")
            images.put(file, bitmap)
            bitmap
        }
        require(image.width % columns == 0 && image.height % rows.length() == 0) { "字形网格与图片尺寸不匹配" }
        val cellWidth = image.width / columns
        val cellHeight = image.height / rows.length()
        val left = columnIndex * cellWidth
        val top = rowIndex * cellHeight
        var inkWidth = 0
        for (x in cellWidth - 1 downTo 0) {
            if ((0 until cellHeight).any { y -> image.getPixel(left + x, top + y) ushr 24 != 0 }) {
                inkWidth = x + 1
                break
            }
        }
        val scale = height / cellHeight
        return PackGlyph(image, Rect(left, top, left + cellWidth, top + cellHeight), height, ascent,
            floor(inkWidth * scale + 0.5f) + 1f)
    }

    companion object {
        fun normalizeId(id: String) = if (':' in id) id else "minecraft:$id"
    }
}
