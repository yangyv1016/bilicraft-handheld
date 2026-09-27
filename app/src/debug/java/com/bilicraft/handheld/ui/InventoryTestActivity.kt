package com.bilicraft.handheld.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bilicraft.handheld.protocol.*
import org.json.JSONObject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File

/** Local 1.21.11 server through adb reverse; never connects to a saved/user server. */
class InventoryTestActivity : ComponentActivity() {
    private lateinit var client: MinecraftClient
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.bilicraft.handheld.AppContainer.init(this)
        fun asset(name: String) = JSONObject(assets.open("minecraft/inventory-774/$name.json").bufferedReader().use { it.readText() })
        MinecraftTranslations.loadJson(assets.open("minecraft/zh_cn.json").bufferedReader().use { it.readText() })
        client = MinecraftClient(PaletteRegistry.forProtocol(774), 774, "", "InventoryQA",
            "b4fa9911000030008000000000000001", InventoryCodec(asset("slot-schema"), asset("items"), asset("item-components")), true)
        client.connect(ServerAddress("127.0.0.1", 25566))
        lifecycleScope.launch {
            client.inventory.first { !it.alive }
            client.sendRespawn()
        }
        if (intent.getBooleanExtra("runScenario", false)) lifecycleScope.launch {
            val report = File(filesDir, "inventory-local-test.txt")
            report.writeText("Local vanilla 1.21.11 server\n")
            try {
                withTimeout(60000) { client.inventory.first { it.ready && it.slots[36]?.count == 16 && it.slots[38]?.details?.id == "minecraft:diamond_helmet" } }
                val steps = listOf(
                    InventoryAction.Move(36, 9), InventoryAction.Move(9, 10, 5), InventoryAction.Move(10, 9),
                    InventoryAction.Move(9, 37), InventoryAction.Move(38, 5), InventoryAction.Move(5, 11),
                    InventoryAction.Move(37, 45), InventoryAction.Drop(45, false), InventoryAction.Drop(45, true))
                for (action in steps) {
                    val before = client.inventory.value
                    client.performInventoryAction(action, before.revision)
                    val after = withTimeout(15000) { client.inventory.first { it.revision != before.revision && !it.busy } }
                    check(after.message == "操作完成") { after.message ?: "Missing result" }
                    report.appendText("PASS $action\n")
                }
                client.selectHotbar(3)
                report.appendText("PASS all 9 inventory operations; hotbar 4 requested\n")
            } catch (error: Exception) { report.appendText("FAIL ${error.message}\n") }
        }
        setContent {
            val inventory by client.inventory.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(LocalResourcePackItemIcons provides com.bilicraft.handheld.AppContainer.vanillaItemIcons) {
                MaterialTheme {
                    val menu = inventory.menu
                    if (menu != null) ServerMenuScreen(inventory, menu,
                        onClose = { client.closeServerMenu(menu.generation) },
                        onClick = { slot, button, revision -> client.clickServerMenu(menu.generation, slot, button, revision) })
                    else InventoryScreen(inventory, onClose = { finish() }, onAction = client::performInventoryAction, onSelectHotbar = client::selectHotbar)
                }
            }
        }
    }
    override fun onDestroy() { client.disconnect(); super.onDestroy() }
}
