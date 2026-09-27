package com.bilicraft.handheld.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bilicraft.handheld.protocol.InventoryState
import com.bilicraft.handheld.protocol.ServerMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerMenuScreen(state: InventoryState, menu: ServerMenu, onClose: () -> Unit,
    onClick: (slot: Int, button: Int, revision: Long) -> Unit) {
    var selected by remember(menu.generation) { mutableStateOf<Pair<Int, Long>?>(null) }
    var notice by remember(menu.generation) { mutableStateOf<String?>(null) }
    var confirmClose by remember(menu.generation) { mutableStateOf(false) }
    val canClick = menu.supported && menu.ready && state.alive && !state.busy
    fun close() {
        if (state.busy) notice = "请等待当前操作完成"
        else if (state.cursor != null) confirmClose = true
        else onClose()
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { MinecraftText(menu.title, style = MaterialTheme.typography.titleLarge, maxLines = 2) },
                navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭服务器菜单") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !menu.supported -> Text("此服务器界面暂未适配，可以返回关闭。")
                    !menu.ready -> Text(state.message ?: "正在等待服务器同步菜单…")
                    else -> {
                        for (row in 0 until menu.containerSlots / menu.columns) {
                            Row(Modifier.fillMaxWidth(menu.columns / 9f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (column in 0 until menu.columns) {
                                    val slot = row * menu.columns + column
                                    ItemSlot(menu.slots[slot], slot, Modifier.weight(1f)) {
                                        if (canClick) selected = slot to state.revision
                                    }
                                }
                            }
                        }
                        Text("个人背包", style = MaterialTheme.typography.titleMedium)
                        for (row in 0..3) {
                            if (row == 3) Text("快捷栏", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (column in 0..8) {
                                    val slot = menu.containerSlots + row * 9 + column
                                    ItemSlot(menu.slots[slot], slot, Modifier.weight(1f), row == 3 && column == state.selectedHotbar) {
                                        if (canClick) selected = slot to state.revision
                                    }
                                }
                            }
                        }
                        Text("点击格子查看详情，再选择左键或右键操作。")
                    }
                }
                state.cursor?.let {
                    Text("光标持有：${it.details.displayName.joinToString("") { span -> span.text }} ×${it.count}。点击目标格并选择左键放回。")
                }
                if (!state.alive) Text("角色已死亡，菜单暂不可操作。")
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { if (menu.ready) Text(it) }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        selected?.let { (slot, revision) ->
            val item = menu.slots[slot]
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlertDialog(onDismissRequest = { selected = null }, title = { Text("槽位 $slot") }, text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item == null) Text("空槽位") else ItemDetailsContent(item.details)
                        val unchanged = revision == state.revision
                        if (!unchanged) Text("菜单已更新，请关闭后重新选择。")
                        for (button in 0..1) TextButton(enabled = canClick && unchanged, onClick = {
                            selected = null
                            onClick(slot, button, revision)
                        }) { Text(if (button == 0) "左键点击" else "右键点击") }
                    }
                }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭详情") } })
            }
        }
        if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("光标上还有物品") },
            text = { Text("建议先将物品放回槽位。继续关闭时由服务器归还物品，背包已满时可能掉落。") },
            confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("关闭菜单") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("返回放回物品") } })
    }
}
