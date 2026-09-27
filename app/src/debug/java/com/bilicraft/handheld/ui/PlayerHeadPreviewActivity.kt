package com.bilicraft.handheld.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilicraft.handheld.AppContainer
import com.bilicraft.handheld.protocol.*
import com.bilicraft.handheld.resourcepack.*
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Local fixtures use the production icon and chat components; no server actions. */
class PlayerHeadPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.init(this)
        val skin = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(skin)
        val paint = Paint()
        for ((rectangle, color) in listOf(
            intArrayOf(8,8,16,16) to Color.RED, intArrayOf(0,8,8,16) to Color.GREEN,
            intArrayOf(16,8,24,16) to Color.BLUE, intArrayOf(8,0,16,8) to Color.YELLOW,
            intArrayOf(42,10,46,14) to Color.CYAN
        )) {
            paint.color = color
            canvas.drawRect(rectangle[0].toFloat(), rectangle[1].toFloat(), rectangle[2].toFloat(), rectangle[3].toFloat(), paint)
        }
        val pack = File(cacheDir, "head-qa.zip")
        ZipOutputStream(pack.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.mcmeta"))
            zip.write("""{"pack":{"pack_format":75}}""".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/qa/textures/skin.png"))
            skin.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry()
        }
        val icons = ResourcePackItemIcons(listOf(ResourcePackArchive(File(filesDir,"vanilla-items-1.21.11.zip")), ResourcePackArchive(pack)),
            PlayerHeadSkins(File(cacheDir,"qa-head-skins")))
        val value = Base64.getEncoder().encodeToString("""{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680"}}}""".toByteArray())
        val fixtures = listOf(
            "默认 Steve" to "{}",
            "静态皮肤" to """{"properties":[{"name":"textures","value":"$value"}]}""",
            "动态玩家名" to """{"name":"Notch"}""",
            "动态 UUID" to """{"id":"069a79f4-44e9-4726-a5be-fca90e38aaf5"}""",
            "资源包 Alex" to """{"texture":"minecraft:entity/player/wide/alex"}""",
            "面朝红色 · 青色帽层" to """{"texture":"qa:skin"}"""
        ).map { (name, profile) ->
            name to ItemDetails("minecraft:player_head",1,NbtTag.NbtCompound(mapOf(
                "minecraft:profile" to Nbt.fromJson(JSONObject(profile)),
                "minecraft:custom_name" to NbtTag.NbtString(name))))
        }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalResourcePackItemIcons provides icons) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("玩家头颅验收", style = MaterialTheme.typography.titleLarge)
                            fixtures.chunked(3).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    row.forEach { (name, item) -> Column { Text(name); ItemIconView(item) } }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            ChatLog(fixtures.map { (name, item) ->
                                ChatEvent("[$name]", "", spans = listOf(ChatSpan("[$name]",color=0x55ffff,hover=ChatHover.Item(item))))
                            }, false, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
