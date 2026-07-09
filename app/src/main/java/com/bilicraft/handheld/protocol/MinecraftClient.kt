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
import com.bilicraft.handheld.protocol.McTypes.writeFixedBitSet
import com.bilicraft.handheld.protocol.McTypes.writeString
import com.bilicraft.handheld.protocol.McTypes.writeUuid
import com.bilicraft.handheld.protocol.McTypes.writeVarInt
import com.bilicraft.handheld.protocol.Nbt.readNetworkNbt
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
    private val palette: PacketPalette,
    private val protocolNumber: Int,
    private val accessToken: String,
    private val playerName: String,
    private val playerUuid: String,
    private val signingMode: ChatSigningMode = ChatSigningMode.UNSIGNED,
    private val certificate: com.bilicraft.handheld.auth.PlayerCertificate? = null
) {
    // session 签名链状态（1.19.3+）：消息序号 + 会话 id。仅签名模式下有意义。
    private val messageChain = MessageChainState()

    // 强制签名模式且证书就绪时构造签名器；否则为 null（走未签名路径）。
    // 仅支持 session 体系（1.19.3/761+）；更低版本不构造签名器，回退未签名。
    private val signer: ChatSigner? =
        if (signingMode == ChatSigningMode.SIGNED && certificate != null && palette.sessionSigning)
            ChatSigner(
                privateKey = certificate.privateKey,
                playerUuid = uuidFromUndashed(playerUuid),
                sessionId = messageChain.sessionId
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

    /**
     * 发送聊天消息（play 阶段有效）。
     *
     * Chat Message 包结构（1.19.3+ session 体系）：
     *   String message | Long timestamp | Long salt
     *   | Prefixed Optional<byte[256]> signature   （签名模式才有）
     *   | VarInt messageCount | Fixed BitSet(20) acknowledged
     * 未签名时 signature 存在标志位为 false，其余字段照写。
     */
    fun sendChat(text: String) {
        val ch = channel ?: return
        if (phase != Phase.PLAY) return
        val sbChat = palette.sbId(PacketKey.SB_CHAT_MESSAGE) ?: return
        val buf = ch.alloc().buffer()
        buf.writeVarInt(sbChat)
        buf.writeString(text)

        val timestamp = System.currentTimeMillis()
        val salt = McCrypto.random.nextLong()
        buf.writeLong(timestamp)
        buf.writeLong(salt)

        // signature：session 签名器就绪则签，否则写"无签名"标志
        val signed = signer?.sign(text, timestamp, salt, messageChain.nextIndex())
        if (signed != null) {
            buf.writeBoolean(true)
            buf.writeBytes(signed.signature)         // 定长 256 字节，无 VarInt 前缀
        } else {
            buf.writeBoolean(false)
        }

        // lastSeen 确认：进服无历史消息，count=0 + 全零 20-bit BitSet
        buf.writeVarInt(0)
        buf.writeFixedBitSet(BooleanArray(ACKNOWLEDGED_BITS), ACKNOWLEDGED_BITS)

        ch.writeAndFlush(buf)
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
            ls.writeVarInt(palette.sbId(PacketKey.SB_LOGIN_START) ?: 0x00)
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
                _state.value = ConnectionState.Failed("解析错误：${e.message}", retriable = false)
                ctx.close()
            } finally {
                buf.release()
            }
        }

        private fun handleLogin(ctx: ChannelHandlerContext, packetId: Int, buf: ByteBuf) {
            when (palette.cbKey(packetId)) {
                PacketKey.CB_SET_COMPRESSION -> {
                    val threshold = buf.readVarInt()
                    installCompression(ctx, threshold)
                }
                PacketKey.CB_ENCRYPTION_REQUEST -> doEncryption(ctx, buf)
                PacketKey.CB_LOGIN_SUCCESS -> onLoginSuccess(ctx)
                PacketKey.CB_LOGIN_DISCONNECT -> failWithServerReason(ctx, buf, "被服务器拒绝")
                else -> Unit
            }
        }

        private fun onLoginSuccess(ctx: ChannelHandlerContext) {
            if (palette.hasConfigPhase) {
                // 现代：发送 Login Acknowledged，进入 configuration
                phase = Phase.CONFIGURATION
                palette.sbId(PacketKey.SB_LOGIN_ACK)?.let {
                    val ack = ctx.alloc().buffer().writeVarInt(it)
                    ctx.writeAndFlush(ack)
                }
            } else {
                enterPlay(ctx)
            }
        }

        private fun handleConfiguration(ctx: ChannelHandlerContext, packetId: Int, buf: ByteBuf) {
            when (palette.cbKey(packetId)) {
                PacketKey.CB_CONFIG_DISCONNECT -> failWithServerReason(ctx, buf, "配置阶段被服务器断开")
                PacketKey.CB_CONFIG_KEEP_ALIVE -> {
                    // 回 keepalive（config 阶段），id 与收到的一致
                    val id = buf.readLong()
                    val ka = ctx.alloc().buffer().writeVarInt(packetId)
                    ka.writeLong(id)
                    ctx.writeAndFlush(ka)
                }
                PacketKey.CB_CONFIG_KNOWN_PACKS -> {
                    // 1.20.5+/766+：服务器询问客户端已知数据包。
                    // 聊天客户端无本地资源包，回一个空表（VarInt 0）即可推进 config。
                    palette.sbId(PacketKey.SB_CONFIG_KNOWN_PACKS)?.let {
                        val reply = ctx.alloc().buffer().writeVarInt(it)
                        reply.writeVarInt(0)
                        ctx.writeAndFlush(reply)
                    }
                }
                PacketKey.CB_CONFIG_FINISH -> {
                    // 确认 config 结束，进入 play
                    palette.sbId(PacketKey.SB_CONFIG_FINISH_ACK)?.let {
                        val fin = ctx.alloc().buffer().writeVarInt(it)
                        ctx.writeAndFlush(fin)
                    }
                    enterPlay(ctx)
                }
                // 其余 config 包（registry data 等）忽略：聊天客户端不需要世界数据
                else -> Unit
            }
        }

        /** 进入 PLAY：session 签名模式先上报玩家公钥，再置为已连接 */
        private fun enterPlay(ctx: ChannelHandlerContext) {
            phase = Phase.PLAY
            sendChatSessionUpdateIfNeeded(ctx)
            _state.value = ConnectionState.Connected(serverBrand = null)
        }

        private fun handlePlay(ctx: ChannelHandlerContext, packetId: Int, buf: ByteBuf) {
            when (palette.cbKey(packetId)) {
                PacketKey.CB_PLAY_DISCONNECT -> failWithServerReason(ctx, buf, "被服务器断开")
                PacketKey.CB_KEEP_ALIVE_PLAY -> {
                    val id = buf.readLong()
                    val sbKa = palette.sbId(PacketKey.SB_KEEP_ALIVE_PLAY) ?: return
                    val ka = ctx.alloc().buffer().writeVarInt(sbKa)
                    ka.writeLong(id)
                    ctx.writeAndFlush(ka)
                }
                PacketKey.CB_SYSTEM_CHAT, PacketKey.CB_PLAYER_CHAT -> emitChat(buf)
                else -> Unit
            }
        }

        /**
         * session 签名（1.19.3+）必需：进 PLAY 后立即发送 Chat Session Update，
         * 上报玩家公钥 + Mojang 签名 + sessionId + 过期时间。不发则强制签名服拒绝后续聊天。
         *
         * 包结构：UUID sessionId | Long expiresAt | ByteArray publicKey(DER) | ByteArray keySignature
         */
        private fun sendChatSessionUpdateIfNeeded(ctx: ChannelHandlerContext) {
            val cert = certificate ?: return
            if (!palette.sessionSigning || signer == null) return
            val sbId = palette.sbId(PacketKey.SB_CHAT_SESSION_UPDATE) ?: return
            val buf = ctx.alloc().buffer()
            buf.writeVarInt(sbId)
            buf.writeUuid(messageChain.sessionId)
            buf.writeLong(cert.expiresAtEpochMs)
            buf.writeByteArray(cert.publicKeyDer)
            buf.writeByteArray(cert.publicKeySignatureV2)
            ctx.writeAndFlush(buf)
        }

        private fun failWithServerReason(ctx: ChannelHandlerContext, buf: ByteBuf, fallback: String) {
            val rawReason = runCatching { buf.readString() }.getOrDefault(fallback)
            val plainReason = ChatComponent.toPlainText(rawReason).ifBlank { rawReason }
            _state.value = ConnectionState.Failed(plainReason, retriable = false)
            ctx.close()
        }

        /**
         * 归一化聊天/系统消息为 ChatEvent。
         *
         * 组件读取按版本二选一：
         *   - chatComponentIsNbt（1.20.3+）→ 读网络 NBT，交 ChatComponent.fromNbt
         *   - 否则 → 读 JSON 字符串，交 ChatComponent.toPlainText
         *
         * 说明：System Chat 包首字段就是内容组件，可直接读取。Player Chat 包内容前
         * 还有 sender/index/签名等头部字段，结构复杂；这里聊天核心优先，先按"内容在包首"
         * 的宽松策略提取（对 System Chat 精确，对 Player Chat 可能失配则跳过），
         * 避免误解析导致连接崩溃。Player Chat 完整解析留待后续按真实抓包细化。
         */
        private fun emitChat(buf: ByteBuf) {
            val (plain, raw) = runCatching {
                if (palette.chatComponentIsNbt) {
                    val tag = buf.readNetworkNbt()
                    ChatComponent.fromNbt(tag) to tag.toString()
                } else {
                    val json = buf.readString()
                    ChatComponent.toPlainText(json) to json
                }
            }.getOrNull() ?: return
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
                _state.value = ConnectionState.Failed("会话校验失败（joinServer）", retriable = false)
                ctx.close()
                return
            }

            // Encryption Response：RSA 加密的 sharedSecret + verifyToken
            val resp = ctx.alloc().buffer()
            resp.writeVarInt(palette.sbId(PacketKey.SB_ENCRYPTION_RESPONSE) ?: 0x01)
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
            if (_state.value is ConnectionState.Failed) return
            if (phase == Phase.PLAY) {
                // 已进入游戏后断开 → 正常掉线，交上层重连
                _state.value = ConnectionState.Disconnected
            } else {
                // 还没进 PLAY 就被对端关闭：握手/登录/配置阶段异常。
                // 服务器往往不发 Disconnect 包直接 close，这里带阶段信息报失败，
                // 否则上层会无声重连、丢失唯一的诊断线索。
                val stage = when (phase) {
                    Phase.HANDSHAKE -> "握手"
                    Phase.LOGIN -> "登录"
                    Phase.CONFIGURATION -> "配置"
                    Phase.PLAY -> "游戏"
                }
                _state.value = ConnectionState.Failed(
                    "连接在「$stage」阶段被服务器关闭（未收到断开原因，协议 $protocolNumber）"
                )
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

    private companion object {
        // 聊天 acknowledged 字段固定 20 位（1.19.1+），序列化为 3 字节
        const val ACKNOWLEDGED_BITS = 20
    }
}