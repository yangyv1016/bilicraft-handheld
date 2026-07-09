package com.bilicraft.handheld.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * MC 文本组件（Chat Component）→ 纯文本 的归一化。
 *
 * 服务器聊天可能是：纯字符串、{"text":...,"extra":[...]} 结构、或带 translate。
 * 插件只关心可读文本，因此这里递归提取所有 text 段拼接，剥离颜色/格式代码。
 * 这是「协议细节不外泄」原则的一部分：把富文本差异消化在协议层内。
 */
object ChatComponent {

    fun toPlainText(raw: String): String {
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("{") -> extractFromObject(JSONObject(trimmed))
                trimmed.startsWith("[") -> extractFromArray(JSONArray(trimmed))
                else -> stripLegacyCodes(trimmed)
            }
        } catch (e: Exception) {
            stripLegacyCodes(trimmed)
        }
    }

    private fun extractFromObject(obj: JSONObject): String = buildString {
        append(obj.optString("text", ""))
        // translate 组件：退化为其 key（多数聊天走 text，不强求完美翻译）
        obj.optString("translate", "").let { if (it.isNotEmpty()) append(it) }
        obj.optJSONArray("with")?.let { append(" ").append(extractFromArray(it)) }
        obj.optJSONArray("extra")?.let { append(extractFromArray(it)) }
    }.let { stripLegacyCodes(it) }

    private fun extractFromArray(arr: JSONArray): String = buildString {
        for (i in 0 until arr.length()) {
            when (val item = arr.get(i)) {
                is JSONObject -> append(extractFromObject(item))
                is String -> append(item)
                else -> append(item.toString())
            }
        }
    }

    /** 剥离 §x 传统颜色/格式代码 */
    private fun stripLegacyCodes(s: String): String =
        s.replace(Regex("\u00A7[0-9a-fk-or]", RegexOption.IGNORE_CASE), "")
}