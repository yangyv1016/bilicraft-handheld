package com.bilicraft.handheld.protocol

import com.bilicraft.handheld.protocol.McTypes.readString
import com.bilicraft.handheld.protocol.McTypes.readVarInt
import com.bilicraft.handheld.protocol.Nbt.readNetworkNbt
import io.netty.buffer.ByteBuf

/** 服务器返回的一条命令补全候选。tooltip 只保留纯文本，UI 不接触协议组件。 */
data class CommandSuggestion(
    val text: String,
    val tooltip: String? = null
)

/**
 * 当前命令补全状态。
 * start/length 使用服务器返回的原始范围，直接作用于 requestInput，避免 UI 猜 token 边界。
 */
data class CommandSuggestionState(
    val requestId: Int = -1,
    val requestInput: String = "",
    val start: Int = 0,
    val length: Int = 0,
    val suggestions: List<CommandSuggestion> = emptyList()
) {
    val hasSuggestions: Boolean
        get() = suggestions.isNotEmpty()
}

object CommandSuggestions {
    val Empty = CommandSuggestionState()

    fun readResponse(buf: ByteBuf, componentIsNbt: Boolean, requestInput: String): CommandSuggestionState {
        val requestId = buf.readVarInt()
        val start = buf.readVarInt()
        val length = buf.readVarInt()
        val count = buf.readVarInt()
        val suggestions = buildList {
            repeat(count) {
                val text = buf.readString()
                val tooltip = if (buf.readBoolean()) readTooltip(buf, componentIsNbt) else null
                add(CommandSuggestion(text, tooltip?.ifBlank { null }))
            }
        }
        return CommandSuggestionState(
            requestId = requestId,
            requestInput = requestInput,
            start = start,
            length = length,
            suggestions = suggestions
        )
    }

    fun apply(input: String, start: Int, length: Int, suggestion: String): String {
        val safeStart = start.coerceIn(0, input.length)
        val safeEnd = (safeStart + length).coerceIn(safeStart, input.length)
        return input.replaceRange(safeStart, safeEnd, suggestion)
    }

    fun clearFor(input: String): CommandSuggestionState = Empty.copy(requestInput = input)

    private fun readTooltip(buf: ByteBuf, componentIsNbt: Boolean): String =
        if (componentIsNbt) ChatComponent.fromNbt(buf.readNetworkNbt())
        else ChatComponent.toPlainText(buf.readString())
}