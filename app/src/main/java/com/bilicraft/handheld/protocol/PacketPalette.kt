package com.bilicraft.handheld.protocol

/**
 * 版本差异收敛层（palette）。取代旧的 PacketProfile「阈值二选一 + 集合猜包」。
 *
 * 设计（对齐 MCC 的 PacketTypePalette 思路）：
 *   逻辑包 PacketKey  ──映射──►  某协议号下的数字 id
 * 状态机只认 PacketKey，数字 id 全部封在这里。换版本 = 换一张映射，不改状态机。
 *
 * 覆盖边界与可信度（务实、不伪装）：
 *   - HANDSHAKE / LOGIN / CONFIGURATION 阶段的包 id 跨 1.20.2–1.21.x 高度稳定，
 *     这里的值可信度高。
 *   - PLAY 阶段的聊天/系统消息 id 每个版本都可能浮动，是历史上最易错位的部分。
 *     因此 PLAY 聊天相关 id 用 per-version 覆盖槽显式声明，并标注需对真实服务器校准。
 *
 * 未在 registry 精确登记的协议号：fallback 到最近的已登记基线，宁可少覆盖也不猜。
 */

/**
 * 协议阶段。MC 的包 id 是 per-phase 命名空间：同一个数字在不同阶段是不同的包
 * （例如 clientbound 0x03 在 LOGIN 是 Set Compression、在 CONFIGURATION 是 Finish Config）。
 * 因此反向解析（id → 逻辑包）必须带阶段，否则扁平表会撞车。
 */
enum class PacketPhase { LOGIN, CONFIGURATION, PLAY }

/**
 * 逻辑包名。只覆盖「聊天核心链路」需要的包，不追求全包。
 * 每个包声明自己归属的协议阶段，供反向表按阶段分桶，从根上消除跨阶段 id 撞车。
 */
enum class PacketKey(val phase: PacketPhase) {
    // --- serverbound ---
    SB_LOGIN_START(PacketPhase.LOGIN),
    SB_ENCRYPTION_RESPONSE(PacketPhase.LOGIN),
    SB_LOGIN_ACK(PacketPhase.LOGIN),                    // 1.20.2+ 登录完成确认（进入 configuration）
    SB_CONFIG_KNOWN_PACKS(PacketPhase.CONFIGURATION),   // 1.20.5+/协议766+ 回应服务器 Known Packs
    SB_CONFIG_FINISH_ACK(PacketPhase.CONFIGURATION),    // 确认 configuration 结束（进入 play）
    SB_CONFIG_KEEP_ALIVE(PacketPhase.CONFIGURATION),
    SB_KEEP_ALIVE_PLAY(PacketPhase.PLAY),
    SB_CHAT_MESSAGE(PacketPhase.PLAY),                  // play 阶段发送聊天
    SB_CHAT_COMMAND(PacketPhase.PLAY),                  // 1.19+ 斜杠命令走独立包（非 Chat Message）
    SB_CHAT_SESSION_UPDATE(PacketPhase.PLAY),           // 1.19.3+ session 体系：上报玩家公钥/会话

    // --- clientbound ---
    CB_ENCRYPTION_REQUEST(PacketPhase.LOGIN),
    CB_LOGIN_SUCCESS(PacketPhase.LOGIN),
    CB_SET_COMPRESSION(PacketPhase.LOGIN),
    CB_LOGIN_DISCONNECT(PacketPhase.LOGIN),
    CB_CONFIG_KNOWN_PACKS(PacketPhase.CONFIGURATION),   // 1.20.5+/协议766+ 服务器询问 Known Packs
    CB_CONFIG_FINISH(PacketPhase.CONFIGURATION),
    CB_CONFIG_KEEP_ALIVE(PacketPhase.CONFIGURATION),
    CB_CONFIG_DISCONNECT(PacketPhase.CONFIGURATION),
    CB_PLAY_DISCONNECT(PacketPhase.PLAY),
    CB_JOIN_GAME(PacketPhase.PLAY),                     // play 首包 Login(play)：会话公钥须在此之后上报
    CB_KEEP_ALIVE_PLAY(PacketPhase.PLAY),
    CB_SYSTEM_CHAT(PacketPhase.PLAY),                   // 系统消息（服务器广播、命令回显等，多数聊天走这里）
    CB_PLAYER_CHAT(PacketPhase.PLAY),                   // 玩家签名聊天（结构复杂，这里只提取可读文本）
}

/**
 * 一个协议号下的精确包映射。
 * sb: 逻辑包 → 发送 id；cb: 接收 id → 逻辑包（双向查询）。
 * cb 反向表在构造时由 sb/cb 声明生成，避免手写两遍。
 */
data class PacketPalette(
    val protocol: Int,
    val hasConfigPhase: Boolean,
    val chatComponentIsNbt: Boolean,   // 1.20.3(765)+ 聊天组件走网络 NBT
    val sessionSigning: Boolean,       // 1.19.3(761)+ session 体系签名
    val chatHasChecksum: Boolean,      // 1.21.5(770)+ serverbound chat 尾部多一个 checksum 字节
    private val sbMap: Map<PacketKey, Int>,
    private val cbMap: Map<PacketKey, Int>
) {
    // 反向表按阶段分桶：同一数字 id 在不同阶段是不同包（如 cb 0x03 = LOGIN:Set Compression / CONFIG:Finish），
    // 扁平表会因 associate 写覆盖而误判。分桶后每个阶段各自独立，从根上消除跨阶段撞车。
    private val cbReverseByPhase: Map<PacketPhase, Map<Int, PacketKey>> =
        cbMap.entries
            .groupBy { it.key.phase }
            .mapValues { (_, entries) -> entries.associate { (k, v) -> v to k } }

    /** 逻辑包 → 发送 id。未登记的包返回 null（调用方据此决定是否发送）。 */
    fun sbId(key: PacketKey): Int? = sbMap[key]

    /** 接收 id → 逻辑包，按当前连接阶段解析。未识别返回 null（忽略该包）。 */
    fun cbKey(id: Int, phase: PacketPhase): PacketKey? = cbReverseByPhase[phase]?.get(id)
}

/**
 * palette 注册表。以「基线 + 版本覆盖」声明，forProtocol 返回精确档案。
 *
 * 精确登记 1.21→26.2 全段（协议 767–776）。它们共享同一套 login/config 映射；
 * PLAY 聊天 id 按 MCC 权威表逐段声明（767 / 768-769 / 770 / 771-772 / 773-774 / 775-776）。
 * 26.x 是 Mojang 2026 起的新版本号方案（26.1=775, 26.2=776）。
 * 老版本走 legacy 基线，明确标注未逐版校准。
 */
object PaletteRegistry {

    fun forProtocol(protocol: Int): PacketPalette = when {
        protocol >= 764 -> modern(protocol)   // 1.20.2+：有 configuration 阶段
        else -> legacy(protocol)              // 1.13–1.20.1
    }

    /**
     * 现代基线（1.20.2+，协议 764 起）。
     * login/config id 可信度高；play 聊天 id 见下方常量，需按版本校准。
     */
    private fun modern(protocol: Int): PacketPalette {
        val play = modernPlayChatIds(protocol)
        return PacketPalette(
            protocol = protocol,
            hasConfigPhase = true,
            chatComponentIsNbt = protocol >= 765,   // 1.20.3+
            sessionSigning = protocol >= 761,        // 恒 true（modern 起点 764 > 761）
            chatHasChecksum = protocol >= 770,       // 1.21.5+
            sbMap = mapOf(
                PacketKey.SB_LOGIN_START to 0x00,
                PacketKey.SB_ENCRYPTION_RESPONSE to 0x01,
                PacketKey.SB_LOGIN_ACK to 0x03,
                // Known Packs 仅 1.20.5+/766+ 存在
                *(if (protocol >= 766) arrayOf(PacketKey.SB_CONFIG_KNOWN_PACKS to 0x07) else emptyArray()),
                PacketKey.SB_CONFIG_FINISH_ACK to 0x03,
                PacketKey.SB_CONFIG_KEEP_ALIVE to 0x04,
                PacketKey.SB_KEEP_ALIVE_PLAY to play.sbKeepAlive,
                PacketKey.SB_CHAT_MESSAGE to play.sbChatMessage,
                PacketKey.SB_CHAT_COMMAND to play.sbChatCommand,
                PacketKey.SB_CHAT_SESSION_UPDATE to play.sbChatSessionUpdate,
            ),
            cbMap = mapOf(
                PacketKey.CB_ENCRYPTION_REQUEST to 0x01,
                PacketKey.CB_LOGIN_SUCCESS to 0x02,
                PacketKey.CB_SET_COMPRESSION to 0x03,
                PacketKey.CB_LOGIN_DISCONNECT to 0x00,
                *(if (protocol >= 766) arrayOf(PacketKey.CB_CONFIG_KNOWN_PACKS to 0x0E) else emptyArray()),
                PacketKey.CB_CONFIG_FINISH to 0x03,
                PacketKey.CB_CONFIG_KEEP_ALIVE to 0x04,
                PacketKey.CB_CONFIG_DISCONNECT to 0x02,
                PacketKey.CB_PLAY_DISCONNECT to play.cbDisconnect,
                PacketKey.CB_JOIN_GAME to play.cbJoinGame,
                PacketKey.CB_KEEP_ALIVE_PLAY to play.cbKeepAlive,
                PacketKey.CB_SYSTEM_CHAT to play.cbSystemChat,
                PacketKey.CB_PLAYER_CHAT to play.cbPlayerChat,
            )
        )
    }

    /**
     * PLAY 阶段版本敏感 id 集合。
     * 取值来源：MCCTeam/Minecraft-Console-Client 的 PacketPalette*.cs（权威逐版本表）。
     * 分段边界对齐 MCC PacketType18Handler.cs 的 palette 分派逻辑。
     */
    private data class PlayChatIds(
        val sbChatMessage: Int,
        val sbChatCommand: Int,          // SignedChatCommand（在线模式 1.20.6+）/ ChatCommand
        val sbKeepAlive: Int,
        val sbChatSessionUpdate: Int,
        val cbDisconnect: Int,
        val cbKeepAlive: Int,
        val cbSystemChat: Int,
        val cbPlayerChat: Int,
        val cbJoinGame: Int,
    )

    private fun modernPlayChatIds(protocol: Int): PlayChatIds = when {
        // 775–776：26.1 / 26.2（MCC Palette261，26.2 沿用 26.1 的包 id）
        protocol >= 775 -> PlayChatIds(
            sbChatMessage = 0x09,
            sbChatCommand = 0x08,
            sbKeepAlive = 0x1C,
            sbChatSessionUpdate = 0x0A,
            cbDisconnect = 0x20,
            cbKeepAlive = 0x2C,
            cbSystemChat = 0x79,
            cbPlayerChat = 0x41,
            cbJoinGame = 0x31,
        )
        // 773–774：1.21.9 / 1.21.10 / 1.21.11（MCC Palette1219）
        protocol >= 773 -> PlayChatIds(
            sbChatMessage = 0x08,
            sbChatCommand = 0x07,
            sbKeepAlive = 0x1B,
            sbChatSessionUpdate = 0x09,
            cbDisconnect = 0x20,
            cbKeepAlive = 0x2B,
            cbSystemChat = 0x77,
            cbPlayerChat = 0x3F,
            cbJoinGame = 0x30,
        )
        // 771–772：1.21.6 / 1.21.7 / 1.21.8（MCC Palette1216）
        protocol >= 771 -> PlayChatIds(
            sbChatMessage = 0x08,
            sbChatCommand = 0x07,
            sbKeepAlive = 0x1B,
            sbChatSessionUpdate = 0x09,
            cbDisconnect = 0x1C,
            cbKeepAlive = 0x26,
            cbSystemChat = 0x72,
            cbPlayerChat = 0x3A,
            cbJoinGame = 0x2B,
        )
        // 770：1.21.5（MCC Palette1215）
        protocol >= 770 -> PlayChatIds(
            sbChatMessage = 0x07,
            sbChatCommand = 0x06,
            sbKeepAlive = 0x1A,
            sbChatSessionUpdate = 0x08,
            cbDisconnect = 0x1C,
            cbKeepAlive = 0x26,
            cbSystemChat = 0x72,
            cbPlayerChat = 0x3A,
            cbJoinGame = 0x2B,
        )
        // 768–769：1.21.2 / 1.21.3 / 1.21.4（MCC Palette1212 与 Palette1214，聊天链路 id 一致）
        protocol >= 768 -> PlayChatIds(
            sbChatMessage = 0x07,
            sbChatCommand = 0x06,
            sbKeepAlive = 0x1A,
            sbChatSessionUpdate = 0x08,
            cbDisconnect = 0x1D,
            cbKeepAlive = 0x27,
            cbSystemChat = 0x73,
            cbPlayerChat = 0x3B,
            cbJoinGame = 0x2C,
        )
        // 767：1.21 / 1.21.1（MCC Palette121）。
        // 注意：764–766（1.20.2/1.20.3/1.20.5/1.20.6，MCC Palette1202/1204/1206）也会落到此分支，
        // 但其 play id 与 767 不同，未逐版校准；本项目当前只面向 1.21+，如需支持需另行补段。
        else -> PlayChatIds(
            sbChatMessage = 0x06,
            sbChatCommand = 0x05,
            sbKeepAlive = 0x18,
            sbChatSessionUpdate = 0x07,
            cbDisconnect = 0x1D,
            cbKeepAlive = 0x26,
            cbSystemChat = 0x6C,
            cbPlayerChat = 0x39,
            cbJoinGame = 0x2B,
        )
    }

    /**
     * 传统基线（1.13–1.20.1，无 configuration 阶段）。
     * 【未逐版校准】仅提供一份代表映射，聊天 id 跨版本浮动大，建议配合「自动识别」使用。
     */
    private fun legacy(protocol: Int): PacketPalette = PacketPalette(
        protocol = protocol,
        hasConfigPhase = false,
        chatComponentIsNbt = false,          // 老版本聊天组件是 JSON 字符串
        sessionSigning = protocol >= 761,    // legacy 上限 763，1.19.3 也走 session
        chatHasChecksum = false,             // legacy 上限 763 < 770，无 checksum
        sbMap = mapOf(
            PacketKey.SB_LOGIN_START to 0x00,
            PacketKey.SB_ENCRYPTION_RESPONSE to 0x01,
            PacketKey.SB_KEEP_ALIVE_PLAY to 0x12,
            PacketKey.SB_CHAT_MESSAGE to 0x05,
        ),
        cbMap = mapOf(
            PacketKey.CB_ENCRYPTION_REQUEST to 0x01,
            PacketKey.CB_LOGIN_SUCCESS to 0x02,
            PacketKey.CB_SET_COMPRESSION to 0x03,
            PacketKey.CB_LOGIN_DISCONNECT to 0x00,
            PacketKey.CB_PLAY_DISCONNECT to 0x1A,
            PacketKey.CB_KEEP_ALIVE_PLAY to 0x21,
            PacketKey.CB_SYSTEM_CHAT to 0x62,
            PacketKey.CB_PLAYER_CHAT to 0x35,
        )
    )
}