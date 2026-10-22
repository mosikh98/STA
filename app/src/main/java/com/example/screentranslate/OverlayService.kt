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
import kotlin.math.abs

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
    private var panel: View? = null
    private var overlay: View? = null
    private var pending = false
    private var busy = false

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
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(v, lp); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap(); true }
                else -> false
            }
        }
        wm.addView(tv, lp)
        fab = tv; fabLp = lp
    }

    private fun onTap() {
        if (busy) return
        if (overlay != null) { removeOverlay(); return }   // second tap clears the translation
        hidePanel()
        if (Store.load(this).key.isBlank()) {
            Toast.makeText(this, "ابتدا API Key را در تنظیمات وارد کنید", Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        // follow screen rotation (e.g. user started the app in portrait, game is landscape)
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
        fab?.text = "…"
        Thread {
            val scaled = if (bmp.width > 1280)
                Bitmap.createScaledBitmap(bmp, 1280, bmp.height * 1280 / bmp.width, true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val r = Api.translate(cfg, b64, scaled.width, scaled.height)

            val items: List<Item>? =
                if (cfg.boxMode != 3)
                    r.getOrNull()?.let { Overlay.parse(it, cfg.boxMode, scaled.width, scaled.height) }
                        ?.map { item -> item.copy(color = Overlay.sample(bmp, item)) }
                else null

            main.post {
                busy = false
                fab?.text = "ترجمه"
                when {
                    r.isFailure -> showPanel("خطا: ${r.exceptionOrNull()?.message}")
                    items == null -> showPanel(r.getOrNull().orEmpty())
                    items.isEmpty() -> Toast.makeText(this, "متنی برای ترجمه پیدا نشد", Toast.LENGTH_SHORT).show()
                    else -> showOverlay(items)
                }
            }
        }.start()
    }

    /** Paints translations over the original text. Touches pass through to the app below. */
    private fun showOverlay(items: List<Item>) {
        removeOverlay()
        val dm = realMetrics()
        val v = TranslationView(this, items)
        val lp = overlayParams(
            dm.widthPixels, dm.heightPixels,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.8f   // Android 12+ only lets touches pass through overlays with alpha <= 0.8
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        overlay = v
        fab?.let { f ->
            wm.removeView(f)          // re-add so the button stays above the overlay
            wm.addView(f, fabLp)
            f.text = "پاک"
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        fab?.text = "ترجمه"
    }

    /** Fallback: plain text panel (panel mode, errors, unparseable replies). */
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
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        hidePanel()
        overlay?.let { runCatching { wm.removeView(it) } }
        fab?.let { runCatching { wm.removeView(it) } }
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
