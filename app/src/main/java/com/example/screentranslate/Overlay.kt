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
 * Decides where each translated label goes and how big its text is. Pure geometry, no Android windows.
 *
 * Coordinates: every Item holds fractions (0..1) of the screenshot. The screenshot is taken at the real display
 * size, so fraction * display px = screen px. Fractions are converted exactly once, here.
 *
 * Anchor strategy:
 *  - vertical: TOP anchored. The label starts at the top of the source box and only grows downwards;
 *    it never grows past the top of the next text block below it (limitBottom).
 *  - horizontal: CENTER anchored on the source box. If the Persian text needs more room it first widens
 *    sideways, but only into free space (up to the neighbours on the same row / the screen edge).
 *  - font: starts at the size of the original text (from the OCR line height), is shrunk to at most 75% of it
 *    only after widening is exhausted; if it still does not fit the label grows downwards anyway.
 */
class LabelLayouter(
    private val screenW: Int, private val screenH: Int, private val d: Float,
    private val rtl: Boolean, private val fontScale: Float
) {
    private val items = ArrayList<Item>()
    private val raws = ArrayList<RectF>()          // source boxes in screen px, index-aligned with items
    private val keys = ArrayList<String?>()        // constraints used for the last placement (cache key)
    private var extra: List<RectF> = emptyList()   // all OCR boxes (known up front), also used as obstacles

    private fun toPx(l: Float, t: Float, r: Float, b: Float) = RectF(l * screenW, t * screenH, r * screenW, b * screenH)

    fun setSources(frac: List<RectF>) { extra = frac.map { toPx(it.left, it.top, it.right, it.bottom) } }

    /** Returns false (and ignores the item) for empty text or an invalid box. */
    fun add(item: Item): Boolean {
        if (item.text.isBlank() || screenW <= 0 || screenH <= 0) return false
        val r = toPx(item.l, item.t, item.r, item.b)
        if (!(r.width() > 0f && r.height() > 0f)) return false
        items.add(item); raws.add(r); keys.add(null)
        return true
    }

    /** Re-places every label whose free space changed since the last call; returns only new/changed ones. */
    fun placeAll(): List<Pair<Int, Placement>> {
        val out = ArrayList<Pair<Int, Placement>>()
        for (i in items.indices) {
            val c = constraints(i)
            val key = "${c[0].toInt()}|${c[1].toInt()}|${c[2].toInt()}"
            if (key == keys[i]) continue
            keys[i] = key
            out.add(i to fit(i, c[0], c[1], c[2]))
        }
        return out
    }

    private fun same(a: RectF, b: RectF) =
        abs(a.left - b.left) < 1.5f && abs(a.top - b.top) < 1.5f && abs(a.right - b.right) < 1.5f && abs(a.bottom - b.bottom) < 1.5f

    /** Free space around source i: [minX, maxX] on its text row and limitBottom = top of the next block below. */
    private fun constraints(i: Int): FloatArray {
        val src = raws[i]
        val margin = 4f * d
        val gap = 2f * d
        var minX = margin
        var maxX = screenW - margin
        var limitBottom = screenH - margin
        fun visit(o: RectF) {
            if (same(o, src)) return
            val vOv = min(o.bottom, src.bottom) - max(o.top, src.top)
            val hOv = min(o.right, src.right) - max(o.left, src.left)
            if (vOv > 0.5f * min(o.height(), src.height())) {
                // same text row: the neighbour limits how far we may widen on that side
                if (o.centerX() < src.centerX()) minX = max(minX, o.right + gap) else maxX = min(maxX, o.left - gap)
            } else if (o.top >= src.top + 0.5f * src.height() && hOv > 0.25f * min(o.width(), src.width())) {
                limitBottom = min(limitBottom, o.top - gap)   // next block below us
            }
        }
        for (o in raws) visit(o)
        for (o in extra) visit(o)
        minX = min(minX, src.left)     // never narrower than the source itself
        maxX = max(maxX, src.right)
        return floatArrayOf(minX, maxX, limitBottom)
    }

    /** Every layout gets its own TextPaint: a StaticLayout keeps using the paint it was built with. */
    private fun build(text: String, size: Float, w: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.WHITE }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(w, 20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            // bidi: force RTL paragraphs for RTL targets so mixed Persian/English text keeps a stable order
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .build()
    }

    private fun fit(i: Int, minX: Float, maxX: Float, limitBottom: Float): Placement {
        val item = items[i]
        val src = raws[i]
        val margin = 4f * d
        val ex = 3f * d                       // cover a little more than the OCR box (boxes are tight)
        val padX = 5f * d
        val padY = 3f * d
        val srcL = src.left - ex
        val srcR = src.right + ex
        val srcT = src.top - ex / 2
        val srcW = srcR - srcL
        val srcH = src.height() + ex

        val lineH = if (item.lineH > 0f) item.lineH * screenH else srcH
        val start = (lineH * 1.1f * fontScale).coerceIn(10f * d, 48f * d)   // em size ~ 1.1 x OCR line box height
        val minSize = max(start * 0.75f, 9f * d).coerceAtMost(start)
        val freeW = max(srcW, maxX - minX)
        val availH = max(srcH, limitBottom - srcT)

        var w = srcW
        var size = start
        var lay = build(item.text, size, (w - 2 * padX).toInt())
        var guard = 0
        while (lay.height + 2 * padY > availH && guard++ < 60) {
            if (w < freeW - 1f) w = min(freeW, w * 1.2f)                 // 1) widen into free space
            else if (size > minSize + 0.01f) size = max(minSize, size - d) // 2) then shrink the font a little
            else break                                                    // 3) otherwise grow downwards
            lay = build(item.text, size, (w - 2 * padX).toInt())
        }

        val minXe = min(minX, srcL)
        val maxXe = max(maxX, srcR)
        val left = (src.centerX() - w / 2).coerceIn(minXe, max(minXe, maxXe - w))
        val h = max(srcH, lay.height + 2 * padY)
        var top = srcT
        if (top + h > screenH - margin) top = max(margin, screenH - margin - h)   // keep inside the screen

        val textW = (w - 2 * padX).toInt()
        var dx = 0f
        if (lay.lineCount == 1) {          // single line: centre it; multi-line text keeps its reading alignment
            val lw = lay.getLineRight(0) - lay.getLineLeft(0)
            dx = (textW - lw) / 2f - lay.getLineLeft(0)
        }
        return Placement(RectF(left, top, left + w, top + h), lay, padX + dx, (h - lay.height) / 2f)
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
