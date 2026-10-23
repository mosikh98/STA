package com.example.screentranslate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var providerSp: Spinner
    private lateinit var urlBox: LinearLayout
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var lang: EditText
    private lateinit var extra: EditText
    private lateinit var mode: Spinner
    private lateinit var effortSp: Spinner
    private lateinit var imgSp: Spinner
    private lateinit var methodSp: Spinner
    private lateinit var ocrSp: Spinner
    private lateinit var opaqueCb: CheckBox
    private lateinit var status: TextView
    private var cur = Store.GEMINI
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cur = Store.provider(this)
        val shared = Store.load(this)
        val pr = Store.profile(this, cur)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(TextView(this).apply { text = "سرویس‌دهنده" })
        providerSp = spinner(listOf("Gemini (فقط API Key)", "سفارشی (URL و کلید دلخواه)"), cur)
        root.addView(providerSp)

        urlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(urlBox)
        url = field(urlBox, "آدرس API (Base URL)", pr.url)
        key = field(root, "API Key", pr.key, pwd = true)
        model = field(root, "نام مدل (باید ورودی تصویر را پشتیبانی کند)", pr.model)
        button(root, "دریافت لیست مدل‌های این کلید و انتخاب") { fetchModels() }

        lang = field(root, "زبان مقصد (به انگلیسی بنویسید، مثلاً Persian)", shared.lang)
        extra = field(root, "دستورالعمل اضافی (اختیاری)", shared.extra, ltr = false, multi = true)

        root.addView(TextView(this).apply { text = "روش ترجمه"; setPadding(0, 24, 0, 0) })
        methodSp = spinner(
            listOf(
                "سریع: OCR روی گوشی + ترجمه متن با API (توصیه‌شده)",
                "مدل بینایی: عکس مستقیم به مدل (کندتر)"
            ),
            Store.methodIdx(this).coerceIn(0, 1)
        )
        root.addView(methodSp)

        root.addView(TextView(this).apply { text = "زبان متن روی صفحه (برای OCR)"; setPadding(0, 24, 0, 0) })
        ocrSp = spinner(
            listOf("چینی + انگلیسی/لاتین", "انگلیسی و زبان‌های لاتین", "ژاپنی + لاتین", "کره‌ای + لاتین"),
            Store.ocrIdx(this).coerceIn(0, 3)
        )
        root.addView(ocrSp)

        root.addView(TextView(this).apply { text = "فرمت مختصات (فقط روش «مدل بینایی»)"; setPadding(0, 24, 0, 0) })
        mode = spinner(
            listOf(
                "روی متن اصلی — نرمال ۰–۱۰۰۰ [y1,x1,y2,x2] (Gemini و مشابه)",
                "روی متن اصلی — نرمال ۰–۱۰۰۰ [x1,y1,x2,y2]",
                "روی متن اصلی — پیکسل [x1,y1,x2,y2] (Qwen-VL و مشابه)",
                "فقط پنل متنی (بدون مختصات)"
            ),
            shared.boxMode.coerceIn(0, 3)
        )
        root.addView(mode)

        root.addView(TextView(this).apply { text = "میزان تفکر مدل (کمتر = سریع‌تر)"; setPadding(0, 24, 0, 0) })
        effortSp = spinner(
            listOf(
                "خودکار (Gemini: کم / سفارشی: ارسال نشود)",
                "ارسال نشود",
                "حداقل (minimal)",
                "کم (low)",
                "متوسط (medium)"
            ),
            Store.effortIdx(this).coerceIn(0, 4)
        )
        root.addView(effortSp)

        root.addView(TextView(this).apply { text = "اندازه تصویر ارسالی (کوچک‌تر = سریع‌تر)"; setPadding(0, 24, 0, 0) })
        imgSp = spinner(
            listOf("سریع — عرض ۸۰۰", "متعادل — عرض ۱۰۲۴", "دقیق — عرض ۱۲۸۰"),
            Store.imgIdx(this).coerceIn(0, 2)
        )
        root.addView(imgSp)

        opaqueCb = CheckBox(this).apply {
            text = "برچسب‌های مات و پررنگ (متن زیرشان دیده نشود)"
            isChecked = Store.opaque(this@MainActivity)
        }
        root.addView(opaqueCb)
        root.addView(TextView(this).apply {
            text = "نکته: در اندروید ۱۲ به بالا، تا وقتی ترجمه مات روی صفحه است لمس بازی زیرش کار نمی‌کند؛ با منوی «پاک کردن ترجمه» برش دارید."
            textSize = 12f
        })

        status = TextView(this).apply { setPadding(0, 20, 0, 20) }
        root.addView(status)

        button(root, "تست اتصال") { testConnection() }
        button(root, "ذخیره تنظیمات") { save(); status.text = "ذخیره شد ✓" }
        button(root, "شروع ترجمه روی صفحه") { start() }
        button(root, "توقف") { stopService(Intent(this, OverlayService::class.java)); status.text = "متوقف شد" }

        applyProviderUi()
        providerSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != cur) {
                    saveProfile()          // keep what was typed for the provider we are leaving
                    cur = pos
                    val np = Store.profile(this@MainActivity, cur)
                    url.setText(np.url); key.setText(np.key); model.setText(np.model)
                    applyProviderUi()
                    status.text = ""
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun spinner(items: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected)
    }

    private fun applyProviderUi() {
        urlBox.visibility = if (cur == Store.GEMINI) View.GONE else View.VISIBLE
    }

    private fun field(
        parent: LinearLayout, label: String, value: String,
        ltr: Boolean = true, multi: Boolean = false, pwd: Boolean = false
    ): EditText {
        parent.addView(TextView(this).apply { text = label; setPadding(0, 24, 0, 0) })
        val e = EditText(this).apply {
            setText(value)
            inputType = when {
                pwd -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (ltr) textDirection = View.TEXT_DIRECTION_LTR
            if (multi) minLines = 2
        }
        parent.addView(e)
        return e
    }

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply { this.text = text; setOnClickListener { onClick() } })
    }

    private fun activeUrl() =
        if (cur == Store.GEMINI) Store.GEMINI_URL else url.text.toString().trim()

    private fun current() = Cfg(
        activeUrl(), key.text.toString().trim(), model.text.toString().trim(),
        lang.text.toString().trim().ifBlank { "Persian" },
        extra.text.toString().trim(), mode.selectedItemPosition
    )

    private fun saveProfile() {
        Store.saveProfile(this, cur, Profile(activeUrl(), key.text.toString().trim(), model.text.toString().trim()))
    }

    private fun save() {
        saveProfile()
        Store.setProvider(this, cur)
        Store.saveShared(
            this, lang.text.toString().trim().ifBlank { "Persian" },
            extra.text.toString().trim(), mode.selectedItemPosition,
            effortSp.selectedItemPosition, imgSp.selectedItemPosition, opaqueCb.isChecked,
            methodSp.selectedItemPosition, ocrSp.selectedItemPosition
        )
    }

    private fun errText(t: Throwable): String =
        if (t is java.io.InterruptedIOException)
            "زمان انتظار تمام شد؛ سرور پاسخ نداد (آدرس، اینترنت/VPN یا نام مدل را بررسی کنید)"
        else "${t.javaClass.simpleName}: ${t.message ?: ""}"

    private fun testConnection() {
        val cfg = current()
        val t0 = System.currentTimeMillis()
        var running = true
        val tick = object : Runnable {
            override fun run() {
                if (running) {
                    status.text = "در حال تست… ${(System.currentTimeMillis() - t0) / 1000} ثانیه"
                    ui.postDelayed(this, 1000)
                }
            }
        }
        ui.post(tick)
        Thread {
            val r = Api.test(cfg)
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            runOnUiThread {
                running = false
                ui.removeCallbacks(tick)
                status.text = r.fold(
                    { "اتصال موفق ✓  (${"%.1f".format(secs)} ثانیه) پاسخ: $it" },
                    { "خطا ✗  ${errText(it)}" }
                )
            }
        }.start()
    }

    private fun fetchModels() {
        val cfg = current()
        if (cfg.key.isBlank()) { status.text = "ابتدا API Key را وارد کنید"; return }
        status.text = "در حال دریافت لیست مدل‌ها…"
        val geminiOnly = cur == Store.GEMINI
        Thread {
            val r = Api.listModels(cfg, geminiOnly)
            runOnUiThread {
                r.onSuccess { list ->
                    if (list.isEmpty()) {
                        status.text = "مدلی پیدا نشد"
                    } else {
                        status.text = "${list.size} مدل برای این کلید پیدا شد"
                        showModels(list)
                    }
                }.onFailure { status.text = "خطا ✗  ${errText(it)}" }
            }
        }.start()
    }

    private fun showModels(list: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("انتخاب مدل")
            .setItems(list.toTypedArray()) { _, i ->
                model.setText(list[i])
                status.text = "مدل انتخاب شد: ${list[i]}"
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun start() {
        save()
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status.text = "مجوز «نمایش روی سایر برنامه‌ها» را بدهید و دوباره شروع را بزنید"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
