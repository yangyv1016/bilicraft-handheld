package com.bilicraft.handheld.protocol

import io.netty.buffer.ByteBuf
import java.io.IOException
import java.util.UUID

/**
 * MC Java 协议的字节原语。所有读写都是 ByteBuf 上的扩展函数（无状态、可组合）。
 *
 * 这是协议层最底层，向上层隐藏了 VarInt 的 7-bit 变长编码等细节。
 * MC 协议所有版本共用这套原语，版本差异只在「包结构」层面，不在原语层面。
 */
object McTypes {

    /** VarInt：MC 协议最核心的变长整数编码（7 位数据 + 1 位续标） */
    fun ByteBuf.readVarInt(): Int {
        var value = 0
        var position = 0
        while (true) {
            val currentByte = readByte().toInt()
            value = value or ((currentByte and 0x7F) shl position)
            if (currentByte and 0x80 == 0) break
            position += 7
            if (position >= 32) throw IOException("VarInt 过长")
        }
        return value
    }

    fun ByteBuf.writeVarInt(valueIn: Int): ByteBuf {
        var value = valueIn
        while (true) {
            if (value and 0x7F.inv() == 0) {
                writeByte(value)
                return this
            }
            writeByte((value and 0x7F) or 0x80)
            value = value ushr 7
        }
    }

    /** 计算某个 VarInt 编码后占几个字节（帧长度前缀计算用） */
    fun varIntSize(valueIn: Int): Int {
        var value = valueIn
        var size = 0
        do {
            size++
            value = value ushr 7
        } while (value != 0)
        return size
    }

    /** 带长度前缀的 UTF-8 字符串 */
    fun ByteBuf.readString(maxLen: Int = 32767): String {
        val len = readVarInt()
        if (len > readableBytes()) throw IOException("字符串长度越界")
        val bytes = ByteArray(len)
        readBytes(bytes)
        val s = String(bytes, Charsets.UTF_8)
        if (s.length > maxLen) throw IOException("字符串超过最大长度")
        return s
    }

    fun ByteBuf.writeString(value: String): ByteBuf {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(bytes.size)
        writeBytes(bytes)
        return this
    }

    /** 16 字节 UUID（登录握手用） */
    fun ByteBuf.writeUuid(uuid: UUID): ByteBuf {
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
        return this
    }

    fun ByteBuf.readUuid(): UUID = UUID(readLong(), readLong())

    /** 无短横线的 hex UUID（Mojang profile 返回的形式）转标准 UUID */
    fun uuidFromUndashed(hex: String): UUID {
        val dashed = buildString {
            append(hex, 0, 8); append('-')
            append(hex, 8, 12); append('-')
            append(hex, 12, 16); append('-')
            append(hex, 16, 20); append('-')
            append(hex, 20, 32)
        }
        return UUID.fromString(dashed)
    }

    /** 定长字节数组（前缀 VarInt 长度） */
    fun ByteBuf.readByteArray(): ByteArray {
        val len = readVarInt()
        val arr = ByteArray(len)
        readBytes(arr)
        return arr
    }

    fun ByteBuf.writeByteArray(bytes: ByteArray): ByteBuf {
        writeVarInt(bytes.size)
        writeBytes(bytes)
        return this
    }
}