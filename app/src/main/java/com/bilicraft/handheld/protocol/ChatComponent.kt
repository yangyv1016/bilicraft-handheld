package com.bilicraft.handheld.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * MC 文本组件（Chat Component）→ 富文本片段 List<ChatSpan> 的归一化。
 *
 * 服务器聊天可能是：纯字符串（可能内嵌 §x 传统颜色码）、
 * {"text":...,"color":...,"extra":[...]} 结构、
 * 或 {"translate":"key","with":[...]} 翻译组件（系统消息、加入/离开提示、
 * 玩家聊天外壳几乎都是翻译组件）。
 *
 * 这里递归遍历组件树，把颜色/格式消化成语义化的 ChatSpan 序列——
 * UI 只认 ChatSpan（文本 + RGB 颜色 + 粗斜体等），不接触 §x 码或 JSON color 字段。
 * 这是「协议细节不外泄」原则的一部分。
 *
 * 样式继承规则（对齐 vanilla）：子组件继承父组件样式，除非自身字段覆盖；
 * text 内嵌的 §x 码在继承样式之上叠加，颜色码会重置格式（与 §r 一致）。
 * 翻译组件套用内置模板（把 %s 占位符替换为 with 参数），
 * 否则用户会看到 "multiplayer.player.joined MyH5392" 这样的原始 key。
 */
object ChatComponent {

    /** 一段文本的样式。color 为 RGB 整数（0xRRGGBB），null = UI 默认前景色。 */
    private data class Style(
        val color: Int? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false
    )

    /** §x 传统颜色码 → RGB。索引即码字符（0-9 a-f）。 */
    private val legacyColorRgb: Map<Char, Int> = mapOf(
        '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA,
        '4' to 0xAA0000, '5' to 0xAA00AA, '6' to 0xFFAA00, '7' to 0xAAAAAA,
        '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF,
        'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF,
    )

    /** JSON/NBT 命名颜色 → RGB（与 §x 同色，另含 1.16+ 允许的名字）。 */
    private val namedColorRgb: Map<String, Int> = mapOf(
        "black" to 0x000000, "dark_blue" to 0x0000AA, "dark_green" to 0x00AA00,
        "dark_aqua" to 0x00AAAA, "dark_red" to 0xAA0000, "dark_purple" to 0xAA00AA,
        "gold" to 0xFFAA00, "gray" to 0xAAAAAA, "grey" to 0xAAAAAA,
        "dark_gray" to 0x555555, "dark_grey" to 0x555555, "blue" to 0x5555FF,
        "green" to 0x55FF55, "aqua" to 0x55FFFF, "red" to 0xFF5555,
        "light_purple" to 0xFF55FF, "yellow" to 0xFFFF55, "white" to 0xFFFFFF,
    )

    /**
     * 内置翻译模板表（vanilla en_us 子集，只覆盖聊天链路高频 key）。
     *
     * 模板占位符遵循 Java 格式串：%s 顺序取参、%1$s 按索引取参、%% 表示字面 %。
     * 未登记的 key 走兜底：有 with 参数时拼接参数（牺牲 key 求可读），否则显示 key。
     */
    private val translationTemplates: Map<String, String> = mapOf(
        "chat.type.text" to "<%s> %s",
        "chat.type.announcement" to "[%s] %s",
        "chat.type.emote" to "* %s %s",
        "chat.type.admin" to "[%s: %s]",
        "commands.message.display.incoming" to "%s 悄悄对你说：%s",
        "commands.message.display.outgoing" to "你悄悄对 %s 说：%s",
        "multiplayer.player.joined" to "%s 加入了游戏",
        "multiplayer.player.joined.renamed" to "%s（曾用名 %s）加入了游戏",
        "multiplayer.player.left" to "%s 离开了游戏",
        "chat.disabled.missingProfileKey" to "聊天被禁用：缺少玩家资料公钥（服务器未收到有效的签名会话）",
        "chat.disabled.chain_broken" to "聊天被禁用：消息链断裂",
        "chat.disabled.expiredProfileKey" to "聊天被禁用：玩家资料公钥已过期",
        "chat.disabled.invalid_signature" to "聊天被禁用：签名无效",
        "chat.disabled.out_of_order_chat" to "聊天被禁用：消息乱序",
        "multiplayer.disconnect.not_whitelisted" to "你不在服务器白名单中",
        "multiplayer.disconnect.banned" to "你已被服务器封禁",
        "multiplayer.disconnect.kicked" to "你被踢出了服务器",
        "multiplayer.disconnect.server_full" to "服务器已满",
        "death.attack.player" to "%s 被 %s 杀死了",
        "death.attack.mob" to "%s 被 %s 杀死了",
        "death.attack.fall" to "%s 落地过猛",
        "death.attack.drown" to "%s 淹死了",
        "death.attack.lava" to "%s 试图在岩浆里游泳",
        "death.attack.inFire" to "%s 葬身火海",
        "death.attack.explosion" to "%s 被炸飞了",
    )

    // ==== 对外入口 ====

    /** JSON/字符串组件 → 富文本片段。 */
    fun toSpans(raw: String): List<ChatSpan> {
        val trimmed = raw.trim()
        val out = ArrayList<ChatSpan>()
        try {
            when {
                trimmed.startsWith("{") -> appendJson(JSONObject(trimmed), Style(), out)
                trimmed.startsWith("[") -> appendJsonArray(JSONArray(trimmed), Style(), out)
                else -> appendLegacy(trimmed, Style(), out)
            }
        } catch (e: Exception) {
            appendLegacy(trimmed, Style(), out)
        }
        return out
    }

    /** NBT 组件（1.20.3/协议765+）→ 富文本片段。 */
    fun spansFromNbt(tag: NbtTag): List<ChatSpan> =
        ArrayList<ChatSpan>().also { appendNbt(tag, Style(), it) }

    /** 纯文本（插件关键字匹配、断开原因兜底）：片段文本拼接。 */
    fun toPlainText(raw: String): String = toSpans(raw).joinToString("") { it.text }

    fun fromNbt(tag: NbtTag): String = spansFromNbt(tag).joinToString("") { it.text }

    // ==== JSON 路径 ====

    private fun appendJson(obj: JSONObject, inherited: Style, out: MutableList<ChatSpan>) {
        val style = mergeJsonStyle(obj, inherited)
        // text：内嵌 §x 码在当前样式上叠加
        obj.optString("text", "").takeIf { it.isNotEmpty() }?.let { appendLegacy(it, style, out) }
        // translate：套用模板后作为一段（保留本组件自身颜色；逐参数颜色暂不细分）
        obj.optString("translate", "").takeIf { it.isNotEmpty() }?.let { key ->
            val args = obj.optJSONArray("with")?.let { arr ->
                List(arr.length()) { i ->
                    when (val item = arr.get(i)) {
                        is JSONObject -> extractPlain(item)
                        is JSONArray -> extractPlainArray(item)
                        else -> item.toString()
                    }
                }
            } ?: emptyList()
            appendLegacy(renderTranslation(key, args), style, out)
        }
        obj.optJSONArray("extra")?.let { appendJsonArray(it, style, out) }
    }

    private fun appendJsonArray(arr: JSONArray, inherited: Style, out: MutableList<ChatSpan>) {
        for (i in 0 until arr.length()) {
            when (val item = arr.get(i)) {
                is JSONObject -> appendJson(item, inherited, out)
                is JSONArray -> appendJsonArray(item, inherited, out)
                is String -> appendLegacy(item, inherited, out)
                else -> appendLegacy(item.toString(), inherited, out)
            }
        }
    }

    /** 从 JSON 对象读取样式字段，覆盖到继承样式上（缺省字段沿用继承值）。 */
    private fun mergeJsonStyle(obj: JSONObject, base: Style): Style = base.copy(
        color = obj.optString("color", "").takeIf { it.isNotEmpty() }?.let { parseColor(it) } ?: base.color,
        bold = if (obj.has("bold")) obj.optBoolean("bold") else base.bold,
        italic = if (obj.has("italic")) obj.optBoolean("italic") else base.italic,
        underline = if (obj.has("underlined")) obj.optBoolean("underlined") else base.underline,
        strikethrough = if (obj.has("strikethrough")) obj.optBoolean("strikethrough") else base.strikethrough,
    )

    /** 颜色字段解析：#RRGGBB 十六进制 或 命名色。无法识别返回 null（用默认色）。 */
    private fun parseColor(value: String): Int? = when {
        value.startsWith("#") -> value.drop(1).toIntOrNull(16)
        else -> namedColorRgb[value.lowercase()]
    }

    // ==== NBT 路径（与 JSON 同构） ====

    private fun appendNbt(tag: NbtTag, inherited: Style, out: MutableList<ChatSpan>) {
        when (tag) {
            is NbtTag.NbtString -> appendLegacy(tag.value, inherited, out)
            is NbtTag.NbtList -> tag.items.forEach { appendNbt(it, inherited, out) }
            is NbtTag.NbtCompound -> {
                val style = mergeNbtStyle(tag, inherited)
                (tag.entries["text"] as? NbtTag.NbtString)?.value
                    ?.takeIf { it.isNotEmpty() }?.let { appendLegacy(it, style, out) }
                (tag.entries["translate"] as? NbtTag.NbtString)?.let { keyTag ->
                    val args = (tag.entries["with"] as? NbtTag.NbtList)
                        ?.items?.map { extractPlainNbt(it) } ?: emptyList()
                    appendLegacy(renderTranslation(keyTag.value, args), style, out)
                }
                tag.entries["extra"]?.let { appendNbt(it, style, out) }
            }
            else -> Unit
        }
    }

    private fun mergeNbtStyle(tag: NbtTag.NbtCompound, base: Style): Style = base.copy(
        color = (tag.entries["color"] as? NbtTag.NbtString)?.value?.let { parseColor(it) } ?: base.color,
        bold = nbtBool(tag.entries["bold"]) ?: base.bold,
        italic = nbtBool(tag.entries["italic"]) ?: base.italic,
        underline = nbtBool(tag.entries["underlined"]) ?: base.underline,
        strikethrough = nbtBool(tag.entries["strikethrough"]) ?: base.strikethrough,
    )

    /** NBT 布尔存为 Byte（0/1）。非 Byte 返回 null（表示该字段缺省）。 */
    private fun nbtBool(tag: NbtTag?): Boolean? = (tag as? NbtTag.NbtByte)?.value?.let { it.toInt() != 0 }

    // ==== §x 传统码解析：在给定基样式上叠加，切样式时切片 ====

    /**
     * 解析内嵌 §x 码并按样式切片输出。
     * 颜色码（§0-§f）会重置格式再设色（与 vanilla 一致）；§r 完全复位到 base。
     * 无 § 码时整段作为一个片段。
     */
    private fun appendLegacy(text: String, base: Style, out: MutableList<ChatSpan>) {
        var current = base
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) {
                out.add(ChatSpan(run.toString(), current.color, current.bold, current.italic, current.underline, current.strikethrough))
                run.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\u00A7' && i + 1 < text.length) {
                val code = text[i + 1].lowercaseChar()
                val next = when {
                    legacyColorRgb.containsKey(code) -> base.copy(color = legacyColorRgb[code]) // 颜色码重置格式
                    code == 'l' -> current.copy(bold = true)
                    code == 'm' -> current.copy(strikethrough = true)
                    code == 'n' -> current.copy(underline = true)
                    code == 'o' -> current.copy(italic = true)
                    code == 'r' -> base                                                          // 复位
                    code == 'k' -> current                                                        // obfuscated：不渲染动画，忽略
                    else -> null                                                                  // 非样式码，原样保留 §x
                }
                if (next != null) {
                    flush()
                    current = next
                    i += 2
                    continue
                }
            }
            run.append(c)
            i++
        }
        flush()
    }

    // ==== translate 参数扁平化（取纯文本，逐参数颜色暂不保留） ====

    private fun extractPlain(obj: JSONObject): String = toPlainText(obj.toString())
    private fun extractPlainArray(arr: JSONArray): String = toPlainText(arr.toString())
    private fun extractPlainNbt(tag: NbtTag): String = fromNbt(tag)

    /**
     * 翻译组件渲染：查内置模板并填参。
     * 命中 → 按 %s / %n$s 填参；未命中但有参数 → 拼接参数；未命中且无参 → 原样返回 key。
     */
    private fun renderTranslation(key: String, args: List<String>): String {
        val template = translationTemplates[key]
            ?: return if (args.isEmpty()) key else args.joinToString(" ")
        return applyTemplate(template, args)
    }

    /**
     * 套用 Java 风格格式串：%s 顺序取参、%n$s 索引取参、%% 字面百分号。
     * 越界索引替换为空串，避免抛异常。
     */
    private fun applyTemplate(template: String, args: List<String>): String = buildString {
        var i = 0
        var autoIndex = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '%') {
                append(c); i++; continue
            }
            if (i + 1 >= template.length) { append('%'); i++; continue }
            val next = template[i + 1]
            when {
                next == '%' -> { append('%'); i += 2 }
                next == 's' -> {
                    append(args.getOrElse(autoIndex) { "" }); autoIndex++; i += 2
                }
                next.isDigit() -> {
                    var j = i + 1
                    while (j < template.length && template[j].isDigit()) j++
                    if (j + 1 < template.length && template[j] == '$' && template[j + 1] == 's') {
                        val idx = template.substring(i + 1, j).toInt() - 1
                        append(args.getOrElse(idx) { "" })
                        i = j + 2
                    } else {
                        append(c); i++
                    }
                }
                else -> { append(c); i++ }
            }
        }
    }
}