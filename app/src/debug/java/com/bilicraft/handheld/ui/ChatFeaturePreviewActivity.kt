package com.bilicraft.handheld.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.bilicraft.handheld.protocol.ChatComponent
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.ChatClick
import com.bilicraft.handheld.protocol.MinecraftTranslations

/** Debug-only deterministic fixtures rendered through the production chat UI. */
class ChatFeaturePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MinecraftTranslations.loadJson(assets.open("minecraft/zh_cn.json").bufferedReader().use { it.readText() })
        val messages = listOf(
            """{"text":"[打开测试菜单]","color":"green","click_event":{"action":"run_command","command":"menu"}}""",
            """{"text":"[填入命令不发送]","color":"aqua","clickEvent":{"action":"suggest_command","value":"/msg Player "}}""",
            """{"text":"[详情与命令共存]","color":"gold","hover_event":{"action":"show_text","value":"先看详情，再执行命令"},"click_event":{"action":"run_command","command":"signin gui"}}""",
            "普通消息：点击复制，长按也复制。",
            """{"text":"玩家展示了 ","extra":[{"text":"[测试钻石剑]","color":"aqua","hover_event":{"action":"show_item","id":"minecraft:diamond_sword","count":1,"components":{"minecraft:custom_name":{"text":"测试钻石剑","color":"aqua"},"minecraft:enchantments":{"minecraft:sharpness":5,"minecraft:unbreaking":3},"minecraft:lore":[{"text":"测试物品，不操作真实背包","color":"gold"}],"minecraft:damage":42,"minecraft:max_damage":1561}}},{"text":" 和 "},{"text":"[苹果]","color":"red","hover_event":{"action":"show_item","id":"minecraft:apple","count":12}}]}""",
            """{"text":"[活动说明]","color":"yellow","hover_event":{"action":"show_text","value":[{"text":"活动详情\\n","bold":true},{"text":"第一行：点击查看\\n第二行：返回或关闭后可重新打开","color":"green"}]}}""".replace("\\\\n", "\\n"),
            """{"text":"继承提示：","hover_event":{"action":"show_text","value":"父组件的说明"},"extra":[{"text":"[继承]","color":"green"},{"text":" [覆盖]","hover_event":{"action":"show_text","value":"子组件的说明"}}]}""",
            """{"text":"[隐藏物品]","hover_event":{"action":"show_item","id":"minecraft:stone","components":{"minecraft:tooltip_display":{"hide_tooltip":true}}}}"""
        ).map { raw ->
            val spans = ChatComponent.toSpans(raw)
            ChatEvent(spans.joinToString("") { it.text }, raw, spans = spans)
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("聊天交互验收（调试专用）")
                        var runs by remember { mutableStateOf(0) }
                        var command by remember { mutableStateOf("") }
                        var draft by remember { mutableStateOf("") }
                        ChatLog(messages, autoScroll = false, modifier = Modifier.weight(1f), onClickEvent = {
                            when (it) {
                                is ChatClick.RunCommand -> { runs++; command = it.command }
                                is ChatClick.SuggestCommand -> draft = it.command
                            }
                        })
                        Text("执行次数：$runs · $command")
                        Text("草稿：$draft")
                        val clipboard = LocalClipboardManager.current
                        var copied by remember { mutableStateOf("") }
                        TextButton(onClick = { copied = clipboard.getText()?.text.orEmpty() }) { Text("读取剪贴板") }
                        Text("剪贴板：$copied")
                    }
                }
            }
        }
    }
}
