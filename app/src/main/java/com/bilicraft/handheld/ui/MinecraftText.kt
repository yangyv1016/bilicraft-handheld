package com.bilicraft.handheld.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bilicraft.handheld.resourcepack.FontCharacter
import com.bilicraft.handheld.resourcepack.PackGlyph
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.bilicraft.handheld.protocol.ChatHover
import com.bilicraft.handheld.protocol.ChatSpan

/** Shared styled text for chat and item tooltips. Hit targets follow text ranges, not rows. */
@Composable
internal fun MinecraftText(
    spans: List<ChatSpan>,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    onHover: ((ChatHover) -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onClickEvent: ((ChatSpan) -> Unit)? = null
) {
    val fonts = LocalResourcePackFonts.current
    val glyphs by produceState<Map<FontCharacter, PackGlyph>>(emptyMap(), fonts, spans) {
        value = if (fonts == null) emptyMap() else withContext(Dispatchers.IO) { fonts.resolve(spans) }
    }
    if (glyphs.isNotEmpty()) {
        PackFontText(spans, glyphs, modifier, color, style, maxLines, onHover, onCopy, onClickEvent)
        return
    }
    val annotated = remember(spans) {
        buildAnnotatedString {
            spans.forEachIndexed { index, span ->
                val start = length
                withStyle(SpanStyle(
                    color = span.color?.let { Color(0xFF000000.toInt() or it) } ?: Color.Unspecified,
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = when {
                        span.underline && span.strikethrough -> TextDecoration.combine(
                            listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                        span.underline -> TextDecoration.Underline
                        span.strikethrough -> TextDecoration.LineThrough
                        else -> null
                    }
                )) { append(span.text) }
                if ((span.hover != null || span.click != null) && start < length) {
                    addStringAnnotation("interaction", index.toString(), start, length)
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val interactions = if (onHover == null && onCopy == null && onClickEvent == null) Modifier else Modifier
        .pointerInput(annotated, onHover, onCopy, onClickEvent) {
            detectTapGestures(
                onLongPress = { onCopy?.invoke() },
                onTap = { position ->
                    val result = layout
                    var span: ChatSpan? = null
                    if (result != null && annotated.isNotEmpty()) {
                        val offset = result.getOffsetForPosition(position)
                        val line = result.getLineForVerticalPosition(position.y)
                        // Text returns a cursor boundary; the glyph can be on either side.
                        val hit = listOf(offset, offset - 1).firstOrNull {
                            it >= 0 && it < result.getLineEnd(line, visibleEnd = true) &&
                                it < annotated.length && result.getBoundingBox(it).contains(position)
                        }
                        if (hit != null) {
                            val range = annotated.getStringAnnotations("interaction", hit, hit).firstOrNull()
                            span = range?.let { spans[it.item.toInt()] }
                        }
                    }
                    val hover = span?.hover
                    when {
                        span?.click != null && onClickEvent != null -> onClickEvent(span)
                        hover != null && onHover != null -> onHover(hover)
                        else -> onCopy?.invoke()
                    }
                }
            )
        }
        .semantics {
            if (onCopy != null) {
                onClick("复制聊天内容") { onCopy(); true }
                onLongClick("复制聊天内容") { onCopy(); true }
            }
            customActions = spans.flatMap { span ->
                buildList {
                    if (span.hover != null && onHover != null) add(
                        CustomAccessibilityAction("查看${span.text}详情") { onHover(span.hover); true })
                    if (span.click != null && onClickEvent != null) add(
                        CustomAccessibilityAction("打开${span.text}") { onClickEvent(span); true })
                }
            }
        }
    Text(text = annotated, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it }, modifier = modifier.then(interactions))
}
