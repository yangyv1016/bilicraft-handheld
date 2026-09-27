package com.bilicraft.handheld.resourcepack

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Indexed ZIP; server paths never become filesystem paths. */
class ResourcePackArchive(val file: File) {
    val fonts: Map<String, JSONObject>
    private val assetPaths: Map<String, String>

    init {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().take(100_001).toList()
            require(entries.size <= 100_000) { "资源包文件数量超出限制" }
            require(entries.all { it.size in 0..512L * 1024 * 1024 } &&
                entries.sumOf { it.size } <= 512L * 1024 * 1024) { "资源包解压大小超出限制" }
            val metadata = zip.getEntry("pack.mcmeta") ?: error("资源包缺少 pack.mcmeta")
            val json = JSONObject(zip.getInputStream(metadata).use { readLimited(it, 1024 * 1024).toString(Charsets.UTF_8) })
            require(json.has("pack")) { "资源包元数据无效" }
            val prefixes = mutableListOf("")
            val overlays = json.optJSONObject("overlays")?.optJSONArray("entries")
            if (overlays != null) for (index in 0 until overlays.length()) {
                val overlay = overlays.getJSONObject(index)
                val formats = overlay.optJSONObject("formats")
                val minimum = overlay.optInt("min_format", formats?.optInt("min_inclusive", 0) ?: 0)
                val maximum = overlay.optInt("max_format", formats?.optInt("max_inclusive", Int.MAX_VALUE) ?: Int.MAX_VALUE)
                if (75 in minimum..maximum) {
                    val prefix = overlay.getString("directory")
                    require(prefix.matches(Regex("[a-zA-Z0-9_-]+"))) { "资源包 overlay 路径无效" }
                    prefixes += "$prefix/"
                }
            }
            assetPaths = buildMap {
                for (prefix in prefixes) for (entry in entries) {
                    if (entry.name.startsWith("${prefix}assets/")) put(entry.name.removePrefix(prefix), entry.name)
                }
            }
            var fontBytes = 0L
            fonts = assetPaths.mapNotNull { (path, zipPath) ->
                val match = FONT_PATH.matchEntire(path) ?: return@mapNotNull null
                val entry = zip.getEntry(zipPath)
                fontBytes += entry.size
                require(fontBytes <= 16 * 1024 * 1024) { "字体定义总大小超出限制" }
                val definition = JSONObject(zip.getInputStream(entry).use {
                    readLimited(it, 1024 * 1024).toString(Charsets.UTF_8)
                })
                "${match.groupValues[1]}:${match.groupValues[2]}" to definition
            }.toMap()
        }
    }

    fun readAsset(id: String, directory: String, suffix: String = ""): ByteArray? {
        val parts = id.split(':', limit = 2)
        val namespace = if (parts.size == 2) parts[0] else "minecraft"
        val path = parts.last()
        require(namespace.matches(Regex("[a-z0-9_.-]+")) &&
            path.matches(Regex("[a-z0-9_./-]+")) && path.split('/').none { it == ".." }) { "资源路径无效" }
        return ZipFile(file).use { zip ->
            val zipPath = assetPaths["assets/$namespace/$directory/$path$suffix"] ?: return null
            val entry = zip.getEntry(zipPath) ?: return null
            zip.getInputStream(entry).use { readLimited(it, 16 * 1024 * 1024) }
        }
    }

    companion object {
        private val FONT_PATH = Regex("assets/([a-z0-9_.-]+)/font/([a-z0-9_./-]+)\\.json")

        fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size().toLong() + read <= maximum) { "资源文件超出大小限制" }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
