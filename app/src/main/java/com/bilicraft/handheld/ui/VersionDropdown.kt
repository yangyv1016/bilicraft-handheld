package com.bilicraft.handheld.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MenuAnchorType
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionRepository

/**
 * 版本下拉框：分组展示。
 * 顺序对齐需求：自动识别（默认置顶）→ 最新版 → Release → Snapshot → Old。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VersionDropdown(
    grouped: VersionRepository.Grouped,
    selected: McVersion,
    onSelect: (McVersion) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected.id + (selected.protocolNumber?.takeIf { it > 0 }?.let { "（协议 $it）" } ?: ""),
            onValueChange = {},
            readOnly = true,
            label = { Text("MC 版本") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            // 自动识别（默认）
            item(grouped.autoDetect, "默认") { onSelect(it); expanded = false }

            groupHeader("最新版")
            item(grouped.latest) { onSelect(it); expanded = false }

            groupHeader("Release")
            grouped.releases.forEach { v -> item(v) { onSelect(it); expanded = false } }

            if (grouped.snapshots.isNotEmpty()) {
                groupHeader("Snapshot")
                grouped.snapshots.forEach { v -> item(v) { onSelect(it); expanded = false } }
            }

            if (grouped.oldVersions.isNotEmpty()) {
                groupHeader("Old Alpha / Beta")
                grouped.oldVersions.forEach { v -> item(v) { onSelect(it); expanded = false } }
            }
        }
    }
}

@Composable
private fun groupHeader(title: String) {
    HorizontalDivider()
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun item(v: McVersion, tag: String? = null, onClick: (McVersion) -> Unit) {
    DropdownMenuItem(
        text = {
            val proto = v.protocolNumber?.takeIf { it > 0 }?.let { "  协议 $it" } ?: ""
            Text(v.id + proto + (tag?.let { "  [$it]" } ?: ""))
        },
        onClick = { onClick(v) }
    )
}