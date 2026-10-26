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
    private lateinit var methodSp: Spinner
    private lateinit var secApi: LinearLayout
    private lateinit var secInput: LinearLayout
    private lateinit var secOffline: LinearLayout
    private lateinit var secVision: LinearLayout
    private lateinit var secLlm: LinearLayout
    private lateinit var providerSp: Spinner
    private lateinit var urlBox: LinearLayout
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var extra: EditText
    private lateinit var srcBtn: Button
    private lateinit var dstBtn: Button
    private lateinit var mode: Spinner
    private lateinit var effortSp: Spinner
    private lateinit var imgSp: Spinner
    private lateinit var fontSp: Spinner
    private lateinit var opaqueCb: CheckBox
    private lateinit var testBtn: Button
    private lateinit var status: TextView

    private var cur = Store.GEMINI
    private var method = 1
    private var srcCode = "auto"
    private var dstCode = "fa"
    private var downloaded: Set<String> = emptySet()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cur = Store.provider(this)
        method = Store.methodIdx(this).coerceIn(0, 2)
        srcCode = Store.srcLang(this)
        dstCode = Store.dstLang(this)
        val shared = Store.load(this)
        val pr = Store.profile(this, cur)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // ---- 1) translation type, always first ----
        root.addView(lbl("نوع ترجمه"))
        methodSp = spinner(
            listOf(
                "مدل بینایی — عکس مستقیم به مدل (کندتر)",
                "سریع — OCR روی گوشی + ترجمه با API (توصیه‌شده)",
                "آفلاین — همه‌چیز روی گوشی، بدون API"
            ),
            method
        )
        root.addView(methodSp)

        // ---- API settings (vision + OCR/API methods) ----
        secApi = section(root)
        secApi.addView(lbl("سرویس‌دهنده"))
        providerSp = spinner(listOf("Gemini (فقط API Key)", "سفارشی (URL و کلید دلخواه)"), cur)
        secApi.addView(providerSp)
        urlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        secApi.addView(urlBox)
        url = field(urlBox, "آدرس API (Base URL)", pr.url)
        key = field(secApi, "API Key", pr.key, pwd = true)
        model = field(secApi, "نام مدل", pr.model)
        button(secApi, "دریافت لیست مدل‌های این کلید و انتخاب") { fetchModels() }

        // ---- input language (OCR methods) ----
        secInput = section(root)
        secInput.addView(lbl("زبان ورودی (متن روی صفحه)"))
        srcBtn = button(secInput, "") { pickLanguage(true) }
        secInput.addView(TextView(this).apply {
            text = "OCR فقط چینی، ژاپنی، کره‌ای و زبان‌های با خط لاتین را می‌خواند."
            textSize = 12f
        })

        // ---- output language (all methods) ----
        root.addView(lbl("زبان خروجی (ترجمه به)"))
        dstBtn = button(root, "") { pickLanguage(false) }

        // ---- offline: language models ----
        secOffline = section(root)
        secOffline.addView(TextView(this).apply {
            text = "مدل هر زبان یک بار داخل خود اپ دانلود می‌شود (حدود ۳۰ مگابایت) و بعد بدون اینترنت کار می‌کند."
            textSize = 12f
            setPadding(0, 16, 0, 0)
        })
        button(secOffline, "مدیریت و دانلود زبان‌ها") {
            startActivity(Intent(this, LanguagesActivity::class.java))
        }

        // ---- vision-model only ----
        secVision = section(root)
        secVision.addView(lbl("فرمت مختصات مدل"))
        mode = spinner(
            listOf(
                "نرمال ۰–۱۰۰۰ [y1,x1,y2,x2] (Gemini و مشابه)",
                "نرمال ۰–۱۰۰۰ [x1,y1,x2,y2]",
                "پیکسل [x1,y1,x2,y2] (Qwen-VL و مشابه)",
                "فقط پنل متنی (بدون مختصات)"
            ),
            shared.boxMode.coerceIn(0, 3)
        )
        secVision.addView(mode)
        secVision.addView(lbl("اندازه تصویر ارسالی (کوچک‌تر = سریع‌تر)"))
        imgSp = spinner(
            listOf("سریع — عرض ۸۰۰", "متعادل — عرض ۱۰۲۴", "دقیق — عرض ۱۲۸۰"),
            Store.imgIdx(this).coerceIn(0, 2)
        )
        secVision.addView(imgSp)

        // ---- API-based methods ----
        secLlm = section(root)
        secLlm.addView(lbl("میزان تفکر مدل (کمتر = سریع‌تر)"))
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
        secLlm.addView(effortSp)
        extra = field(secLlm, "دستورالعمل اضافی (اختیاری)", shared.extra, ltr = false, multi = true)

        // ---- look (all methods) ----
        root.addView(lbl("اندازه متن ترجمه نسبت به متن اصلی"))
        fontSp = spinner(
            listOf("کوچک‌تر (۸۵٪)", "هم‌اندازه (۱۰۰٪)", "بزرگ‌تر (۱۱۵٪)", "خیلی بزرگ‌تر (۱۳۰٪)"),
            Store.fontIdx(this).coerceIn(0, 3)
        )
        root.addView(fontSp)

        opaqueCb = CheckBox(this).apply {
            text = "برچسب‌های کاملاً مات و مشکی (متن زیرشان دیده نشود)"
            isChecked = Store.opaque(this@MainActivity)
        }
        root.addView(opaqueCb)
        root.addView(TextView(this).apply {
            text = "هر برچسب یک پنجره کوچک است: لمس بیرون از برچسب‌ها به برنامه زیر می‌رسد و لمس خود برچسب، همان را برمی‌دارد. اگر این تیک خاموش باشد، لمس از برچسب‌ها هم رد می‌شود ولی اندروید ۱۲+ آن‌ها را حدود ۸۰٪ شفاف می‌کند."
            textSize = 12f
        })

        status = TextView(this).apply { setPadding(0, 20, 0, 20) }
        root.addView(status)

        testBtn = button(root, "تست اتصال") { testConnection() }
        button(root, "ذخیره تنظیمات") { save(); status.text = "ذخیره شد ✓" }
        button(root, "شروع ترجمه روی صفحه") { start() }
        button(root, "توقف") { stopService(Intent(this, OverlayService::class.java)); status.text = "متوقف شد" }

        applyMethodUi()

        methodSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != method) { method = pos; applyMethodUi(); status.text = "" }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
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

    override fun onResume() {
        super.onResume()
        refreshDownloaded()   // the language manager may have changed things
    }

    // ------------------------------------------------------------------ UI helpers

    private fun vis(b: Boolean) = if (b) View.VISIBLE else View.GONE

    /** Show only the settings that belong to the chosen translation type. */
    private fun applyMethodUi() {
        secApi.visibility = vis(method != 2)
        secInput.visibility = vis(method != 0)
        secOffline.visibility = vis(method == 2)
        secVision.visibility = vis(method == 0)
        secLlm.visibility = vis(method != 2)
        testBtn.visibility = vis(method != 2)
        applyProviderUi()
        refreshLangButtons()
    }

    private fun applyProviderUi() {
        urlBox.visibility = vis(cur == Store.CUSTOM)
    }

    private fun lbl(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 0) }

    private fun section(parent: LinearLayout): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(s)
        return s
    }

    private fun spinner(items: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected)
    }

    private fun field(
        parent: LinearLayout, label: String, value: String,
        ltr: Boolean = true, multi: Boolean = false, pwd: Boolean = false
    ): EditText {
        parent.addView(lbl(label))
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

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit): Button {
        val b = Button(this).apply { this.text = text; setOnClickListener { onClick() } }
        parent.addView(b)
        return b
    }

    // ------------------------------------------------------------------ languages

    private fun isReady(c: String) = c == "en" || c in downloaded

    private fun langText(c: String): String {
        var s = Langs.label(c)
        if (method == 2 && c != "auto") s += if (isReady(c)) "  ✓" else "  (دانلود نشده)"
        return s
    }

    private fun refreshLangButtons() {
        srcBtn.text = "زبان ورودی: " + langText(srcCode)
        dstBtn.text = "زبان خروجی: " + langText(dstCode)
    }

    private fun refreshDownloaded() {
        Models.downloaded { set -> downloaded = set; refreshLangButtons() }
    }

    private fun pickLanguage(isSrc: Boolean) {
        val codes: List<String> =
            if (isSrc) listOf("auto") + Langs.ocrInput.map { it.code } else Langs.all.map { it.code }
        val labels = codes.map { c ->
            (if (method == 2 && c != "auto" && isReady(c)) "✓ " else "") + Langs.label(c)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isSrc) "زبان ورودی" else "زبان خروجی")
            .setItems(labels.toTypedArray()) { _, i ->
                val c = codes[i]
                if (isSrc) srcCode = c else dstCode = c
                refreshLangButtons()
                if (method == 2 && c != "auto" && !isReady(c)) offerDownload(c)
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun offerDownload(code: String) {
        AlertDialog.Builder(this)
            .setTitle("دانلود مدل زبان")
            .setMessage("مدل «${Langs.label(code)}» برای ترجمه آفلاین دانلود نشده (حدود ۳۰ مگابایت). الان دانلود شود؟")
            .setPositiveButton("دانلود") { _, _ -> startDownload(code) }
            .setNegativeButton("بعداً", null)
            .show()
    }

    private fun startDownload(code: String) {
        status.text = "در حال دانلود مدل ${Langs.label(code)}…"
        Models.download(code, false) { err ->
            if (err == null) {
                status.text = "دانلود شد ✓  ${Langs.label(code)}"
                refreshDownloaded()
            } else {
                status.text = "خطا در دانلود ✗  ${err.message}"
            }
        }
    }

    // ------------------------------------------------------------------ settings

    private fun activeUrl() =
        if (cur == Store.GEMINI) Store.GEMINI_URL else url.text.toString().trim()

    private fun current() = Cfg(
        url = activeUrl(), key = key.text.toString().trim(), model = model.text.toString().trim(),
        lang = Langs.enName(dstCode), extra = extra.text.toString().trim(),
        boxMode = mode.selectedItemPosition
    )

    private fun saveProfile() {
        Store.saveProfile(this, cur, Profile(activeUrl(), key.text.toString().trim(), model.text.toString().trim()))
    }

    private fun save() {
        saveProfile()
        Store.setProvider(this, cur)
        Store.saveShared(
            this, extra.text.toString().trim(), mode.selectedItemPosition,
            effortSp.selectedItemPosition, imgSp.selectedItemPosition, opaqueCb.isChecked,
            method, srcCode, dstCode, fontSp.selectedItemPosition
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

    // ------------------------------------------------------------------ start

    private fun start() {
        save()
        if (method != 2 && key.text.toString().isBlank()) {
            status.text = "ابتدا API Key را وارد کنید"
            return
        }
        if (method == 2) {
            val need = listOfNotNull(dstCode, srcCode.takeIf { it != "auto" }).filter { !isReady(it) }
            if (need.isNotEmpty()) {
                status.text = "اول مدل این زبان‌ها را دانلود کنید: " + need.joinToString("، ") { Langs.label(it) }
                return
            }
        }
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
