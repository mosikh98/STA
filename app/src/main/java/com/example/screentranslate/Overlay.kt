package com.example.screentranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Box coordinates are fractions (0..1) of the screen. */
data class Item(
    val text: String,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val color: Int = 0
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
        return Item(text, l, t, r, bt)
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

/** Full-screen view that paints each translation over the original text. Items can be added while visible. */
class TranslationView(ctx: Context, private val opaque: Boolean) : View(ctx) {
    private val items = ArrayList<Item>()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun add(item: Item) { items.add(item); invalidate() }

    private fun layout(text: String, size: Float, w: Int, color: Int): StaticLayout {
        tp.textSize = size
        tp.color = color
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 4f * d
        val ex = 4f * d          // grow the label a bit so the original text is fully covered
        val minSize = 8f * d
        val maxSize = 22f * d
        for (item in items) {
            val l = item.l * w - ex
            val t = item.t * h - ex / 2
            val bw = max((item.r - item.l) * w + 2 * ex, 48f * d)
            val bh = max((item.b - item.t) * h + ex, 14f * d)
            val textW = max((bw - 2 * pad).toInt(), 24)

            // push the background towards dark/light for stronger contrast with the text
            val sr = Color.red(item.color); val sg = Color.green(item.color); val sb = Color.blue(item.color)
            val lum = 0.299 * sr + 0.587 * sg + 0.114 * sb
            val dark = lum <= 140
            val fg = if (dark) Color.WHITE else Color.BLACK
            val br: Int; val bgc: Int; val bb: Int
            if (dark) { br = (sr * 0.6f).toInt(); bgc = (sg * 0.6f).toInt(); bb = (sb * 0.6f).toInt() }
            else { br = (sr + (255 - sr) * 0.4f).toInt(); bgc = (sg + (255 - sg) * 0.4f).toInt(); bb = (sb + (255 - sb) * 0.4f).toInt() }

            var size = (bh * 0.7f).coerceIn(minSize, maxSize)
            var lay = layout(item.text, size, textW, fg)
            while (lay.height > bh - pad && size > minSize) {
                size -= 1.5f * d
                lay = layout(item.text, size, textW, fg)
            }
            val hh = max(bh, lay.height + pad)
            bg.color = Color.argb(if (opaque) 255 else 235, br, bgc, bb)
            c.drawRoundRect(l, t, l + bw, t + hh, 6f * d, 6f * d, bg)
            c.save()
            c.translate(l + pad, t + (hh - lay.height) / 2f)
            lay.draw(c)
            c.restore()
        }
    }
}
