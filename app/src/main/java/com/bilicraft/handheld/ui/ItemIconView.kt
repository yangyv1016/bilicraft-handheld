package com.bilicraft.handheld.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.bilicraft.handheld.protocol.ItemDetails
import com.bilicraft.handheld.resourcepack.ItemIcon
import com.bilicraft.handheld.resourcepack.ResourcePackItemIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalResourcePackItemIcons = staticCompositionLocalOf<ResourcePackItemIcons?> { null }

@Composable
internal fun ItemIconView(item: ItemDetails, compact: Boolean = false) {
    val icons = LocalResourcePackItemIcons.current
    val result by produceState<ItemIcon?>(null, item, icons) {
        value = if (icons == null) ItemIcon(null, "尚未加载物品图标资源")
        else withContext(Dispatchers.IO) { icons.render(item) }
    }
    val bitmap = result?.bitmap
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = item.displayName.joinToString("") { it.text },
            modifier = Modifier.size(if (compact) 28.dp else 64.dp), filterQuality = FilterQuality.None)
    } else {
        Box(Modifier.size(if (compact) 28.dp else 48.dp).background(Color(0xFF383A43)), contentAlignment = Alignment.Center) {
            Text(if (compact) item.displayName.joinToString("") { it.text }.take(2) else "?", color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
        if (!compact) Text(if (result == null) "正在加载图标…" else "图标暂不可用，仍可查看物品详情", color = Color(0xFFAAAAAA))
    }
}
