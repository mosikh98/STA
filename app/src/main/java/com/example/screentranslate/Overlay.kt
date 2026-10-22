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
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

/** Box coordinates are fractions (0..1) of the screen. */
data class Item(
    val text: String,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val color: Int = 0
)

object Overlay {
    /** Returns null if the reply is not a parseable JSON array. */
    fun parse(raw: String, mode: Int, sw: Int, sh: Int): List<Item>? = runCatching {
        val s = raw.trim()
        val a = s.indexOf('[')
        val z = s.lastIndexOf(']')
        if (a < 0 || z <= a) return@runCatching null
        val arr = JSONArray(s.substring(a, z + 1))
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text").trim()
            val bx = o.optJSONArray("box") ?: continue
            if (text.isEmpty() || bx.length() < 4) continue
            val v = DoubleArray(4) { bx.optDouble(it) }
            if (v.any { it.isNaN() }) continue
            val q = when (mode) {
                0 -> listOf(v[1] / 1000, v[0] / 1000, v[3] / 1000, v[2] / 1000)
                1 -> listOf(v[0] / 1000, v[1] / 1000, v[2] / 1000, v[3] / 1000)
                else -> listOf(v[0] / sw, v[1] / sh, v[2] / sw, v[3] / sh)
            }.map { it.coerceIn(0.0, 1.0).toFloat() }
            val l = min(q[0], q[2]); val r = max(q[0], q[2])
            val t = min(q[1], q[3]); val b = max(q[1], q[3])
            if (r - l < 0.005f || b - t < 0.005f) continue
            out.add(Item(text, l, t, r, b))
        }
        out as List<Item>?
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

/** Full-screen view that paints each translation over the original text. */
class TranslationView(ctx: Context, private val items: List<Item>) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)

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
        val minSize = 8f * d
        val maxSize = 22f * d
        for (item in items) {
            val l = item.l * w
            val t = item.t * h
            val bw = max(item.r * w - l, 48f * d)
            val bh = max(item.b * h - t, 14f * d)
            val textW = max((bw - 2 * pad).toInt(), 24)
            val rgb = item.color
            val lum = 0.299 * Color.red(rgb) + 0.587 * Color.green(rgb) + 0.114 * Color.blue(rgb)
            val fg = if (lum > 140) Color.BLACK else Color.WHITE

            var size = (bh * 0.7f).coerceIn(minSize, maxSize)
            var lay = layout(item.text, size, textW, fg)
            while (lay.height > bh - pad && size > minSize) {
                size -= 1.5f * d
                lay = layout(item.text, size, textW, fg)
            }
            val hh = max(bh, lay.height + pad)
            bg.color = Color.argb(235, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            c.drawRoundRect(l, t, l + bw, t + hh, 6f * d, 6f * d, bg)
            c.save()
            c.translate(l + pad, t + (hh - lay.height) / 2f)
            lay.draw(c)
            c.restore()
        }
    }
}
