package com.bilicraft.handheld.protocol

import com.bilicraft.handheld.protocol.McTypes.readFixedBitSet
import com.bilicraft.handheld.protocol.McTypes.writeFixedBitSet
import com.bilicraft.handheld.protocol.McTypes.writeString
import com.bilicraft.handheld.protocol.McTypes.writeVarInt
import com.bilicraft.handheld.protocol.Nbt.readNetworkNbt
import io.netty.buffer.Unpooled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议原语与 palette 的纯 JVM 单测（不依赖 Android）。
 * 覆盖：Fixed BitSet 编解码、网络 NBT 读取、palette 双向映射、聊天组件提取。
 */
class ProtocolPrimitivesTest {

    // ---- Fixed BitSet：20 位 → 3 字节，位序 (byte i/8, bit i%8) ----

    @Test
    fun `20位BitSet序列化为3字节`() {
        val buf = Unpooled.buffer()
        buf.writeFixedBitSet(BooleanArray(20), 20)
        assertEquals(3, buf.readableBytes())
    }

    @Test
    fun `BitSet置位后可原样读回`() {
        val bits = BooleanArray(20).also { it[0] = true; it[7] = true; it[19] = true }
        val buf = Unpooled.buffer()
        buf.writeFixedBitSet(bits, 20)
        val back = buf.readFixedBitSet(20)
        assertTrue(back[0]); assertTrue(back[7]); assertTrue(back[19])
        assertTrue(!back[1] && !back[10])
    }

    // ---- 网络 NBT：根标签无名字 ----

    @Test
    fun `网络NBT读取无名根String组件`() {
        // TAG_String(8) + unsigned short 长度 + UTF-8 内容
        val text = "hello"
        val buf = Unpooled.buffer()
        buf.writeByte(8)
        buf.writeShort(text.toByteArray(Charsets.UTF_8).size)
        buf.writeBytes(text.toByteArray(Charsets.UTF_8))
        val tag = buf.readNetworkNbt()
        assertEquals("hello", ChatComponent.fromNbt(tag))
    }

    @Test
    fun `网络NBT读取Compound含text与extra`() {
        // { text:"a", extra:[ {text:"b"} ] } → 提取 "ab"
        val buf = Unpooled.buffer()
        buf.writeByte(10)                 // 根 TAG_Compound（无名）
        // text:"a"
        buf.writeByte(8); writeNbtName(buf, "text"); writeNbtStr(buf, "a")
        // extra: TAG_List of Compound，长度 1
        buf.writeByte(9); writeNbtName(buf, "extra")
        buf.writeByte(10); buf.writeInt(1)   // 元素类型 Compound，1 个
        buf.writeByte(8); writeNbtName(buf, "text"); writeNbtStr(buf, "b")
        buf.writeByte(0)                     // 内层 compound 结束
        buf.writeByte(0)                     // 根 compound 结束
        val tag = buf.readNetworkNbt()
        assertEquals("ab", ChatComponent.fromNbt(tag))
    }

    @Test
    fun `死亡消息中的实体名来自完整翻译表`() {
        MinecraftTranslations.loadJson("""
            {
              "death.attack.explosion.player": "%1${'$'}s被%2${'$'}s炸死了",
              "death.attack.mob": "%1${'$'}s被%2${'$'}s杀死了",
              "entity.minecraft.creeper": "苦力怕",
              "entity.minecraft.zombie": "僵尸"
            }
        """.trimIndent())

        val creeperDeathJson = """{"translate":"death.attack.explosion.player","with":[{"text":"xxx"},{"translate":"entity.minecraft.creeper"}]}"""
        assertEquals("xxx被苦力怕炸死了", ChatComponent.toPlainText(creeperDeathJson))

        val zombieDeathTag = NbtTag.NbtCompound(mapOf(
            "translate" to NbtTag.NbtString("death.attack.mob"),
            "with" to NbtTag.NbtList(listOf(
                NbtTag.NbtCompound(mapOf("text" to NbtTag.NbtString("xxx"))),
                NbtTag.NbtCompound(mapOf("translate" to NbtTag.NbtString("entity.minecraft.zombie")))
            ))
        ))
        assertEquals("xxx被僵尸杀死了", ChatComponent.fromNbt(zombieDeathTag))
    }

    @Test
    fun `未知翻译key优先使用组件fallback`() {
        val raw = """{"translate":"mod.example.unknown_entity","fallback":"神秘生物","with":[{"text":"ignored"}]}"""
        assertEquals("神秘生物", ChatComponent.toPlainText(raw))
    }

    // ---- palette：双向映射与版本特性 ----

    @Test
    fun `1_21_palette聊天包双向映射一致`() {
        val palette = PaletteRegistry.forProtocol(767)
        assertTrue(palette.hasConfigPhase)
        assertTrue(palette.chatComponentIsNbt)
        assertTrue(palette.sessionSigning)
        val sysId = requireNotNull(reverseLookup(palette, PacketKey.CB_SYSTEM_CHAT))
        assertEquals(PacketKey.CB_SYSTEM_CHAT, palette.cbKey(sysId, PacketPhase.PLAY))
    }

    @Test
    fun `1_21_11_palette聊天包id对齐MCC权威表`() {
        // 协议 774 = 1.21.11，MCC 复用 Palette1219；值取自 PacketPalette1219.cs
        val palette = PaletteRegistry.forProtocol(774)
        assertEquals(0x08, palette.sbId(PacketKey.SB_CHAT_MESSAGE))
        assertEquals(0x1B, palette.sbId(PacketKey.SB_KEEP_ALIVE_PLAY))
        assertEquals(0x09, palette.sbId(PacketKey.SB_CHAT_SESSION_UPDATE))
        assertEquals(PacketKey.CB_SYSTEM_CHAT, palette.cbKey(0x77, PacketPhase.PLAY))
        assertEquals(PacketKey.CB_PLAYER_CHAT, palette.cbKey(0x3F, PacketPhase.PLAY))
        assertEquals(PacketKey.CB_PLAY_DISCONNECT, palette.cbKey(0x20, PacketPhase.PLAY))
        assertEquals(PacketKey.CB_KEEP_ALIVE_PLAY, palette.cbKey(0x2B, PacketPhase.PLAY))
    }

    @Test
    fun `26_x_palette聊天包id对齐MCC权威表`() {
        // 协议 775/776 = 26.1/26.2，MCC 用 Palette261（26.2 沿用 26.1）；值取自 PacketPalette261.cs
        for (protocol in intArrayOf(775, 776)) {
            val palette = PaletteRegistry.forProtocol(protocol)
            assertEquals(0x09, palette.sbId(PacketKey.SB_CHAT_MESSAGE))
            assertEquals(0x1C, palette.sbId(PacketKey.SB_KEEP_ALIVE_PLAY))
            assertEquals(0x0A, palette.sbId(PacketKey.SB_CHAT_SESSION_UPDATE))
            assertEquals(PacketKey.CB_SYSTEM_CHAT, palette.cbKey(0x79, PacketPhase.PLAY))
            assertEquals(PacketKey.CB_PLAYER_CHAT, palette.cbKey(0x41, PacketPhase.PLAY))
            assertEquals(PacketKey.CB_PLAY_DISCONNECT, palette.cbKey(0x20, PacketPhase.PLAY))
            assertEquals(PacketKey.CB_KEEP_ALIVE_PLAY, palette.cbKey(0x2C, PacketPhase.PLAY))
        }
    }

    @Test
    fun `1_21与1_21_11聊天id确实不同`() {
        // 回归护栏：证明 play id 逐版本浮动，禁止再退回「所有版本共用一套」
        val v767 = PaletteRegistry.forProtocol(767)
        val v774 = PaletteRegistry.forProtocol(774)
        assertEquals(0x6C, reverseLookup(v767, PacketKey.CB_SYSTEM_CHAT))
        assertEquals(0x77, reverseLookup(v774, PacketKey.CB_SYSTEM_CHAT))
        assertEquals(0x07, v767.sbId(PacketKey.SB_CHAT_SESSION_UPDATE))
        assertEquals(0x09, v774.sbId(PacketKey.SB_CHAT_SESSION_UPDATE))
    }

    @Test
    fun `legacy无config阶段且组件为JSON`() {
        val palette = PaletteRegistry.forProtocol(578)   // 1.15.2
        assertTrue(!palette.hasConfigPhase)
        assertTrue(!palette.chatComponentIsNbt)
        assertNull(palette.sbId(PacketKey.SB_LOGIN_ACK))
    }

    // ---- 命令补全：服务器响应解析与输入替换 ----

    @Test
    fun `命令补全响应解析候选和tooltip`() {
        val buf = Unpooled.buffer()
        buf.writeVarInt(7)
        buf.writeVarInt(1)
        buf.writeVarInt(2)
        buf.writeVarInt(2)
        buf.writeString("gamemode")
        buf.writeBoolean(false)
        buf.writeString("give")
        buf.writeBoolean(true)
        buf.writeString("{\"text\":\"给予物品\"}")

        val state = CommandSuggestions.readResponse(buf, componentIsNbt = false, requestInput = "/gi")

        assertEquals(7, state.requestId)
        assertEquals("/gi", state.requestInput)
        assertEquals(1, state.start)
        assertEquals(2, state.length)
        assertEquals("gamemode", state.suggestions[0].text)
        assertNull(state.suggestions[0].tooltip)
        assertEquals("give", state.suggestions[1].text)
        assertEquals("给予物品", state.suggestions[1].tooltip)
    }

    @Test
    fun `命令补全按服务器范围替换输入片段`() {
        assertEquals("/gamemode creative", CommandSuggestions.apply("/game creative", 1, 4, "gamemode"))
        assertEquals("/tp Steve", CommandSuggestions.apply("/t Steve", 1, 1, "tp"))
        assertEquals("/help", CommandSuggestions.apply("/he", 1, 2, "help"))
    }

    @Test
    fun `1_21命令补全包id对齐MCC权威表`() {
        val palette = PaletteRegistry.forProtocol(767)
        assertEquals(0x0B, palette.sbId(PacketKey.SB_COMMAND_SUGGESTION))
        assertEquals(PacketKey.CB_COMMAND_SUGGESTIONS, palette.cbKey(0x10, PacketPhase.PLAY))
        assertEquals(PacketKey.CB_DECLARE_COMMANDS, palette.cbKey(0x11, PacketPhase.PLAY))
    }

    @Test
    fun `1_21_11命令补全包id对齐MCC权威表`() {
        val palette = PaletteRegistry.forProtocol(774)
        assertEquals(0x0E, palette.sbId(PacketKey.SB_COMMAND_SUGGESTION))
        assertEquals(PacketKey.CB_COMMAND_SUGGESTIONS, palette.cbKey(0x0F, PacketPhase.PLAY))
        assertEquals(PacketKey.CB_DECLARE_COMMANDS, palette.cbKey(0x10, PacketPhase.PLAY))
    }

    @Test
    fun `未精确登记的1_20_x不启用命令补全包`() {
        val palette = PaletteRegistry.forProtocol(766)
        assertNull(palette.sbId(PacketKey.SB_COMMAND_SUGGESTION))
        assertNull(palette.cbKey(0x10, PacketPhase.PLAY))
        assertNull(palette.cbKey(0x11, PacketPhase.PLAY))
    }

    // ---- 辅助 ----

    private fun writeNbtName(buf: io.netty.buffer.ByteBuf, name: String) {
        val b = name.toByteArray(Charsets.UTF_8); buf.writeShort(b.size); buf.writeBytes(b)
    }

    private fun writeNbtStr(buf: io.netty.buffer.ByteBuf, value: String) {
        val b = value.toByteArray(Charsets.UTF_8); buf.writeShort(b.size); buf.writeBytes(b)
    }

    /** 通过遍历已知 id 空间反查某 cb 逻辑包的 id（测试辅助，避免暴露内部 map） */
    private fun reverseLookup(palette: PacketPalette, key: PacketKey): Int? =
        (0..0xFF).firstOrNull { palette.cbKey(it, key.phase) == key }
}