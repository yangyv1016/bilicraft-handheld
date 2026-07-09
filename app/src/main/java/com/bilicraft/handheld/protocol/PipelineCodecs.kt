package com.bilicraft.handheld.protocol

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import com.bilicraft.handheld.protocol.McTypes.readVarInt
import com.bilicraft.handheld.protocol.McTypes.writeVarInt
import com.bilicraft.handheld.protocol.McTypes.varIntSize
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher

/**
 * MC Java 协议的传输管线，三层顺序（Netty pipeline 从外到内）：
 *   [加密] ── [帧长度] ── [压缩] ── [包编解码]
 *
 * 这三层完全与协议版本无关，任何版本共用。它们把「字节流」整理成
 * 「一个个未压缩的 packet ByteBuf」交给上层，反之亦然。
 */

/**
 * 帧编解码：MC 每个包前面有一个 VarInt 表示包长度。
 * 解码：按长度前缀切出完整包；编码：写入长度前缀。
 */
class FrameCodec : ByteToMessageCodec<ByteBuf>() {

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        val len = msg.readableBytes()
        out.ensureWritable(varIntSize(len) + len)
        out.writeVarInt(len)
        out.writeBytes(msg)
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        buf.markReaderIndex()
        // 读长度前缀，若数据不足则回退等待更多字节
        var numRead = 0
        var length = 0
        while (true) {
            if (!buf.isReadable) { buf.resetReaderIndex(); return }
            val b = buf.readByte().toInt()
            length = length or ((b and 0x7F) shl (7 * numRead))
            numRead++
            if (b and 0x80 == 0) break
            if (numRead > 5) throw RuntimeException("VarInt 帧长度过长")
        }
        if (buf.readableBytes() < length) { buf.resetReaderIndex(); return }
        out.add(buf.readRetainedSlice(length))
    }
}

/**
 * 压缩编解码：登录后服务器可能发 Set Compression 包，设定阈值。
 * 启用后每个包变为 [dataLength(VarInt)][zlib 数据]，dataLength=0 表示未压缩。
 */
class CompressionCodec(var threshold: Int) : ByteToMessageCodec<ByteBuf>() {

    private val deflater = Deflater()
    private val inflater = Inflater()

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        val dataLen = msg.readableBytes()
        if (dataLen < threshold) {
            // 未达阈值：dataLength 前缀写 0，原样附上
            out.writeVarInt(0)
            out.writeBytes(msg)
        } else {
            out.writeVarInt(dataLen)
            val input = ByteArray(dataLen)
            msg.readBytes(input)
            deflater.setInput(input)
            deflater.finish()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.writeBytes(buffer, 0, n)
            }
            deflater.reset()
        }
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        if (!buf.isReadable) return
        val dataLen = buf.readVarInt()
        if (dataLen == 0) {
            // 未压缩，直接透传剩余
            out.add(buf.readRetainedSlice(buf.readableBytes()))
        } else {
            val compressed = ByteArray(buf.readableBytes())
            buf.readBytes(compressed)
            inflater.setInput(compressed)
            val result = ByteArray(dataLen)
            inflater.inflate(result)
            inflater.reset()
            out.add(ctx.alloc().buffer(dataLen).writeBytes(result))
        }
    }
}

/**
 * 加密编解码：握手完成后插入。使用 AES/CFB8 流式加解密。
 * cipher 状态随流累积，因此这里逐字节走 Cipher.update。
 */
class DecryptHandler(private val cipher: Cipher) : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        val len = buf.readableBytes()
        if (len == 0) return
        val input = ByteArray(len)
        buf.readBytes(input)
        val output = cipher.update(input)
        out.add(ctx.alloc().buffer(output.size).writeBytes(output))
    }
}

class EncryptHandler(private val cipher: Cipher) : MessageToByteEncoder<ByteBuf>() {
    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        val len = msg.readableBytes()
        val input = ByteArray(len)
        msg.readBytes(input)
        out.writeBytes(cipher.update(input))
    }
}