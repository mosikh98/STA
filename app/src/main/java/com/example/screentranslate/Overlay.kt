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
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Box coordinates are fractions (0..1) of the screen. */
data class Item(
    val text: String,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val color: Int = 0
)

/** Pulls complete top-level {...} objects out of a streamed JSON array, chunk by chunk. */
class ObjStream {
    private val sb = StringBuilder()
    private var depth = 0
    private var inStr = false
    private var esc = false

    fun feed(s: String): List<String> {
        val out = ArrayList<String>()
        for (ch in s) {
            if (depth == 0) {
                if (ch == '{') { depth = 1; sb.setLength(0); sb.append(ch); inStr = false; esc = false }
                continue
            }
            sb.append(ch)
            if (inStr) {
                if (esc) esc = false
                else if (ch == '\\') esc = true
                else if (ch == '"') inStr = false
            } else {
                when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) out.add(sb.toString()) }
                }
            }
        }
        return out
    }
}

object Overlay {
    /** One {"text":..., "box":[...]} object -> Item (null if malformed). */
    fun parseObject(raw: String, mode: Int, sw: Int, sh: Int): Item? = runCatching {
        val o = JSONObject(raw)
        if (o.isNull("text")) return@runCatching null
        val text = o.optString("text").trim()
        val bx = o.optJSONArray("box") ?: return@runCatching null
        if (text.isEmpty() || bx.length() < 4) return@runCatching null
        val v = DoubleArray(4) { bx.optDouble(it) }
        if (v.any { it.isNaN() }) return@runCatching null
        val q = when (mode) {
            0 -> listOf(v[1] / 1000, v[0] / 1000, v[3] / 1000, v[2] / 1000)
            1 -> listOf(v[0] / 1000, v[1] / 1000, v[2] / 1000, v[3] / 1000)
            else -> listOf(v[0] / sw, v[1] / sh, v[2] / sw, v[3] / sh)
        }.map { it.coerceIn(0.0, 1.0).toFloat() }
        val l = min(q[0], q[2]); val r = max(q[0], q[2])
        val t = min(q[1], q[3]); val b = max(q[1], q[3])
        if (r - l < 0.005f || b - t < 0.005f) null else Item(text, l, t, r, b)
    }.getOrNull()

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
