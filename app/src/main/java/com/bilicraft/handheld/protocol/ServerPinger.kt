package com.bilicraft.handheld.protocol

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.buffer.ByteBuf
import com.bilicraft.handheld.protocol.McTypes.readString
import com.bilicraft.handheld.protocol.McTypes.readVarInt
import com.bilicraft.handheld.protocol.McTypes.writeString
import com.bilicraft.handheld.protocol.McTypes.writeVarInt
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 服务器状态查询（Server List Ping）。
 *
 * 用途：「自动识别」时，先 ping 服务器，从 status 响应的 version.protocol
 * 拿到服务器真实协议号，再用该协议号正式连接。这是最可靠的版本协商方式，
 * 避免猜测。ping 使用协议号 -1（约定的握手 next state=status，服务器不校验版本）。
 */
class ServerPinger {

    data class Status(
        val protocol: Int,
        val versionName: String,
        val description: String
    )

    suspend fun ping(address: ServerAddress): Result<Status> =
        suspendCancellableCoroutine { cont ->
            val group: EventLoopGroup = NioEventLoopGroup(1)
            val bootstrap = Bootstrap()
                .group(group)
                .channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<NioSocketChannel>() {
                    override fun initChannel(ch: NioSocketChannel) {
                        ch.pipeline().addLast(FrameCodec())
                        ch.pipeline().addLast(StatusHandler(address, cont) { group.shutdownGracefully() })
                    }
                })

            bootstrap.connect(address.host, address.port).addListener { f ->
                if (!f.isSuccess) {
                    group.shutdownGracefully()
                    if (cont.isActive) cont.resume(Result.failure(f.cause() ?: RuntimeException("连接失败")))
                }
            }

            cont.invokeOnCancellation { group.shutdownGracefully() }
        }

    private class StatusHandler(
        private val address: ServerAddress,
        private val cont: kotlin.coroutines.Continuation<Result<Status>>,
        private val cleanup: () -> Unit
    ) : ChannelInboundHandlerAdapter() {

        private var done = false
        private var resumed = false

        override fun channelActive(ctx: ChannelHandlerContext) {
            // Handshake：protocol=-1(0xFFFFFFF... 作 VarInt)，next state=1(status)
            val hs = ctx.alloc().buffer()
            hs.writeVarInt(0x00)                    // packet id: handshake
            hs.writeVarInt(-1)                      // 协议号 -1：不声明版本
            hs.writeString(address.host)
            hs.writeShort(address.port)
            hs.writeVarInt(1)                       // next state: status
            ctx.write(hs)

            val statusReq = ctx.alloc().buffer()
            statusReq.writeVarInt(0x00)             // status request
            ctx.writeAndFlush(statusReq)
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val buf = msg as ByteBuf
            try {
                val packetId = buf.readVarInt()
                if (packetId == 0x00 && !done) {
                    done = true
                    val json = buf.readString()
                    val root = JSONObject(json)
                    val ver = root.optJSONObject("version")
                    val status = Status(
                        protocol = ver?.optInt("protocol", -1) ?: -1,
                        versionName = ver?.optString("name", "?") ?: "?",
                        description = root.opt("description")?.toString() ?: ""
                    )
                    finish(Result.success(status))
                    ctx.close()
                }
            } catch (e: Exception) {
                finish(Result.failure(e))
                ctx.close()
            } finally {
                buf.release()
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            finish(Result.failure(cause))
            ctx.close()
        }

        private fun finish(result: Result<Status>) {
            if (resumed) return
            resumed = true
            cleanup()
            runCatching { cont.resume(result) }
        }
    }
}