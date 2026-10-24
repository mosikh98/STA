package com.example.screentranslate

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/** In-app manager for the offline translation models: list, download, delete. */
class LanguagesActivity : Activity() {
    private val rows = HashMap<String, Pair<TextView, Button>>()
    private var downloaded: Set<String> = emptySet()
    private val busy = HashSet<String>()
    private lateinit var wifiCb: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "زبان‌های آفلاین"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "مدل هر زبان یک بار دانلود می‌شود (حدود ۳۰ مگابایت برای هر زبان) و بعد بدون اینترنت کار می‌کند. " +
                "ترجمه بین دو زبانی که هیچ‌کدام انگلیسی نیستند، از مدل انگلیسی هم استفاده می‌کند. " +
                "درصد پیشرفت نمایش داده نمی‌شود؛ تا پایان دانلود «در حال انجام…» می‌بینید."
            textSize = 13f
        })
        wifiCb = CheckBox(this).apply { text = "فقط روی Wi-Fi دانلود شود" }
        root.addView(wifiCb)

        for (l in Langs.all) {
            val name = TextView(this).apply { text = "${l.fa} — ${l.en}"; textSize = 15f }
            val st = TextView(this).apply { textSize = 12f }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(st)
            }
            val btn = Button(this).apply { textSize = 12f; setOnClickListener { toggle(l.code) } }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
                addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(btn)
            }
            root.addView(row)
            rows[l.code] = st to btn
        }
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        Models.downloaded { set ->
            downloaded = set
            for (c in rows.keys) update(c)
        }
    }

    private fun update(code: String) {
        val (st, btn) = rows[code] ?: return
        when {
            code in busy -> { st.text = "در حال انجام…"; btn.isEnabled = false }
            code in downloaded -> { st.text = "دانلود شده ✓"; btn.text = "حذف"; btn.isEnabled = true }
            else -> { st.text = "دانلود نشده"; btn.text = "دانلود"; btn.isEnabled = true }
        }
    }

    private fun toggle(code: String) {
        if (code in busy) return
        busy.add(code)
        update(code)
        if (code in downloaded) {
            Models.delete(code) { err ->
                busy.remove(code)
                if (err == null) { downloaded = downloaded - code }
                else { Toast.makeText(this, "خطا: ${err.message}", Toast.LENGTH_LONG).show() }
                update(code)
            }
        } else {
            Models.download(code, wifiCb.isChecked) { err ->
                busy.remove(code)
                if (err == null) { downloaded = downloaded + code }
                else { Toast.makeText(this, "خطا در دانلود: ${err.message}", Toast.LENGTH_LONG).show() }
                update(code)
            }
        }
    }
}
