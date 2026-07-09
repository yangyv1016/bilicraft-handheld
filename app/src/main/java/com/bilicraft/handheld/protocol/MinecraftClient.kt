package com.bilicraft.handheld.protocol

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import com.bilicraft.handheld.protocol.McTypes.readByteArray
import com.bilicraft.handheld.protocol.McTypes.readString
import com.bilicraft.handheld.protocol.McTypes.readVarInt
import com.bilicraft.handheld.protocol.McTypes.uuidFromUndashed
import com.bilicraft.handheld.protocol.McTypes.writeByteArray
import com.bilicraft.handheld.protocol.McTypes.writeString
import com.bilicraft.handheld.protocol.McTypes.writeUuid
import com.bilicraft.handheld.protocol.McTypes.writeVarInt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import javax.crypto.Cipher

/**
 * MC Java 协议客户端主状态机。
 *
 * 连接流程（现代档案）：
 *   HANDSHAKE → LOGIN(可能加密) → CONFIGURATION → PLAY
 * 传统档案跳过 CONFIGURATION。
 *
 * 对外只暴露：
 *   - state: StateFlow<ConnectionState>
 *   - incoming: SharedFlow<ChatEvent>     （服务器聊天，已归一化）
 *   - sendChat(text)                       （发送聊天）
 * 原始 packet、加密、包 id 全部封死在内部。
 */
class MinecraftClient(
    private val profile: PacketProfile,
    private val protocolNumber: Int,
    private val accessToken: String,
    private val playerName: String,
    private val playerUuid: String,
    private val signingMode: ChatSigningMode = ChatSigningMode.UNSIGNED,
    private val certificate: com.bilicraft.handheld.auth.PlayerCertificate? = null
) {
    // 强制签名模式且证书就绪时构造签名器；否则为 null（走未签名路径）
    private val signer: ChatSigner? =
        if (signingMode == ChatSigningMode.SIGNED && certificate != null && protocolNumber >= 759)
            ChatSigner(
                privateKey = certificate.privateKey,
                playerUuid = uuidFromUndashed(playerUuid),
                protocolNumber = protocolNumber
            )
        else null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 256)
    val incoming: SharedFlow<ChatEvent> = _incoming.asSharedFlow()

    private var channel: Channel? = null
    private var group: EventLoopGroup? = null
    private val http = OkHttpClient()

    // 连接内部阶段
    private enum class Phase { HANDSHAKE, LOGIN, CONFIGURATION, PLAY }
    @Volatile private var phase = Phase.HANDSHAKE

    fun connect(address: ServerAddress) {
        _state.value = ConnectionState.Connecting
        val g = NioEventLoopGroup(1)
        group = g
        Bootstrap()
            .group(g)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<NioSocketChannel>() {
                override fun initChannel(ch: NioSocketChannel) {
                    ch.pipeline().addLast("frame", FrameCodec())
                    ch.pipeline().addLast("packet", PacketHandler(address))
                }
            })
            .connect(address.host, address.port)
            .addListener { f ->
                if (!f.isSuccess) {
                    _state.value = ConnectionState.Failed(f.cause()?.message ?: "连接失败")
                    g.shutdownGracefully()
                }
            }
    }

    fun disconnect() {
        channel?.close()
        group?.shutdownGracefully()
        channel = null
        _state.value = ConnectionState.Disconnected
    }

    /** 发送聊天消息（play 阶段有效） */
    fun sendChat(text: String) {
        val ch = channel ?: return
        if (phase != Phase.PLAY) return
        val buf = ch.alloc().buffer()
        buf.writeVarInt(profile.sbChatMessage)
        buf.writeString(text)
        // 1.19+ 需要时间戳/盐/签名字段。按签名模式二选一：
        // - signer != null（强制签名）→ 真实私钥签名
        // - 否则 → 未签名结构（兼容离线服）
        if (protocolNumber >= 759) {
            val signed = signer?.sign(text, System.currentTimeMillis(), McCrypto.random.nextLong())
            if (signed != null) writeSignedChatTail(buf, signed)
            else writeUnsignedChatTail(buf)
        }
        ch.writeAndFlush(buf)
    }

    private fun writeUnsignedChatTail(buf: ByteBuf) {
        buf.writeLong(System.currentTimeMillis())   // timestamp
        buf.writeLong(0L)                            // salt
        buf.writeBoolean(false)                      // 无签名
        if (protocolNumber >= 760) {                 // 1.19.1+
            buf.writeVarInt(0)                       // 已确认消息数
            buf.writeBoolean(false)                  // 无 lastSeen 更新（简化）
        }
    }

    /** 写入已签名聊天尾部：timestamp/salt/签名字节 + 空 lastSeen 链 */
    private fun writeSignedChatTail(buf: ByteBuf, signed: ChatSigner.SignedMessage) {
        buf.writeLong(signed.timestamp)
        buf.writeLong(signed.salt)
        buf.writeBoolean(true)                       // 有签名
        buf.writeByteArrayRaw(signed.signature)      // 签名字节（定长，无 VarInt 前缀差异见下）
        if (protocolNumber >= 760) {                 // 1.19.1+
            buf.writeVarInt(0)                       // 已确认消息数
            buf.writeBoolean(false)                  // 无 lastSeen 更新
        }
    }

    /** 写入签名字节。1.19 用固定长度块，这里按 VarInt 长度前缀 + 内容通用写法 */
    private fun ByteBuf.writeByteArrayRaw(bytes: ByteArray) {
        writeVarInt(bytes.size)
        writeBytes(bytes)
    }

    // ---- 核心 packet 处理器 ----

    private inner class PacketHandler(
        private val address: ServerAddress
    ) : ChannelInboundHandlerAdapter() {

        override fun channelActive(ctx: ChannelHandlerContext) {
            channel = ctx.channel()
            phase = Phase.HANDSHAKE
            _state.value = ConnectionState.LoggingIn

            // Handshake：声明协议号 + next state=2(login)
            val hs = ctx.alloc().buffer()
            hs.writeVarInt(0x00)
            hs.writeVarInt(protocolNumber)
            hs.writeString(address.host)
            hs.writeShort(address.port)
            hs.writeVarInt(2)
            ctx.write(hs)

            // Login Start
            val ls = ctx.alloc().buffer()
            ls.writeVarInt(profile.sbLoginStart)
            ls.writeString(playerName)
            if (protocolNumber >= 761) {                 // 1.19.3+ 附带 UUID
                ls.writeUuid(uuidFromUndashed(playerUuid))
            }
            ctx.writeAndFlush(ls)
            phase = Phase.LOGIN
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val buf = msg as ByteBuf
            try {
                val packetId = buf.readVarInt()
                when (phase) {
                    Phase.LOGIN -> handleLogin(ctx, packetId, buf)
                    Phase.CONFIGURATION -> handleConfiguration(ctx, packetId, buf)
                    Phase.PLAY -> handlePlay(ctx, packetId, buf)
                    else -> Unit
                }
            } catch (e: Exception) {
                _state.value = ConnectionState.Failed("解析错误：${e.message}")
                ctx.close()
            } finally {
                buf.release()
            }
        }

        private fun handleLogin(ctx: ChannelHandlerContext, packetId: Int, buf: ByteBuf) {
            when (packetId) {
                profile.cbSetCompression -> {
                    val threshold = buf.readVarInt()
                    installCompression(ctx, threshold)
                }
                profile.cbEncryptionRequest -> doEncryption(ctx, buf)
                profile.cbLoginSuccess -> onLoginSuccess(ctx)
                0x00 -> {  // login disconnect
                    val reason = runCatching { buf.readString() }.getOrDefault("被服务器拒绝")
                    _state.value = ConnectionState.Failed(reason)
                    ctx.close()
                }
            }
        }

        private fun onLoginSuccess(ctx: ChannelHandlerContext) {
            if (profile.hasConfigurationPhase) {
                // 现代：发送 Login Acknowledged，进入 configuration
                phase = Phase.CONFIGURATION
                profile.sbLoginAck?.let {
                    val ack = ctx.alloc().buffer().writeVarInt(it)
                    ctx.writeAndFlush(ack)
                }
            } else {
                phase = Phase.PLAY
                _state.value = ConnectionState.Connected(serverBrand = null)
            }
        }

        private fun handleConfiguration(ctx: ChannelHandlerContext, packetId: Int, buf: ByteBuf) {
            when (packetId) {
                profile.cbConfigKeepAlive -> {
                    // 回 keepalive（config 阶段），id 与收到的一致
                    val id = buf.readLong()
                    val ka = ctx.alloc().buffer().writeVarInt(packetId)
                    ka.writeLong(id)
                    ctx.writeAndFlush(ka)
                }
                profile.cbConfigFinish -> {
                    // 确认 config 结束，进入 play
                    profile.sbConfigFinishAck?.let {
                        val fin = ctx.alloc().buffer().writeVarInt(it)
                        ctx.writeAndFlush(fin)
                    }
                    phase = Phase.PLAY
                    _state.value = ConnectionState.Connected(serverBrand = null)
                }
                // 其余 config 包（registry data 等）忽略：聊天客户端不需要世界数据
            }
        }

        private fun handlePlay(ctx: ChannelHandlerContext, packetId: Int, buf: ByteBuf) {
            when (packetId) {
                profile.cbKeepAlivePlay -> {
                    val id = buf.readLong()
                    val ka = ctx.alloc().buffer().writeVarInt(profile.sbKeepAlivePlay)
                    ka.writeLong(id)
                    ctx.writeAndFlush(ka)
                }
                in profile.cbChatCandidates -> emitChat(buf)
            }
        }

        /** 把各版本聊天包尽量归一化为 ChatEvent。宽松解析：读首个字符串当作消息组件。 */
        private fun emitChat(buf: ByteBuf) {
            val raw = runCatching { buf.readString() }.getOrNull() ?: return
            val plain = ChatComponent.toPlainText(raw)
            if (plain.isBlank()) return
            _incoming.tryEmit(ChatEvent(plainText = plain, rawJson = raw))
        }

        private fun doEncryption(ctx: ChannelHandlerContext, buf: ByteBuf) {
            val serverId = buf.readString()
            val pubKeyBytes = buf.readByteArray()
            val verifyToken = buf.readByteArray()

            val secret = McCrypto.generateSharedSecret()
            val pubKey = McCrypto.decodePublicKey(pubKeyBytes)

            // 向 Mojang sessionserver 报到，证明身份
            val hash = McCrypto.serverHash(serverId, secret, pubKey)
            if (!joinServer(hash)) {
                _state.value = ConnectionState.Failed("会话校验失败（joinServer）")
                ctx.close()
                return
            }

            // Encryption Response：RSA 加密的 sharedSecret + verifyToken
            val resp = ctx.alloc().buffer()
            resp.writeVarInt(profile.sbEncryptionResponse)
            resp.writeByteArray(McCrypto.rsaEncrypt(pubKey, secret.encoded))
            resp.writeByteArray(McCrypto.rsaEncrypt(pubKey, verifyToken))
            ctx.writeAndFlush(resp)

            // 之后所有流量加密：在 frame 之前插入加/解密 handler
            val enc = McCrypto.newCipher(Cipher.ENCRYPT_MODE, secret)
            val dec = McCrypto.newCipher(Cipher.DECRYPT_MODE, secret)
            ctx.pipeline().addFirst("decrypt", DecryptHandler(dec))
            ctx.pipeline().addFirst("encrypt", EncryptHandler(enc))
        }

        private fun installCompression(ctx: ChannelHandlerContext, threshold: Int) {
            if (ctx.pipeline().get("compress") == null) {
                ctx.pipeline().addAfter("frame", "compress", CompressionCodec(threshold))
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            if (_state.value !is ConnectionState.Failed) {
                _state.value = ConnectionState.Disconnected
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            _state.value = ConnectionState.Failed(cause.message ?: "未知错误")
            ctx.close()
        }
    }

    /** 向 Mojang sessionserver 报到（在线模式登录必需） */
    private fun joinServer(serverHash: String): Boolean {
        val payload = JSONObject().apply {
            put("accessToken", accessToken)
            put("selectedProfile", playerUuid)
            put("serverId", serverHash)
        }
        val req = Request.Builder()
            .url("https://sessionserver.mojang.com/session/minecraft/join")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}