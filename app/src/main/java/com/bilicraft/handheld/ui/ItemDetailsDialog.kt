package com.bilicraft.handheld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bilicraft.handheld.protocol.ChatComponent
import com.bilicraft.handheld.protocol.ChatHover
import com.bilicraft.handheld.protocol.ChatClick
import com.bilicraft.handheld.protocol.ItemDetails
import com.bilicraft.handheld.protocol.MinecraftTranslations
import com.bilicraft.handheld.protocol.NbtTag

@Composable
internal fun ChatDetailsDialog(
    hover: ChatHover, onDismiss: () -> Unit,
    action: ChatClick? = null, onAction: (ChatClick) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF202127),
        titleContentColor = Color(0xFFE0E0E0),
        textContentColor = Color(0xFFE0E0E0),
        title = { Text(if (hover is ChatHover.Item) "物品详情" else "消息详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (hover) {
                    is ChatHover.Text -> MinecraftText(hover.spans)
                    is ChatHover.Item -> ItemDetailsContent(hover.item)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = Color(0xFFB8ACFF)) } },
        dismissButton = {
            if (action != null) TextButton(onClick = { onAction(action) }) {
                Text(if (action is ChatClick.RunCommand) "执行点击命令" else "填入聊天框", color = Color(0xFFB8ACFF))
            }
        }
    )
}

@Composable
internal fun ItemDetailsContent(item: ItemDetails) {
    val components = item.components.entries
    val tooltip = (components["minecraft:tooltip_display"] as? NbtTag.NbtCompound)?.entries
    if ((tooltip?.get("hide_tooltip") as? NbtTag.NbtByte)?.value == 1.toByte() ||
        (tooltip?.get("hide_tooltip") as? NbtTag.NbtInt)?.value == 1) {
        Text("此物品的详情已被服务器隐藏")
        return
    }
    val hidden = (tooltip?.get("hidden_components") as? NbtTag.NbtList)?.items
        ?.mapNotNull { (it as? NbtTag.NbtString)?.value }?.toSet().orEmpty()
    ItemIconView(item)
    MinecraftText(item.displayName)
    Text("数量：${item.count}")
    for (key in listOf("minecraft:enchantments", "minecraft:stored_enchantments")) {
        if (key in hidden) continue
        val enchantments = (components[key] as? NbtTag.NbtCompound)?.entries ?: continue
        for ((id, levelTag) in enchantments) {
            val level = (levelTag as? NbtTag.NbtInt)?.value ?: continue
            val name = MinecraftTranslations.templateFor("enchantment.${id.replace(':', '.')}") ?: id
            val levelName = MinecraftTranslations.templateFor("enchantment.level.$level") ?: level.toString()
            Text("$name $levelName", color = Color(0xFFAAAAAA))
        }
    }
    if ("minecraft:lore" !in hidden) {
        val lore = (components["minecraft:lore"] as? NbtTag.NbtList)?.items.orEmpty()
        lore.forEach { MinecraftText(ChatComponent.spansFromNbt(it)) }
    }
    if ("minecraft:unbreakable" in components && "minecraft:unbreakable" !in hidden) Text("无法破坏")
    if ("minecraft:damage" !in hidden) {
        val damage = (components["minecraft:damage"] as? NbtTag.NbtInt)?.value
        val maximum = (components["minecraft:max_damage"] as? NbtTag.NbtInt)?.value
        if (maximum != null) Text("耐久：${(maximum - (damage ?: 0)).coerceAtLeast(0)} / $maximum")
        else if (damage != null) Text("已损耗耐久：$damage")
    }
    Text(item.id, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
}
