package com.bilicraft.handheld.protocol

import org.json.JSONObject

/**
 * Minecraft language key → 文本模板。
 *
 * 对齐 MCC 的职责划分：聊天组件只读取 translate key，真正的 key 查表来自完整
 * Minecraft 语言资源。默认由 Android 宿主在启动时从 assets/minecraft/zh_cn.json 注入。
 */
object MinecraftTranslations {

    @Volatile private var templates: Map<String, String> = emptyMap()

    fun loadJson(rawJson: String) {
        val obj = JSONObject(rawJson)
        val loaded = LinkedHashMap<String, String>(obj.length())
        for (key in obj.keys()) {
            loaded[key] = obj.optString(key)
        }
        templates = loaded
    }

    fun templateFor(key: String): String? = templates[key]
}