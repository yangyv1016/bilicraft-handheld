package com.bilicraft.handheld.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatHoverTest {
    @Test
    fun `item components with decimal numbers retain hover data`() {
        val raw = """{"text":"custom","hover_event":{"action":"show_item","id":"minecraft:paper","components":{"minecraft:custom_model_data":{"floats":[1.5]}}}}"""
        val item = (ChatComponent.toSpans(raw).single().hover as ChatHover.Item).item
        val model = item.components.entries["minecraft:custom_model_data"] as NbtTag.NbtCompound
        assertEquals(NbtTag.NbtList(listOf(NbtTag.NbtDouble(1.5))), model.entries["floats"])
    }

    @Test
    fun `extra siblings do not inherit hover from each other`() {
        val raw = """{"text":"","extra":[{"text":"item","hover_event":{"action":"show_text","value":"hint"}},{"text":" plain"}]}"""
        for (spans in listOf(ChatComponent.toSpans(raw), ChatComponent.spansFromNbt(Nbt.fromJson(JSONObject(raw))))) {
            assertNotNull(spans.first().hover)
            assertNull(spans.last().hover)
        }
    }

    @Test
    fun `component arrays inherit their first component style`() {
        val raw = """[{"text":"A","font":"server:icons","hover_event":{"action":"show_text","value":"hint"}},"B",{"text":"C"}]"""
        val json = org.json.JSONArray(raw)
        for (spans in listOf(ChatComponent.toSpans(raw), ChatComponent.spansFromNbt(Nbt.fromJson(json)))) {
            assertEquals("ABC", spans.joinToString("") { it.text })
            assertTrue(spans.all { it.font == "server:icons" && it.hover == spans.first().hover })
        }
    }

    @Test
    fun `font and hover inherit through extras with explicit overrides`() {
        val raw = """{"text":"A","font":"server:icons","hover_event":{"action":"show_text","value":"parent"},"extra":[{"text":"B","bold":true},{"text":"C","font":"minecraft:default","hover_event":{"action":"show_text","value":"child"}},{"text":"D","hover_event":null}]}"""
        for (spans in listOf(ChatComponent.toSpans(raw), ChatComponent.spansFromNbt(Nbt.fromJson(JSONObject(raw))))) {
            assertEquals("ABCD", spans.joinToString("") { it.text })
            assertEquals("server:icons", spans[1].font)
            assertEquals(spans[0].hover, spans[1].hover)
            assertEquals("minecraft:default", spans[2].font)
            assertEquals("child", (spans[2].hover as ChatHover.Text).spans.single().text)
            assertNull(spans[3].hover)
        }
    }

    @Test
    fun `two adjacent items retain independent metadata and never become commands`() {
        val raw = """[{"text":"sword","click_event":{"action":"run_command","command":"/danger"},"hover_event":{"action":"show_item","id":"minecraft:diamond_sword","components":{"minecraft:damage":42,"minecraft:custom_name":{"text":"name","font":"server:icons"}}}},{"text":"apple","hover_event":{"action":"show_item","id":"minecraft:apple","count":12}}]"""
        val spans = ChatComponent.toSpans(raw)
        val first = (spans[0].hover as ChatHover.Item).item
        val second = (spans[1].hover as ChatHover.Item).item
        assertEquals("minecraft:diamond_sword", first.id)
        assertEquals(1, first.count)
        assertEquals("server:icons", first.displayName.single().font)
        assertEquals(NbtTag.NbtInt(42), first.components.entries["minecraft:damage"])
        assertEquals("minecraft:apple", second.id)
        assertEquals(12, second.count)
    }

    @Test
    fun `translation arguments and legacy formatting preserve hover ranges`() {
        val raw = """{"translate":"chat.type.text","with":["Player",{"text":"§ahello §lworld","hover_event":{"action":"show_text","value":"details"}}]}"""
        val spans = ChatComponent.toSpans(raw)
        assertEquals("<Player> hello world", spans.joinToString("") { it.text })
        assertNull(spans.first().hover)
        assertEquals(2, spans.count { it.hover != null })
        assertTrue(spans.last().bold)
    }

    @Test
    fun `malformed or unsupported hover preserves visible message`() {
        for (hover in listOf("null", "{}", """{"action":"show_item"}""", """{"action":"show_item","id":"minecraft:apple","count":0}""", """{"action":"show_entity"}""")) {
            val spans = ChatComponent.toSpans("""{"text":"still visible","hover_event":$hover}""")
            assertEquals("still visible", spans.single().text)
            assertNull(spans.single().hover)
        }
    }
}
