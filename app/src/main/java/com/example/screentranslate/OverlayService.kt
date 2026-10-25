package com.example.screentranslate

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

class OverlayService : Service() {
    companion object { const val ACTION_STOP = "stop"; const val CH = "st_channel" }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var rw = 0
    private var rh = 0
    private var fab: TextView? = null
    private var fabLp: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var panel: View? = null
    private var panelText: TextView? = null
    private var overlay: View? = null
    private var pending = false
    private var busy = false
    /** Bumped whenever a translation is cancelled / replaced; stale results are dropped. */
    @Volatile private var gen = 0

    private val timeout = Runnable {
        if (pending) {
            pending = false
            busy = false
            fab?.visibility = View.VISIBLE
            Toast.makeText(this, "تصویری دریافت نشد، دوباره امتحان کنید", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun makeReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> onImage(ir) }, main)
        return r
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()

        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        val dm = realMetrics()
        rw = dm.widthPixels; rh = dm.heightPixels
        reader = makeReader(rw, rh)
        display = projection!!.createVirtualDisplay(
            "st", rw, rh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )
        showFab()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Screen Translate", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Screen Translate فعال است")
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "توقف", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun overlayParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT
    )

    // ---------------------------------------------------------------- floating button + menu

    private fun showFab() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val lp = overlayParams(size, size).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 400 }
        val tv = TextView(this).apply {
            text = "ترجمه"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDD1976D2.toInt()) }
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (!moved && (abs(dx) > 10 || abs(dy) > 10)) { moved = true; hideMenu() }
                    if (moved) { lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggleMenu(); true }
                else -> false
            }
        }
        wm.addView(tv, lp)
        fab = tv; fabLp = lp
    }

    private fun toggleMenu() { if (menu != null) hideMenu() else showMenu() }

    private fun hideMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    /** Popup next to the floating button: translate / clear / stop. */
    private fun showMenu() {
        val d = resources.displayMetrics.density
        val itemH = (48 * d).toInt()
        val menuW = (160 * d).toInt()
        val entries: List<Pair<String, () -> Unit>> = listOf(
            "ترجمه صفحه" to { menuTranslate() },
            "پاک کردن ترجمه" to { menuClear() },
            "توقف" to { stopSelf() }
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(0xF0202020.toInt()) }
            for ((label, action) in entries) {
                addView(TextView(context).apply {
                    text = label; textSize = 15f; setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, itemH)
                    setOnClickListener { hideMenu(); action() }
                })
            }
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) { hideMenu(); true } else false
            }
        }
        val f = fabLp ?: return
        val menuH = itemH * entries.size
        val dm = realMetrics()
        val mx = f.x.coerceIn(0, (dm.widthPixels - menuW).coerceAtLeast(0))
        val above = f.y - menuH - (8 * d).toInt()
        val my = if (above >= 0) above else f.y + f.height + (8 * d).toInt()
        val lp = overlayParams(menuW, menuH, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
            gravity = Gravity.TOP or Gravity.START
            x = mx; y = my
        }
        wm.addView(box, lp)
        menu = box
    }

    /** Cancels any translation in progress and removes everything drawn on screen. */
    private fun menuClear() {
        gen++
        busy = false
        pending = false
        main.removeCallbacks(timeout)
        fab?.visibility = View.VISIBLE
        fab?.text = "ترجمه"
        removeOverlay(); hidePanel()
    }

    private fun menuTranslate() {
        val c = Store.load(this)
        if (c.method != 2 && c.key.isBlank()) {
            Toast.makeText(this, "ابتدا API Key را در تنظیمات وارد کنید", Toast.LENGTH_LONG).show()
            return
        }
        menuClear()           // also cancels a translation that is still running
        busy = true
        // small delay so the menu / old translation are gone before the screenshot
        main.postDelayed({ startCapture() }, 250)
    }

    // ---------------------------------------------------------------- capture + translate

    private fun startCapture() {
        // follow screen rotation (e.g. app started in portrait, game is landscape)
        val dm = realMetrics()
        if (dm.widthPixels != rw || dm.heightPixels != rh) {
            rw = dm.widthPixels; rh = dm.heightPixels
            reader?.close()
            reader = makeReader(rw, rh)
            display?.resize(rw, rh, dm.densityDpi)
            display?.surface = reader!!.surface
        }
        pending = true
        fab?.visibility = View.INVISIBLE   // forces a fresh frame
        main.postDelayed(timeout, 2500)
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            if (pending) {
                pending = false
                main.removeCallbacks(timeout)
                val bmp = toBitmap(img)
                fab?.visibility = View.VISIBLE
                process(bmp)
            }
        } finally { img.close() }
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val rowPad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    private fun process(bmp: Bitmap) {
        val cfg = Store.load(this)
        val g = gen
        fab?.text = "…"
        Thread {
            when (cfg.method) {
                1 -> runOcr(cfg, bmp, g)
                2 -> runOffline(cfg, bmp, g)
                else -> runVision(cfg, bmp, g)
            }
        }.start()
    }

    private fun fail(g: Int, msg: String) {
        main.post {
            if (g == gen) { busy = false; fab?.text = "ترجمه"; showPanel(msg) }
        }
    }

    /** Called when streaming ended (also with errors). Items have already been drawn as they arrived. */
    private fun finish(g: Int, cfg: Cfg, r: Result<String>, count: Int) {
        main.post {
            if (g != gen) return@post
            busy = false
            fab?.text = "ترجمه"
            val err = r.exceptionOrNull()
            val full = r.getOrNull().orEmpty()
            when {
                err != null && count == 0 -> showPanel("خطا: ${err.message}")
                err != null -> Toast.makeText(this, "ترجمه ناقص ماند: ${err.message}", Toast.LENGTH_LONG).show()
                cfg.method == 0 && cfg.boxMode == 3 ->
                    if (full.isBlank()) Toast.makeText(this, "متنی برای ترجمه پیدا نشد", Toast.LENGTH_SHORT).show()
                count == 0 ->
                    if (full.isBlank()) Toast.makeText(this, "متنی برای ترجمه پیدا نشد", Toast.LENGTH_SHORT).show()
                    else showPanel(full)   // reply was not in the expected line format: show as plain text
                else -> Unit
            }
            Unit
        }
    }

    /** Runs OCR; returns null when the pipeline already ended (error / nothing found / cancelled). */
    private fun ocrBlocks(cfg: Cfg, bmp: Bitmap, g: Int): List<OcrBlock>? {
        val blocks = try {
            Ocr.recognize(bmp, cfg.ocrScript)
        } catch (e: Throwable) {
            fail(g, "خطای OCR: ${e.message}")
            return null
        }
        if (g != gen) return null
        if (blocks.isEmpty()) { finish(g, cfg, Result.success(""), 0); return null }
        return blocks
    }

    /** Fast method: ML Kit OCR on the phone (boxes), then only the text goes to the API. */
    private fun runOcr(cfg: Cfg, bmp: Bitmap, g: Int) {
        val blocks = ocrBlocks(cfg, bmp, g) ?: return

        val numbered = StringBuilder()
        blocks.forEachIndexed { i, b -> numbered.append(i + 1).append('|').append(b.text).append('\n') }

        val ls = LineStream()
        val count = AtomicInteger(0)

        fun handle(line: String) {
            val bar = line.indexOf('|')
            if (bar < 0) return
            val idx = Overlay.ascii(line.substring(0, bar)).trim().toIntOrNull() ?: return
            val t = line.substring(bar + 1).trim()
            if (t.isEmpty()) return
            val b = blocks.getOrNull(idx - 1) ?: return
            count.incrementAndGet()
            val item = Item(t, b.l, b.t, b.r, b.b, lineH = b.lineH)
            main.post { if (g == gen) addItem(item, cfg.opaque) }
        }

        val r = Api.translateText(cfg, numbered.toString(), { g != gen }) { delta ->
            for (ln in ls.feed(delta)) runCatching { handle(ln) }
        }
        ls.flush()?.let { runCatching { handle(it) } }
        finish(g, cfg, r, count.get())
    }

    /** Offline method: OCR + ML Kit on-device translation. No API, no key. */
    private fun runOffline(cfg: Cfg, bmp: Bitmap, g: Int) {
        val blocks = ocrBlocks(cfg, bmp, g) ?: return
        val count = AtomicInteger(0)
        val r: Result<String> = try {
            Offline.translate(
                cfg.src, cfg.dst, blocks, { g != gen },
                { msg -> main.post { if (g == gen) Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
            ) { idx, text ->
                val b = blocks[idx]
                count.incrementAndGet()
                val item = Item(text, b.l, b.t, b.r, b.b, lineH = b.lineH)
                main.post { if (g == gen) addItem(item, cfg.opaque) }
            }
            Result.success("")
        } catch (e: Throwable) {
            Result.failure(e)
        }
        finish(g, cfg, r, count.get())
    }

    /** Vision method: the whole screenshot goes to the model, which returns text + boxes. */
    private fun runVision(cfg: Cfg, bmp: Bitmap, g: Int) {
        val targetW = min(cfg.imgWidth, bmp.width)
        val scaled = if (bmp.width > targetW)
            Bitmap.createScaledBitmap(bmp, targetW, bmp.height * targetW / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val sw = scaled.width
        val sh = scaled.height

        val ls = LineStream()
        val count = AtomicInteger(0)
        val acc = StringBuilder()

        fun handle(line: String) {
            val item = Overlay.parseBoxLine(line, cfg.boxMode, sw, sh) ?: return
            count.incrementAndGet()
            main.post { if (g == gen) addItem(item, cfg.opaque) }
        }

        val r = Api.translate(cfg, b64, sw, sh, { g != gen }) { delta ->
            acc.append(delta)
            if (cfg.boxMode == 3) {
                val t = acc.toString()
                main.post { if (g == gen) updatePanel(t) }
            } else {
                for (ln in ls.feed(delta)) runCatching { handle(ln) }
            }
        }
        if (cfg.boxMode != 3) ls.flush()?.let { runCatching { handle(it) } }
        finish(g, cfg, r, count.get())
    }

    // ---------------------------------------------------------------- drawing

    private fun addItem(item: Item, opaque: Boolean) {
        var v = overlay as? TranslationView
        if (v == null) v = createOverlay(opaque)
        v.add(item)
    }

    /** Full-screen layer that paints translations over the original text. Touches pass through where allowed. */
    private fun createOverlay(opaque: Boolean): TranslationView {
        removeOverlay()
        val dm = realMetrics()
        val v = TranslationView(this, opaque)
        val lp = overlayParams(
            dm.widthPixels, dm.heightPixels,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ only lets touches pass through overlays with alpha <= 0.8
            alpha = if (opaque) 1.0f else 0.8f
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        overlay = v
        fab?.let { f ->
            wm.removeView(f)          // re-add so the button stays above the translation
            wm.addView(f, fabLp)
        }
        return v
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun updatePanel(text: String) {
        if (panel == null) showPanel(text) else panelText?.text = text
    }

    /** Plain text panel (panel mode, errors, unparseable replies). */
    private fun showPanel(text: String) {
        hidePanel()
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 16f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        val close = TextView(this).apply {
            this.text = "✕  بستن"; setTextColor(Color.WHITE); gravity = Gravity.END
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), 0)
            setOnClickListener { hidePanel() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(0xEE202020.toInt()) }
            addView(close)
            addView(ScrollView(context).apply { addView(tv) })
        }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, (realMetrics().heightPixels * 0.45).toInt())
            .apply { gravity = Gravity.BOTTOM }
        wm.addView(box, lp)
        panel = box
        panelText = tv
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelText = null
    }

    override fun onDestroy() {
        gen++
        main.removeCallbacks(timeout)
        hideMenu()
        hidePanel()
        removeOverlay()
        fab?.let { runCatching { wm.removeView(it) } }
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
