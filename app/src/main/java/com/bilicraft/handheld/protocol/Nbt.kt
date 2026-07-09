package com.bilicraft.handheld.protocol

import io.netty.buffer.ByteBuf
import java.io.IOException

/**
 * 最小 NBT 子系统。只服务一个目的：解析 1.20.3(协议765)+ 服务器以「网络 NBT」
 * 形式下发的聊天文本组件，供 ChatComponent 归一化为纯文本。
 *
 * 不追求完整 NBT 规范（不写、不落盘、不处理压缩），只覆盖文本组件会出现的 tag：
 *   Byte / Short / Int / Long / Float / Double / String / List / Compound
 *   以及三类数组（Byte/Int/Long Array，聊天里罕见但按规范兜底跳过内容）。
 *
 * 关键差异（易错点）：
 *   - 「网络 NBT」根标签**没有名字**：类型字节之后直接是 payload，不像磁盘 NBT 带一个根名。
 *     1.20.2(764) 起客户端收到的组件即为此形态。
 *   - NBT 字符串长度是**无符号 short**（2 字节），编码为 Java modified-UTF-8；
 *     聊天文本几乎都是 BMP 字符，这里按标准 UTF-8 解析即可覆盖。
 */
sealed interface NbtTag {
    data class NbtByte(val value: Byte) : NbtTag
    data class NbtShort(val value: Short) : NbtTag
    data class NbtInt(val value: Int) : NbtTag
    data class NbtLong(val value: Long) : NbtTag
    data class NbtFloat(val value: Float) : NbtTag
    data class NbtDouble(val value: Double) : NbtTag
    data class NbtString(val value: String) : NbtTag
    data class NbtList(val items: List<NbtTag>) : NbtTag
    data class NbtCompound(val entries: Map<String, NbtTag>) : NbtTag
    data object NbtEnd : NbtTag
}

/**
 * NBT 网络形态读取器。所有方法都是 ByteBuf 扩展，无状态、可组合，
 * 与 McTypes 的原语风格一致。
 */
object Nbt {

    // tag 类型号（NBT 规范固定值）
    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    /**
     * 读取一个「网络 NBT」值（根标签无名字）。
     * 若根类型为 TAG_End（协议里表示空组件）返回 NbtEnd。
     */
    fun ByteBuf.readNetworkNbt(): NbtTag {
        val rootType = readByte().toInt()
        if (rootType == TAG_END) return NbtTag.NbtEnd
        return readPayload(rootType)
    }

    /** 按 tag 类型读取其 payload（不含名字，名字由调用方在 compound 内处理） */
    private fun ByteBuf.readPayload(type: Int): NbtTag = when (type) {
        TAG_BYTE -> NbtTag.NbtByte(readByte())
        TAG_SHORT -> NbtTag.NbtShort(readShort())
        TAG_INT -> NbtTag.NbtInt(readInt())
        TAG_LONG -> NbtTag.NbtLong(readLong())
        TAG_FLOAT -> NbtTag.NbtFloat(readFloat())
        TAG_DOUBLE -> NbtTag.NbtDouble(readDouble())
        TAG_STRING -> NbtTag.NbtString(readNbtString())
        TAG_LIST -> readListPayload()
        TAG_COMPOUND -> readCompoundPayload()
        TAG_BYTE_ARRAY -> { skipArray(elementBytes = 1); NbtTag.NbtEnd }
        TAG_INT_ARRAY -> { skipArray(elementBytes = 4); NbtTag.NbtEnd }
        TAG_LONG_ARRAY -> { skipArray(elementBytes = 8); NbtTag.NbtEnd }
        else -> throw IOException("未知 NBT tag 类型: $type")
    }

    /** List payload：[元素类型(1B)][长度(4B)][元素...] */
    private fun ByteBuf.readListPayload(): NbtTag {
        val elementType = readByte().toInt()
        val length = readInt()
        if (length <= 0) return NbtTag.NbtList(emptyList())
        val items = ArrayList<NbtTag>(length)
        repeat(length) { items.add(readPayload(elementType)) }
        return NbtTag.NbtList(items)
    }

    /** Compound payload：连续的 [类型(1B)][名字(String)][payload]，遇 TAG_End 结束 */
    private fun ByteBuf.readCompoundPayload(): NbtTag {
        val entries = LinkedHashMap<String, NbtTag>()
        while (true) {
            val type = readByte().toInt()
            if (type == TAG_END) break
            val name = readNbtString()
            entries[name] = readPayload(type)
        }
        return NbtTag.NbtCompound(entries)
    }

    /** NBT 字符串：无符号 short 长度前缀 + UTF-8 内容 */
    private fun ByteBuf.readNbtString(): String {
        val len = readUnsignedShort()
        val bytes = ByteArray(len)
        readBytes(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** 数组类 tag：[长度(4B)][length*elementBytes]，聊天组件用不到内容，读长度后跳过 */
    private fun ByteBuf.skipArray(elementBytes: Int) {
        val length = readInt()
        if (length > 0) skipBytes(length * elementBytes)
    }
}