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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.ChatClick
import com.bilicraft.handheld.protocol.ChatHover
import com.bilicraft.handheld.protocol.ChatSpan
import com.bilicraft.handheld.protocol.ItemDetails
import com.bilicraft.handheld.protocol.MinecraftTranslations
import com.bilicraft.handheld.protocol.Nbt
import com.bilicraft.handheld.protocol.NbtTag
import com.bilicraft.handheld.resourcepack.ResourcePackArchive
import com.bilicraft.handheld.resourcepack.ResourcePackFonts
import com.bilicraft.handheld.resourcepack.ResourcePackItemIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Uses the real downloaded pack; sends no chat or inventory commands. */
class ResourcePackPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MinecraftTranslations.loadJson(assets.open("minecraft/zh_cn.json").bufferedReader().use { it.readText() })
        val messages = mutableListOf<ChatEvent>()
        fun message(spans: List<ChatSpan>) { messages += ChatEvent(spans.joinToString("") { it.text }, "", spans = spans) }
        message(listOf(ChatSpan("अ [打开菜单] ꐐ", color = 0x55ff55, click = ChatClick.RunCommand("/menu")), ChatSpan(" 普通文字")))
        message(listOf(ChatSpan("आ [填入不发送] ꐑ", color = 0x55ffff, click = ChatClick.SuggestCommand("/msg Player "))))
        val tooltip = ChatHover.Text(listOf(ChatSpan("国旗与称号：अ ꐐ", color = 0xffffff)))
        message(listOf(ChatSpan("原包国旗：अ आ इ ई उ ऊ  称号：ꐐ ꐑ ꐒ",color = 0xffffff,hover = tooltip)))
        message(listOf(ChatSpan("引用字体：अ ꐐ", font = "oraxen:effect_wave", hover = tooltip)))
        message(listOf(ChatSpan("普通间距：अअ  负间距：अ\uf80bअ", hover = tooltip)))
        message(listOf(ChatSpan("正间距：अ\uf802अ  零变化正文：中文 ABC 123")))
        for ((name, id, custom) in listOf(
            Triple("平面物品", "minecraft:paper", "oraxen:19001"),
            Triple("三维血剑", "minecraft:diamond_sword", "oraxen:blood_sword"),
            Triple("三维猫杖", "minecraft:wooden_sword", "oraxen:cat_staff")
        )) {
            val data = JSONObject().put("minecraft:custom_model_data",JSONObject().put("strings",org.json.JSONArray().put(custom)))
                .put("minecraft:custom_name",JSONObject().put("text","अ $name ꐐ"))
                .put("minecraft:lore",org.json.JSONArray().put(JSONObject().put("text","Lore 共用字形：आ ꐑ")))
            val item = ItemDetails(id,1,Nbt.fromJson(data) as NbtTag.NbtCompound)
            message(listOf(ChatSpan("अ 点击查看[$name]",color=0x55ffff,hover=ChatHover.Item(item)),ChatSpan(" 普通文字复制")))
        }
        message(listOf(ChatSpan("无资源图标",hover=ChatHover.Item(ItemDetails("minecraft:stone",1)))))
        setContent {
            MaterialTheme {
                val archive by produceState<ResourcePackArchive?>(null) {
                    value = withContext(Dispatchers.IO) {
                        File(filesDir,"resource-packs").listFiles()?.filter { it.extension == "zip" }?.maxByOrNull { it.lastModified() }?.let { ResourcePackArchive(it) }
                    }
                }
                val fonts = remember(archive) { archive?.let { ResourcePackFonts(listOf(it)) } }
                val icons = remember(archive) { archive?.let { ResourcePackItemIcons(listOf(it)) } }
                CompositionLocalProvider(LocalResourcePackFonts provides fonts, LocalResourcePackItemIcons provides icons) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (archive == null) "未找到已下载资源包" else "真实资源包 · 本地验收样例")
                            var runs by remember { mutableStateOf(0) }
                            var draft by remember { mutableStateOf("") }
                            ChatLog(messages,autoScroll=false,modifier=Modifier.weight(1f),onClickEvent = {
                                when (it) {
                                    is ChatClick.RunCommand -> runs++
                                    is ChatClick.SuggestCommand -> draft = it.command
                                }
                            })
                            Text("执行次数：$runs · 草稿：$draft")
                            val clipboard = LocalClipboardManager.current
                            var copied by remember { mutableStateOf("") }
                            TextButton(onClick={copied=clipboard.getText()?.text.orEmpty()}) { Text("读取剪贴板") }
                            Text("剪贴板：$copied")
                        }
                    }
                }
            }
        }
    }
}
