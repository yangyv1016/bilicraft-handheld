package com.bilicraft.handheld.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.bilicraft.handheld.protocol.ChatHover
import com.bilicraft.handheld.protocol.ChatSpan
import com.bilicraft.handheld.resourcepack.FontCharacter
import com.bilicraft.handheld.resourcepack.PackGlyph
import com.bilicraft.handheld.resourcepack.ResourcePackFonts
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val LocalResourcePackFonts = staticCompositionLocalOf<ResourcePackFonts?> { null }

private class InteractionSpan(val span: ChatSpan)

/** ReplacementSpan supports signed advances, unlike Compose's nonnegative inline placeholders. */
private class BitmapGlyphSpan(private val glyph: PackGlyph, private val bold: Boolean, private val italic: Boolean) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val scale = paint.textSize / 8f
        if (fm != null && glyph.bitmap != null) {
            fm.ascent = min(fm.ascent, floor(-glyph.ascent * scale).toInt())
            fm.descent = max(fm.descent, ceil((glyph.height - glyph.ascent) * scale).toInt())
            fm.top = min(fm.top, fm.ascent)
            fm.bottom = max(fm.bottom, fm.descent)
        }
        return ((glyph.advance + if (bold && glyph.bitmap != null) 1f else 0f) * scale).roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val bitmap = glyph.bitmap ?: return
        val scale = paint.textSize / 8f
        val targetHeight = glyph.height * scale
        val targetWidth = glyph.source.width().toFloat() / glyph.source.height() * targetHeight
        val drawPaint = Paint(paint).apply {
            isFilterBitmap = false
            colorFilter = PorterDuffColorFilter(paint.color, PorterDuff.Mode.MULTIPLY)
        }
        canvas.save()
        canvas.translate(x, y.toFloat())
        if (italic) canvas.skew(-0.25f, 0f)
        val destination = RectF(0f, -glyph.ascent * scale, targetWidth, -glyph.ascent * scale + targetHeight)
        canvas.drawBitmap(bitmap, glyph.source, destination, drawPaint)
        if (bold) {
            destination.offset(scale, 0f)
            canvas.drawBitmap(bitmap, glyph.source, destination, drawPaint)
        }
        canvas.restore()
    }
}

@Composable
internal fun PackFontText(
    spans: List<ChatSpan>, glyphs: Map<FontCharacter, PackGlyph>, modifier: Modifier,
    color: Color, style: TextStyle, maxLines: Int, onHover: ((ChatHover) -> Unit)?, onCopy: (() -> Unit)?,
    onClickEvent: ((ChatSpan) -> Unit)?
) {
    val density = LocalDensity.current
    val textSize = with(density) { style.fontSize.toPx() }
    val lineHeight = with(density) { if (style.lineHeight.isSpecified) style.lineHeight.toPx() else textSize }
    val actualColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val text = remember(spans, glyphs) {
        val value = SpannableString(spans.joinToString("") { it.text })
        var start = 0
        for (span in spans) {
            val end = start + span.text.length
            if (end == start) continue
            fun add(style: Any) { value.setSpan(style, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            span.color?.let { add(ForegroundColorSpan(0xff000000.toInt() or it)) }
            if (span.bold || span.italic) add(StyleSpan((if (span.bold) Typeface.BOLD else 0) or (if (span.italic) Typeface.ITALIC else 0)))
            if (span.underline) add(UnderlineSpan())
            if (span.strikethrough) add(StrikethroughSpan())
            if (span.hover != null || span.click != null) add(InteractionSpan(span))
            var offset = start
            while (offset < end) {
                val codePoint = Character.codePointAt(value, offset)
                val next = offset + Character.charCount(codePoint)
                glyphs[FontCharacter(ResourcePackFonts.normalizeId(span.font), codePoint)]?.let {
                    value.setSpan(BitmapGlyphSpan(it, span.bold, span.italic), offset, next, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                offset = next
            }
            start = end
        }
        value
    }
    AndroidView(
        modifier = modifier,
        factory = { context -> TextView(context).apply { includeFontPadding = false; setPadding(0, 0, 0, 0) } },
        update = { view ->
            view.text = text
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize)
            view.setTextColor(actualColor.toArgb())
            view.maxLines = maxLines
            view.ellipsize = TextUtils.TruncateAt.END
            view.setLineSpacing((lineHeight - view.paint.fontSpacing).coerceAtLeast(0f), 1f)
            view.setOnClickListener { onCopy?.invoke() }
            view.setOnLongClickListener { onCopy?.invoke(); onCopy != null }
            val hoverActions = spans.filter { it.hover != null }.distinctBy { it.hover }
            val clickActions = spans.filter { it.click != null }.distinctBy { it.click to it.hover }
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (onHover != null) hoverActions.forEachIndexed { index, span ->
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(0x01000000 + index, "查看${span.text}详情"))
                    }
                    if (onClickEvent != null) clickActions.forEachIndexed { index, span ->
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(0x01000000 + hoverActions.size + index, "打开${span.text}"))
                    }
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val hover = hoverActions.getOrNull(action - 0x01000000)?.hover
                    if (hover != null && onHover != null) { onHover(hover); return true }
                    val span = clickActions.getOrNull(action - 0x01000000 - hoverActions.size)
                    if (span != null && onClickEvent != null) { onClickEvent(span); return true }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
            val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent) = true
                override fun onLongPress(event: MotionEvent) { onCopy?.invoke() }
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    val layout = view.layout ?: return false
                    val x = event.x - view.totalPaddingLeft
                    val y = event.y - view.totalPaddingTop
                    val line = layout.getLineForVertical(y.toInt())
                    val offset = layout.getOffsetForHorizontal(line, x)
                    val visibleEnd = if (layout.getEllipsisCount(line) > 0)
                        layout.getLineStart(line) + layout.getEllipsisStart(line) else layout.getLineEnd(line)
                    val hit = listOf(offset, offset - 1).firstOrNull { position ->
                        position >= layout.getLineStart(line) && position < visibleEnd && position < text.length &&
                            y >= layout.getLineTop(line) && y < layout.getLineBottom(line) &&
                            x >= min(layout.getPrimaryHorizontal(position), layout.getPrimaryHorizontal(position + 1)) &&
                            x <= max(layout.getPrimaryHorizontal(position), layout.getPrimaryHorizontal(position + 1))
                    }
                    val span = hit?.let { position ->
                        text.getSpans(position, position + 1, InteractionSpan::class.java).firstOrNull {
                            text.getSpanStart(it) <= position && text.getSpanEnd(it) > position
                        }?.span
                    }
                    val hover = span?.hover
                    when {
                        span?.click != null && onClickEvent != null -> onClickEvent(span)
                        hover != null && onHover != null -> onHover(hover)
                        else -> onCopy?.invoke()
                    }
                    return true
                }
            })
            view.setOnTouchListener { _, event ->
                if (onHover == null && onCopy == null && onClickEvent == null) false else detector.onTouchEvent(event)
            }
        }
    )
}
