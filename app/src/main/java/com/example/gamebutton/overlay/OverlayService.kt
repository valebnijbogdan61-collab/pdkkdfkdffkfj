package com.example.gamebutton.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.gamebutton.R
import com.example.gamebutton.ai.GeminiVision
import com.example.gamebutton.capture.ScreenCapture
import com.example.gamebutton.data.Prefs
import com.example.gamebutton.input.ActionPlanner
import com.example.gamebutton.input.TapInjector
import com.example.gamebutton.ocr.OcrEngine
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Плаваюча кнопка. Раз на секунду (пауза ПІСЛЯ скану, тобто не частіше 1/с) знімає екран,
 * шукає OCR-ом що треба натиснути. Кнопка світиться, коли щось знайдено.
 * Тап = натиснути знайдене. Довге утримання = авто-режим (сам тисне).
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var button: ImageView? = null
    private var capture: ScreenCapture? = null
    private var exec: ScheduledExecutorService? = null
    private val ocr by lazy { OcrEngine() }
    private val planner = ActionPlanner()

    @Volatile private var latest: ActionPlanner.Plan? = null
    @Volatile private var auto = false
    private var lastKey = ""
    private var lastTime = 0L
    private var lastAi = 0L
    private var aiQueue: List<ActionPlanner.Plan> = emptyList()
    private var aiIdx = 0
    private var dialIdx = 0
    private var lastHash: IntArray? = null
    private var aiQueueTime = 0L
    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || button != null) {
            if (button == null) stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        val mp: MediaProjection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent!!.getIntExtra(EXTRA_CODE, 0), data)
        capture = ScreenCapture(this, mp)
        showButton()
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ runCatching { scanOnce() } }, 800, SCAN_DELAY_MS, TimeUnit.MILLISECONDS)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exec?.shutdownNow()
        capture?.release()
        button?.let { runCatching { wm.removeView(it) } }
        button = null
        super.onDestroy()
    }

    // ---------- скан і натискання ----------

    private fun scanOnce() {
        val bmp = capture?.grab() ?: return
        val plan = planner.plan(ocr.scan(bmp))
        latest = plan
        ui.post { button?.alpha = if (plan != null) 1f else 0.55f }
        if (auto && plan != null) press(plan, force = false)
        else if (plan == null) askAi(bmp, force = false, pressNow = auto)
    }

    private fun press(plan: ActionPlanner.Plan, force: Boolean) {
        if (plan.label == "dial") {
            // циферблат: лише за тапом по кнопці, по одній цифрі, по колу
            if (!force || plan.points.isEmpty()) return
            TapInjector.tapSequence(listOf(plan.points[dialIdx++ % plan.points.size]))
            return
        }
        val now = SystemClock.elapsedRealtime()
        val cd = if (plan.label.startsWith("code:")) CODE_COOLDOWN_MS else COOLDOWN_MS
        if (!force && plan.label == lastKey && now - lastTime < cd) return
        lastKey = plan.label
        lastTime = now
        TapInjector.tapSequence(plan.points)
    }

    private fun onButtonClick() {
        val p = latest
        if (p != null) { exec?.execute { press(p, force = true) }; return }
        exec?.execute {
            // є свіжий список кнопок від Gemini -> наступна по колу
            if (aiQueue.isNotEmpty() && SystemClock.elapsedRealtime() - aiQueueTime < QUEUE_TTL_MS) {
                pressNext()
                return@execute
            }
            val b = capture?.grab()
            if (b == null || !askAi(b, force = true)) toast(getString(R.string.nothing_found))
        }
    }

    /** Gemini vision: лише коли локально нічого не знайдено; з лімітом, щоб не з'їсти безкоштовну квоту. */
    private fun askAi(bmp: Bitmap, force: Boolean, pressNow: Boolean = true): Boolean {
        val key = prefs.getString(Prefs.KEY, "").orEmpty()
        if (!prefs.getBoolean(Prefs.AI_ON, false) || key.isBlank()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastAi < (if (force) 1000L else AI_INTERVAL_MS)) return false
        // фоновий префетч: питаємо Gemini лише коли кадр помітно змінився -> до тапу відповідь уже готова
        val h = hash(bmp)
        if (!force && !changed(h, lastHash)) return false
        lastHash = h
        lastAi = now
        return try {
            val model = prefs.getString(Prefs.MODEL, Prefs.DEFAULT_MODEL).orEmpty().ifBlank { Prefs.DEFAULT_MODEL }
            val plans = GeminiVision(key, model).ask(bmp)
            if (plans.isEmpty()) false else {
                aiQueue = plans; aiIdx = 0; aiQueueTime = now
                if (pressNow) pressNext()
                true
            }
        } catch (e: GeminiVision.RateLimited) {
            if (force) toast(getString(R.string.ai_limit))
            false
        } catch (e: Exception) {
            if (force) toast("AI: ${e.message}")
            false
        }
    }

    /** Тисне наступну кнопку зі списку Gemini (кожен тап — наступна, по колу). */
    private fun pressNext() {
        val q = aiQueue
        if (q.isEmpty()) return
        val p = q[aiIdx % q.size]
        aiIdx++
        press(p.copy(label = p.label + "#" + aiIdx), force = true)
    }

    private fun hash(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        return IntArray(256) { val c = s.getPixel(it % 16, it / 16); (Color.red(c) + Color.green(c) + Color.blue(c)) / 3 }
    }

    private fun changed(h: IntArray, o: IntArray?): Boolean =
        o == null || h.indices.sumOf { abs(h[it] - o[it]) } / 256 > 8

    private fun toast(msg: String) = ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun toggleAuto() {
        auto = !auto
        if (auto) button?.setColorFilter(0xFF4CAF50.toInt(), PorterDuff.Mode.SRC_IN)
        else button?.clearColorFilter()
        Toast.makeText(this, if (auto) R.string.auto_on else R.string.auto_off, Toast.LENGTH_SHORT).show()
    }

    // ---------- UI кнопки ----------

    private fun showButton() {
        val size = (BUTTON_SIZE_DP * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.POS_X, 0)
            y = prefs.getInt(Prefs.POS_Y, 300)
        }

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_interact_button)
            alpha = 0.55f
            setOnTouchListener(DragTouchListener(lp))
        }
        wm.addView(view, lp)
        button = view
    }

    private inner class DragTouchListener(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private var longFired = false
        private val longPress = Runnable { longFired = true; toggleAuto() }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false
                    ui.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        ui.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    if (moved) prefs.edit().putInt(Prefs.POS_X, lp.x).putInt(Prefs.POS_Y, lp.y).apply()
                    if (!moved && !longFired) onButtonClick()
                }
                MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
            }
            return true
        }
    }

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_running))
            .setOngoing(true)
            .build()
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Build.VERSION.SDK_INT >= 29 -> startForeground(
                NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            else -> startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val EXTRA_CODE = "projection_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1
        private const val BUTTON_SIZE_DP = 56
        private const val SCAN_DELAY_MS = 1000L
        private const val COOLDOWN_MS = 3000L
        private const val AI_INTERVAL_MS = 8_000L
        private const val QUEUE_TTL_MS = 30_000L
        private const val CODE_COOLDOWN_MS = 20000L
    }
}
