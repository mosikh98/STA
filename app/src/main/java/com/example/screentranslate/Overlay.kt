package com.example.screentranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Box coordinates are fractions (0..1) of the screen. */
data class Item(
    val text: String,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val color: Int = 0,
    val lineH: Float = 0f   // height of one text line of the ORIGINAL text, as a fraction of screen height
)

/** Splits a streamed reply into complete lines. */
class LineStream {
    private val sb = StringBuilder()

    fun feed(s: String): List<String> {
        val out = ArrayList<String>()
        for (ch in s) {
            if (ch == '\n') { out.add(sb.toString()); sb.setLength(0) } else sb.append(ch)
        }
        return out
    }

    /** The last, unterminated line (if any). */
    fun flush(): String? {
        if (sb.isBlank()) return null
        val s = sb.toString()
        sb.setLength(0)
        return s
    }
}

object Overlay {
    /** Persian / Arabic-Indic digits and separators -> ASCII (models sometimes answer in Persian digits). */
    fun ascii(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                    '\u066B' -> '.'
                    '\u060C' -> ','
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private val boxRe = Regex(
        """^\s*\[?\s*(-?[0-9]+(?:\.[0-9]+)?)\s*,\s*(-?[0-9]+(?:\.[0-9]+)?)\s*,\s*(-?[0-9]+(?:\.[0-9]+)?)\s*,\s*(-?[0-9]+(?:\.[0-9]+)?)\s*\]?\s*$"""
    )

    /** One line "a,b,c,d|translation" -> Item (null if the line is not in that form). */
    fun parseBoxLine(line: String, mode: Int, sw: Int, sh: Int): Item? {
        val bar = line.indexOf('|')
        if (bar < 0) return null
        val m = boxRe.find(ascii(line.substring(0, bar))) ?: return null
        val text = line.substring(bar + 1).trim()
        if (text.isEmpty()) return null
        val v = DoubleArray(4)
        for (k in 0..3) v[k] = m.groupValues[k + 1].toDoubleOrNull() ?: return null
        val q = when (mode) {
            0 -> listOf(v[1] / 1000, v[0] / 1000, v[3] / 1000, v[2] / 1000)
            1 -> listOf(v[0] / 1000, v[1] / 1000, v[2] / 1000, v[3] / 1000)
            else -> listOf(v[0] / sw, v[1] / sh, v[2] / sw, v[3] / sh)
        }.map { it.coerceIn(0.0, 1.0).toFloat() }
        val l = min(q[0], q[2]); val r = max(q[0], q[2])
        val t = min(q[1], q[3]); val bt = max(q[1], q[3])
        if (r - l < 0.005f || bt - t < 0.005f) return null
        // one-line labels: the box is the line; taller boxes are paragraphs, assume a typical line height
        val boxH = bt - t
        return Item(text, l, t, r, bt, lineH = if (boxH <= 0.045f) boxH else 0.03f)
    }

    /** Average colour under the original text, used as the label background. */
    fun sample(bmp: Bitmap, item: Item): Int {
        val x0 = (item.l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val x1 = (item.r * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (item.t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val y1 = (item.b * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        var rs = 0; var gs = 0; var bs = 0; var n = 0
        for (i in 0..5) for (j in 0..5) {
            val p = bmp.getPixel(x0 + (x1 - x0) * i / 5, y0 + (y1 - y0) * j / 5)
            rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p); n++
        }
        return Color.rgb(rs / n, gs / n, bs / n)
    }
}

/** A placed label: window rectangle in screen px + the finished text layout (text coordinates are label-relative). */
class Placement(val rect: RectF, val layout: StaticLayout, val textLeft: Float, val textTop: Float)

/**
 * Puts each translation EXACTLY over the OCR box of the original text, and nothing more.
 *
 * Coordinates: Item boxes are fractions (0..1) of the screenshot. The screenshot is taken at the real display
 * size, so fraction * display px = screen px (converted once, here; no other scaling anywhere).
 *
 * The label rectangle is the OCR box plus a hair of padding (OCR boxes are tight). It never widens and never grows.
 * Instead the text is fitted INTO the box: the font starts at the size of the original text and is reduced
 * (binary search) until the wrapped translation fits both the width and the height of the box. If even the
 * smallest size does not fit, as many lines as fit are kept and the rest is cut with an ellipsis.
 */
class LabelLayouter(
    private val screenW: Int, private val screenH: Int, private val d: Float,
    private val rtl: Boolean, private val fontScale: Float
) {
    /** Every layout gets its own TextPaint: a StaticLayout keeps using the paint it was built with. */
    private fun build(text: String, size: Float, w: Int, maxLines: Int = Int.MAX_VALUE): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.WHITE }
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(w, 1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            // bidi: force RTL paragraphs for RTL targets so mixed Persian/English text keeps a stable order
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        if (maxLines != Int.MAX_VALUE) b.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        return b.build()
    }

    private fun fits(lay: StaticLayout, w: Int, h: Int): Boolean {
        if (lay.height > h) return false
        for (i in 0 until lay.lineCount) if (lay.getLineWidth(i) > w + 0.5f) return false   // unbreakable long word
        return true
    }

    /** Returns null for blank text or an unusable box. */
    fun place(item: Item): Placement? {
        if (item.text.isBlank() || screenW <= 0 || screenH <= 0) return null
        val pad = 1.5f * d
        val rect = RectF(item.l * screenW - pad, item.t * screenH - pad, item.r * screenW + pad, item.b * screenH + pad)
        if (!rect.intersect(0f, 0f, screenW.toFloat(), screenH.toFloat())) return null   // keep on screen
        if (rect.width() < 8f * d || rect.height() < 8f * d) return null                   // OCR noise

        val padX = 2f * d
        val padY = 0.5f * d
        val innerW = (rect.width() - 2 * padX).toInt()
        val innerH = (rect.height() - 2 * padY).toInt()

        // font size of the original text ~ 1.1 x the OCR line height (user can scale it in settings)
        val lineH = if (item.lineH > 0f) item.lineH * screenH else rect.height()
        val hi0 = (lineH * 1.1f * fontScale).coerceIn(8f * d, 48f * d)
        val lo0 = 6f * d

        var lay = build(item.text, hi0, innerW)
        if (!fits(lay, innerW, innerH)) {
            var lo = lo0
            var hi = hi0
            var best: StaticLayout? = null
            repeat(10) {
                val mid = (lo + hi) / 2f
                val l = build(item.text, mid, innerW)
                if (fits(l, innerW, innerH)) { best = l; lo = mid } else { hi = mid }
            }
            lay = best ?: run {
                // even the smallest size does not fit: keep the lines that fit and cut with an ellipsis
                val l = build(item.text, lo0, innerW)
                val lh = l.height / max(1, l.lineCount).toFloat()
                build(item.text, lo0, innerW, max(1, (innerH / lh).toInt()))
            }
        }

        var dx = 0f
        if (lay.lineCount == 1) {          // single line: centre it; multi-line text keeps its reading alignment
            val lw = lay.getLineRight(0) - lay.getLineLeft(0)
            dx = (innerW - lw) / 2f - lay.getLineLeft(0)
        }
        return Placement(rect, lay, padX + dx, (rect.height() - lay.height) / 2f)
    }
}

/** One label = one small overlay window, so only the label area is covered (and can be fully opaque). */
class LabelView(ctx: Context, var placement: Placement, opaque: Boolean) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 0, 0, 0) }

    override fun onDraw(c: Canvas) {
        val p = placement
        val r = 4f * resources.displayMetrics.density
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)
        c.save()
        c.translate(p.textLeft, p.textTop)
        p.layout.draw(c)
        c.restore()
    }
}
