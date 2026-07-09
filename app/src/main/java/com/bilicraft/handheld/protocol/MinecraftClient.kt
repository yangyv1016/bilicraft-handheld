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
import android.util.Log

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

    // 会话公钥是否已上报（一次性守卫）：JoinGame 会在重生/换世界时重复到达，只在首个 JoinGame 上报。
    @Volatile private var chatSessionReported = false

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
     * Chat Message 包结构（1.19.3+ session 体系，字段顺序对齐 MCC 权威表）：
     *   String message | Long timestamp | Long salt
     *   | bool hasSignature (+ byte[256] signature 仅签名模式)
     *   | VarInt messageCount | Fixed BitSet(20) acknowledged
     *   | byte checksum          （1.21.5/协议770+ 才有，0=跳过校验）
     * 未签名时 salt=0、hasSignature=false。
     */
    fun sendChat(text: String) {
        val ch = channel ?: return
        if (phase != Phase.PLAY) return
        // 斜杠开头是命令：1.19+ 走独立的 Chat Command 包，不能当普通聊天发（服务器会把它当聊天文本，命令不执行）。
        if (text.startsWith("/")) {
            sendCommand(ch, text.removePrefix("/"))
            return
        }
        val sbChat = palette.sbId(PacketKey.SB_CHAT_MESSAGE) ?: return
        val buf = ch.alloc().buffer()
        buf.writeVarInt(sbChat)
        buf.writeString(text)

        val timestamp = System.currentTimeMillis()
        buf.writeLong(timestamp)

        // signature：session 签名器就绪则签，否则写"无签名"标志。
        // 未签名时 salt 恒为 0（对齐 MCC 权威表：无签名的 salt 不参与校验）。
        val signed = signer?.sign(text, timestamp, McCrypto.random.nextLong(), messageChain.nextIndex())
        if (signed != null) {
            buf.writeLong(signed.salt)
            buf.writeBoolean(true)
            buf.writeBytes(signed.signature)         // 定长 256 字节，无 VarInt 前缀
        } else {
            buf.writeLong(0L)
            buf.writeBoolean(false)
        }

        // lastSeen 确认：进服无历史消息，count=0 + 全零 20-bit BitSet
        buf.writeVarInt(0)
        buf.writeFixedBitSet(BooleanArray(ACKNOWLEDGED_BITS), ACKNOWLEDGED_BITS)

        // Checksum 字节（1.21.5/协议770+）：0 = 跳过校验。缺此字节会导致服务器解析越界。
        if (palette.chatHasChecksum) buf.writeByte(0)

        ch.writeAndFlush(buf)
    }

    /**
     * 发送斜杠命令（1.19+ session 体系，字段顺序对齐 MCC SendChatCommand）。
     *
     * Chat Command 包结构（1.19.3+，我们不解析命令树，故走「无签名参数」路径）：
     *   String 命令(不含前导斜杠) | Long timestamp | Long salt(0)
     *   | VarInt 参数签名数(0) | VarInt messageCount(0) | Fixed BitSet(20) acknowledged
     *   | byte checksum          （1.21.5/协议770+ 才有）
     *
     * 与 Chat Message 的关键差异：命令包没有 hasSignature 布尔（那是 ≤1.19.2 的旧结构），
     * 取而代之的是「参数签名数」VarInt。命令参数签名需要 DeclareCommands 命令树，
     * 本客户端不解析命令树 → 参数签名数恒为 0，服务器照常接受并执行命令。
     */
    private fun sendCommand(ch: Channel, command: String) {
        if (command.isBlank()) return
        val sbCommand = palette.sbId(PacketKey.SB_CHAT_COMMAND) ?: return
        val buf = ch.alloc().buffer()
        buf.writeVarInt(sbCommand)
        buf.writeString(command)
        buf.writeLong(System.currentTimeMillis())
        buf.writeLong(0L)          // salt=0：无签名参数
        buf.writeVarInt(0)         // 参数签名数=0（不解析命令树）
        buf.writeVarInt(0)         // lastSeen messageCount=0
        buf.writeFixedBitSet(BooleanArray(ACKNOWLEDGED_BITS), ACKNOWLEDGED_BITS)
        if (palette.chatHasChecksum) buf.writeByte(0)
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
            Log.i(PROBE_TAG, "LOGIN 收到包 id=0x${packetId.toString(16)} → key=${palette.cbKey(packetId, PacketPhase.LOGIN)}")
            when (palette.cbKey(packetId, PacketPhase.LOGIN)) {
                PacketKey.CB_SET_COMPRESSION -> {
                    val threshold = buf.readVarInt()
                    installCompression(ctx, threshold)
                }
                PacketKey.CB_ENCRYPTION_REQUEST -> doEncryption(ctx, buf)
                PacketKey.CB_LOGIN_SUCCESS -> onLoginSuccess(ctx)
                PacketKey.CB_LOGIN_DISCONNECT -> failWithServerReason(ctx, buf, "被服务器拒绝", componentIsNbt = false)
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
            when (palette.cbKey(packetId, PacketPhase.CONFIGURATION)) {
                PacketKey.CB_CONFIG_DISCONNECT -> failWithServerReason(ctx, buf, "配置阶段被服务器断开", componentIsNbt = palette.chatComponentIsNbt)
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

        /**
         * 进入 PLAY：仅切阶段并置为已连接。
         * 会话公钥（Chat Session Update）不在此处发送——必须等到收到 JoinGame 之后，
         * 对齐 MCC（SendPlayerSession 只在 OnGameJoined 内触发）。过早上报公钥服务器不登记，
         * 导致 chat.disabled.missingProfileKey。
         */
        private fun enterPlay(ctx: ChannelHandlerContext) {
            phase = Phase.PLAY
            _state.value = ConnectionState.Connected(serverBrand = null)
        }

        private fun handlePlay(ctx: ChannelHandlerContext, packetId: Int, buf: ByteBuf) {
            when (palette.cbKey(packetId, PacketPhase.PLAY)) {
                PacketKey.CB_PLAY_DISCONNECT -> failWithServerReason(ctx, buf, "被服务器断开", componentIsNbt = palette.chatComponentIsNbt)
                PacketKey.CB_JOIN_GAME -> {
                    // 收到 play 首包后才上报会话公钥（对齐 MCC OnGameJoined 时序）。
                    // JoinGame 会在重生/换世界时重复到达，用一次性守卫避免重发。
                    if (!chatSessionReported) {
                        chatSessionReported = true
                        sendChatSessionUpdateIfNeeded(ctx)
                    }
                }
                PacketKey.CB_KEEP_ALIVE_PLAY -> {
                    val id = buf.readLong()
                    val sbKa = palette.sbId(PacketKey.SB_KEEP_ALIVE_PLAY) ?: return
                    val ka = ctx.alloc().buffer().writeVarInt(sbKa)
                    ka.writeLong(id)
                    ctx.writeAndFlush(ka)
                }
                PacketKey.CB_SYSTEM_CHAT -> emitSystemChat(buf)
                PacketKey.CB_PLAYER_CHAT -> emitPlayerChat(buf)
                PacketKey.CB_START_CONFIGURATION -> acknowledgeConfiguration(ctx)
                else -> Unit
            }
        }

        /**
         * 代理服/群组服切换子服务器时，服务端会在 PLAY 阶段发 Start Configuration，
         * 客户端必须回 Acknowledge Configuration 并切回 CONFIGURATION；否则服务端会等待超时后断开。
         */
        private fun acknowledgeConfiguration(ctx: ChannelHandlerContext) {
            val sbId = palette.sbId(PacketKey.SB_ACKNOWLEDGE_CONFIGURATION) ?: return
            val ack = ctx.alloc().buffer().writeVarInt(sbId)
            ctx.writeAndFlush(ack)
            chatSessionReported = false
            phase = Phase.CONFIGURATION
        }

        /**
         * session 签名（1.19.3+）必需：进 PLAY 后立即发送 Chat Session Update，
         * 上报玩家公钥 + Mojang 签名 + sessionId + 过期时间。不发则强制签名服拒绝后续聊天。
         *
         * 包结构：UUID sessionId | Long expiresAt | ByteArray publicKey(DER) | ByteArray keySignature
         */
        private fun sendChatSessionUpdateIfNeeded(ctx: ChannelHandlerContext) {
            val cert = certificate
            if (cert == null || !palette.sessionSigning || signer == null) {
                Log.i(PROBE_TAG, "跳过 ChatSessionUpdate：cert=${cert != null} sessionSigning=${palette.sessionSigning} signer=${signer != null}")
                return
            }
            val sbId = palette.sbId(PacketKey.SB_CHAT_SESSION_UPDATE) ?: return
            Log.i(PROBE_TAG, "发送 ChatSessionUpdate id=0x${sbId.toString(16)} expiresAt=${cert.expiresAtEpochMs}(${cert.expiresAtEpochMs - System.currentTimeMillis()}ms后) pubKeyDer=${cert.publicKeyDer.size}B sigV2=${cert.publicKeySignatureV2.size}B")
            val buf = ctx.alloc().buffer()
            buf.writeVarInt(sbId)
            buf.writeUuid(messageChain.sessionId)
            buf.writeLong(cert.expiresAtEpochMs)
            buf.writeByteArray(cert.publicKeyDer)
            buf.writeByteArray(cert.publicKeySignatureV2)
            ctx.writeAndFlush(buf)

            // 重报 session = 新后端签名链起点，index 必须归零（对齐 vanilla 重建 Encoder）。
            // 否则切回原后端时 index 不从 0 起，后端判定乱序/链断裂 → 聊天被禁用。
            messageChain.reset()
        }

        /**
         * 读取服务器断开原因并置为失败。
         * 断开原因组件的编码随阶段不同：LOGIN 的 Login Disconnect 恒为 JSON 字符串；
         * CONFIG/PLAY 的 Disconnect 在 1.20.3+(chatComponentIsNbt) 是网络 NBT 组件。
         * 用错读法会把 NBT 字节读成乱码，吞掉唯一的踢出线索。
         */
        private fun failWithServerReason(
            ctx: ChannelHandlerContext,
            buf: ByteBuf,
            fallback: String,
            componentIsNbt: Boolean
        ) {
            val reason = runCatching {
                if (componentIsNbt) ChatComponent.fromNbt(buf.readNetworkNbt())
                else ChatComponent.toPlainText(buf.readString())
            }.getOrNull()?.ifBlank { null } ?: fallback
            _state.value = ConnectionState.Failed(reason, retriable = false)
            ctx.close()
        }

        /**
         * 读取一个文本组件为富文本片段（版本自适应）。
         *   - chatComponentIsNbt（1.20.3+/765+）→ 网络 NBT 组件
         *   - 否则 → JSON 字符串组件
         * 返回 (spans, raw)；raw 用于插件消费原始组件。
         */
        private fun readComponent(buf: ByteBuf): Pair<List<ChatSpan>, String> =
            if (palette.chatComponentIsNbt) {
                val tag = buf.readNetworkNbt()
                ChatComponent.spansFromNbt(tag) to tag.toString()
            } else {
                val json = buf.readString()
                ChatComponent.toSpans(json) to json
            }

        /**
         * System Chat（服务器广播、命令回显、多数聊天走这里）。
         * 包结构（1.19.1+ 稳定）：内容组件在包首，其后是 overlay 布尔（是否 actionbar），
         * 内容即包首字段，直接读取即精确。
         */
        private fun emitSystemChat(buf: ByteBuf) {
            val (spans, raw) = runCatching { readComponent(buf) }.getOrNull() ?: return
            val plain = spans.joinToString("") { it.text }
            if (plain.isBlank()) return
            _incoming.tryEmit(ChatEvent(plainText = plain, rawJson = raw, spans = spans))
        }

        /**
         * Player Chat（玩家签名聊天，1.19.3+ session 体系）。
         *
         * 内容不在包首：前面有一整套签名头部，须逐字段跳过才能取到真正可读的内容与发送者名。
         * 字段顺序对齐 vanilla ClientboundPlayerChatPacket / MCC：
         *
         *   sender: UUID (16B)
         *   index: VarInt                                    // 消息序号
         *   signature: 可选 256B                             // present 布尔为 true 时存在
         *   ── 以上为 SignedMessageHeader/Body 的签名部分 ──
         *   body.content: String                             // 玩家键入的原始明文（未装饰）
         *   body.timestamp: Long
         *   body.salt: Long
         *   previousMessages: VarInt 计数 + 每条 { id: VarInt; id==0 时附 256B 签名 }
         *   ── 以上为可验证消息体，UI 不需要，仅跳过 ──
         *   unsignedContent: 可选组件                        // 服务器改写后的展示内容（present 布尔）
         *   filterType: VarInt                               // 0=PASS 1=FULLY 2=PARTIALLY(+长整型位组)
         *   ── 以下为展示装饰，UI 需要 ──
         *   chatType: VarInt                                 // 注册表引用（holder，服务器恒为引用）
         *   senderName: 组件                                 // 发送者显示名（带前缀/颜色）
         *   targetName: 可选组件                             // 私聊等定向目标名
         *
         * 展示对齐 vanilla：有 unsignedContent 用它，否则用明文 body.content，
         * 再套 chat.type.text 装饰「<发送者名> 内容」。装饰模板无法从注册表取，
         * 但服务器聊天绝大多数即此格式，直接构造等价片段。
         */
        private fun emitPlayerChat(buf: ByteBuf) {
            val parsed = runCatching {
                buf.skipBytes(16)                                // sender UUID
                buf.readVarInt()                                 // index
                if (buf.readBoolean()) buf.skipBytes(256)        // 签名（present 时定长 256B）

                val bodyContent = buf.readString()               // 明文正文
                buf.readLong()                                   // timestamp
                buf.readLong()                                   // salt
                skipPreviousMessages(buf)                        // 历史消息引用列表

                val unsigned = if (buf.readBoolean()) readComponent(buf) else null
                skipFilterMask(buf)                              // filter type (+partial 位组)

                buf.readVarInt()                                 // chatType holder（引用 id）
                val (senderSpans, _) = readComponent(buf)        // 发送者显示名
                if (buf.readBoolean()) readComponent(buf)        // targetName（忽略）

                val (contentSpans, contentRaw) = unsigned
                    ?: (ChatComponent.toSpans(bodyContent) to bodyContent)
                Triple(senderSpans, contentSpans, contentRaw)
            }.getOrNull() ?: return

            val (senderSpans, contentSpans, contentRaw) = parsed
            val senderName = senderSpans.joinToString("") { it.text }
            val spans = decorateChat(senderSpans, contentSpans)
            val plain = spans.joinToString("") { it.text }
            if (plain.isBlank()) return
            _incoming.tryEmit(
                ChatEvent(
                    plainText = plain,
                    rawJson = contentRaw,
                    sender = senderName.ifBlank { null },
                    spans = spans
                )
            )
        }

        /** previousMessages：VarInt 计数；每项 id VarInt，id==0（新签名）时附带 256B 签名。 */
        private fun skipPreviousMessages(buf: ByteBuf) {
            val count = buf.readVarInt()
            repeat(count) {
                val id = buf.readVarInt()
                if (id == 0) buf.skipBytes(256)
            }
        }

        /** filter type：0=PASS_THROUGH 1=FULLY_FILTERED 2=PARTIALLY_FILTERED（附 long 数组位组）。 */
        private fun skipFilterMask(buf: ByteBuf) {
            if (buf.readVarInt() == 2) {
                val longs = buf.readVarInt()
                buf.skipBytes(longs * 8)
            }
        }

        /** 「<发送者> 内容」装饰：对齐 vanilla chat.type.text 默认模板，保留双方各自样式。 */
        private fun decorateChat(sender: List<ChatSpan>, content: List<ChatSpan>): List<ChatSpan> =
            buildList {
                add(ChatSpan("<"))
                addAll(sender)
                add(ChatSpan("> "))
                addAll(content)
            }

        private fun doEncryption(ctx: ChannelHandlerContext, buf: ByteBuf) {
            val serverId = buf.readString()
            val pubKeyBytes = buf.readByteArray()
            val verifyToken = buf.readByteArray()

            val secret = McCrypto.generateSharedSecret()
            val pubKey = McCrypto.decodePublicKey(pubKeyBytes)

            // 向 Mojang sessionserver 报到，证明身份
            val hash = McCrypto.serverHash(serverId, secret, pubKey)
            val joinStart = System.currentTimeMillis()
            Log.i(PROBE_TAG, "收到 Encryption Request，开始 joinServer（sessionserver.mojang.com）…")
            val joined = joinServer(hash)
            Log.i(PROBE_TAG, "joinServer 返回 $joined，耗时 ${System.currentTimeMillis() - joinStart}ms")
            if (!joined) {
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
            Log.i(PROBE_TAG, "已发送 Encryption Response，等待 Login Success…")

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
        // 登录链路运行时探针 tag（真机诊断用；上层日志走 BilicraftMC）
        const val PROBE_TAG = "BilicraftMC-Probe"
    }
}