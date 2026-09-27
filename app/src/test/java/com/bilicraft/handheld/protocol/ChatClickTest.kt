package com.bilicraft.handheld.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatClickTest {
    private fun both(raw: String): List<List<ChatSpan>> = listOf(
        ChatComponent.toSpans(raw),
        ChatComponent.spansFromNbt(Nbt.fromJson(if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)))
    )

    @Test fun `modern and legacy commands use command channel even without slash`() {
        for (event in listOf(
            """"click_event":{"action":"run_command","command":"signin gui"}""",
            """"clickEvent":{"action":"run_command","value":"/signin gui"}"""
        )) for (spans in both("""{"text":"签到",$event}""")) {
            assertEquals(ChatClick.RunCommand("/signin gui"), spans.single().click)
        }
    }

    @Test fun `suggestions preserve input including empty text and do not become run actions`() {
        for (command in listOf("/msg Player ", "hello", "")) {
            val raw = JSONObject().put("text", "建议").put("click_event",
                JSONObject().put("action", "suggest_command").put("command", command)).toString()
            for (spans in both(raw)) assertEquals(ChatClick.SuggestCommand(command), spans.single().click)
        }
    }

    @Test fun `extras inherit parent events but not sibling events`() {
        val raw = """{"text":"A","click_event":{"action":"run_command","command":"menu"},"extra":[{"text":"B","click_event":{"action":"suggest_command","command":"/help "}},{"text":"§aC§lD"},{"text":"E","click_event":null},{"text":"F","click_event":{"action":"open_file","value":"file"}}]}"""
        for (spans in both(raw)) {
            assertEquals(listOf(ChatClick.RunCommand("/menu"), ChatClick.SuggestCommand("/help "),
                ChatClick.RunCommand("/menu"), ChatClick.RunCommand("/menu"), null, null), spans.map { it.click })
        }
        for (spans in both("""{"text":"","extra":[{"text":"A","clickEvent":{"action":"run_command","value":"/menu"}},{"text":"B"}]}""")) {
            assertNotNull(spans[0].click)
            assertNull(spans[1].click)
        }
    }

    @Test fun `array event inheritance coexists with hover and font`() {
        val raw = """[{"text":"A","font":"server:icons","hover_event":{"action":"show_text","value":"hint"},"click_event":{"action":"run_command","command":"menu"}},"B"]"""
        for (spans in both(raw)) {
            assertTrue(spans.all { it.click == ChatClick.RunCommand("/menu") && it.hover != null && it.font == "server:icons" })
        }
    }

    @Test fun `invalid network commands are not executable`() {
        for (command in listOf("", "/", "   ", "menu\nhelp", "menu\r", "menu\u0000", "menu\u007f", "§amenu")) {
            val raw = JSONObject().put("text", "invalid").put("click_event",
                JSONObject().put("action", "run_command").put("command", command)).toString()
            for (spans in both(raw)) assertNull(spans.single().click)
        }
        for (spans in both("""{"text":"bad type","click_event":{"action":"run_command","command":42}}""")) {
            assertNull(spans.single().click)
        }
    }
}
