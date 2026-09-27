package com.bilicraft.handheld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bilicraft.handheld.protocol.InventoryAction
import com.bilicraft.handheld.protocol.InventoryActions
import com.bilicraft.handheld.protocol.InventoryState
import com.bilicraft.handheld.protocol.InventoryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InventoryScreen(state: InventoryState, onClose: () -> Unit,
    onAction: (InventoryAction, Long) -> Unit = { _, _ -> }, onSelectHotbar: (Int) -> Unit = {},
    onShowItem: ((Int) -> Unit)? = null) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var target by remember { mutableStateOf<PendingInventoryMove?>(null) }
    var placingCursor by remember { mutableStateOf(false) }
    var splitSlot by remember { mutableStateOf<Int?>(null) }
    var quantity by remember { mutableStateOf("1") }
    var drop by remember { mutableStateOf<PendingInventoryDrop?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun close() {
        if (state.busy) notice = "请等待当前操作完成"
        else if (state.cursor != null) notice = "请先将光标物品放回背包"
        else onClose()
    }
    fun clickSlot(slot: Int) {
        if (state.busy) return
        val move = target
        if (move != null) {
            target = null
            onAction(InventoryAction.Move(move.source, slot, move.quantity), move.revision)
        } else if (placingCursor) {
            placingCursor = false
            onAction(InventoryAction.PlaceCursor(slot), state.revision)
        } else selected = slot
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("个人背包") }, navigationIcon = {
                IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回聊天") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !state.supported -> Text("背包目前支持 Minecraft 1.21.11，请连接该版本服务器。")
                    !state.ready -> Text(state.message ?: "正在等待服务器同步背包…")
                    else -> {
                        Text("装备与副手", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((slot, label) in listOf(5 to "头盔", 6 to "胸甲", 7 to "护腿", 8 to "靴子", 45 to "副手")) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    ItemSlot(state.slots[slot], slot, Modifier.size(48.dp)) { clickSlot(slot) }
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Text("背包", style = MaterialTheme.typography.titleMedium)
                        for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (column in 0..8) {
                                val slot = 9 + row * 9 + column
                                ItemSlot(state.slots[slot], slot, Modifier.weight(1f)) { clickSlot(slot) }
                            }
                        }
                        Text("快捷栏 · 当前 ${state.selectedHotbar + 1}", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (slot in 36..44) ItemSlot(state.slots[slot], slot, Modifier.weight(1f), slot == state.selectedHotbar + 36) { clickSlot(slot) }
                        }
                        state.cursor?.let { Text("光标持有：${it.details.displayName.joinToString("") { span -> span.text }} ×${it.count}") }
                        if (state.openContainer != 0) Text("服务器当前打开了其他容器，个人背包暂不可操作。")
                        if (!state.alive) Text("角色已死亡，返回聊天页复活后再操作背包。")
                        if (state.cursor != null && !state.busy) TextButton(onClick = { placingCursor = true }) { Text("将光标物品放入槽位") }
                        if (target != null || placingCursor) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("请选择目标槽位", Modifier.weight(1f))
                            TextButton(onClick = { target = null; placingCursor = false }) { Text("取消") }
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("点击物品查看详情与操作", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        selected?.let { slot ->
            val item = state.slots[slot]
            val canOperate = state.ready && state.alive && !state.busy && state.openContainer == 0 && state.cursor == null
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlertDialog(onDismissRequest = { selected = null }, title = { Text("槽位 $slot") },
                    containerColor = Color(0xFF202127), titleContentColor = Color(0xFFE0E0E0), textContentColor = Color(0xFFE0E0E0), text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item == null) Text("空槽位") else ItemDetailsContent(item.details)
                        if (item != null) {
                            if (slot in 36..44 && onShowItem != null) TextButton(enabled = canOperate, onClick = {
                                selected = null
                                onShowItem(slot - 35)
                            }) { Text("展示") }
                            TextButton(enabled = canOperate, onClick = {
                                target = PendingInventoryMove(slot, null, state.revision); selected = null
                            }) { Text("移动 / 交换 / 合并") }
                            TextButton(enabled = canOperate && item.count > 1, onClick = {
                                splitSlot = slot; quantity = "1"; selected = null
                            }) { Text("拆分堆叠") }
                            val equipmentSlot = InventoryActions.equipmentSlot(item)
                            if (equipmentSlot != null && equipmentSlot != slot) TextButton(enabled = canOperate, onClick = {
                                onAction(InventoryAction.Move(slot, equipmentSlot), state.revision); selected = null
                            }) { Text("穿上装备") }
                            if (slot in 5..8) TextButton(enabled = canOperate, onClick = {
                                target = PendingInventoryMove(slot, null, state.revision); selected = null
                            }) { Text("脱下装备并选择目标") }
                            if (slot != 45) TextButton(enabled = canOperate, onClick = {
                                onAction(InventoryAction.Move(slot, 45), state.revision); selected = null
                            }) { Text("与副手交换") }
                            for (all in listOf(false, true)) TextButton(enabled = canOperate, onClick = {
                                drop = PendingInventoryDrop(slot, all, state.revision, item.details.displayName.joinToString("") { it.text }, if (all) item.count else 1)
                                selected = null
                            }) { Text(if (all) "丢弃整组…" else "丢弃一个…", color = MaterialTheme.colorScheme.error) }
                        }
                        if (slot in 36..44) TextButton(enabled = canOperate, onClick = {
                            onSelectHotbar(slot - 36); selected = null
                        }) { Text("切换到快捷栏 ${slot - 35}") }
                    }
                }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
            }
        }
        splitSlot?.let { slot ->
            val maximum = (state.slots[slot]?.count ?: 0) - 1
            val count = quantity.toIntOrNull()
            AlertDialog(onDismissRequest = { splitSlot = null }, title = { Text("拆分堆叠") }, text = {
                OutlinedTextField(quantity, { quantity = it }, label = { Text("数量（1–$maximum）") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }, confirmButton = { TextButton(enabled = count != null && count in 1..maximum, onClick = {
                target = PendingInventoryMove(slot, count, state.revision); splitSlot = null
            }) { Text("选择目标槽位") } }, dismissButton = { TextButton(onClick = { splitSlot = null }) { Text("取消") } })
        }
        drop?.let { pending ->
            AlertDialog(onDismissRequest = { drop = null }, title = { Text("确认丢弃？") },
                text = { Text("${pending.name} ×${pending.count}\n物品将掉落到游戏世界中。") },
                confirmButton = { TextButton(onClick = {
                    onAction(InventoryAction.Drop(pending.slot, pending.all), pending.revision); drop = null
                }) { Text("丢弃", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { drop = null }) { Text("取消") } })
        }
    }
}

@Composable
internal fun ItemSlot(item: InventoryItem?, slot: Int, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val name = item?.details?.displayName?.joinToString("") { it.text } ?: "空槽位"
    Box(modifier.aspectRatio(1f).background(Color(0xFF343740))
        .border(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else Color(0xFF727680))
        .semantics { contentDescription = "槽位 $slot，$name，${item?.count ?: 0} 个" }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (item != null) {
            ItemIconView(item.details, compact = true)
            if (item.count > 1) Text(item.count.toString(), Modifier.align(Alignment.BottomEnd).background(Color(0xBB202127)),
                color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private data class PendingInventoryMove(val source: Int, val quantity: Int?, val revision: Long)
private data class PendingInventoryDrop(val slot: Int, val all: Boolean, val revision: Long, val name: String, val count: Int)
