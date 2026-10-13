package com.catch7ng.ostatus

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*

class StatusBarService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var v: DuoIndicatorView
    private lateinit var lp: WindowManager.LayoutParams
    private var baseXPx = 0
    private var baseYPx = 0
    private var overlayAttached = false
    private var autoHideInFullScreen = true
    private var hiddenForFullScreen = false

    private fun displaySizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    /**
     * Camera-ring positioning is intentionally screen-centred.
     * X is now a signed calibration offset from the physical screen centre,
     * instead of the old O.status START/END edge margin.
     */
    private fun clampUserPosition(density: Float) {
        val (screenW, screenH) = displaySizePx()
        val overlayW = lp.width.coerceAtLeast(1)
        val overlayH = lp.height.coerceAtLeast(1)
        val pref = getSharedPreferences("settings", MODE_PRIVATE)

        val maxCenterOffsetX = ((screenW - overlayW) / 2).coerceAtLeast(0)
        val requestedX = (pref.getInt("duo_h_offset", 0) * density).toInt()
        baseXPx = requestedX.coerceIn(-maxCenterOffsetX, maxCenterOffsetX)

        val requestedY = ((pref.getInt("y5", 0) + 7 + pref.getInt("duo_v_offset_v2", 0)) * density).toInt()
        baseYPx = requestedY.coerceIn(0, (screenH - overlayH).coerceAtLeast(0))

        lp.x = baseXPx
        lp.y = baseYPx
    }

    private fun ensureOverlayAttached() {
        if (!BootPrefs.isEnabled(this) || !Settings.canDrawOverlays(this)) return
        if (!::wm.isInitialized || !::v.isInitialized || !::lp.isInitialized) return
        if (!overlayAttached || !v.isAttachedToWindow) {
            try {
                if (v.parent != null) try { wm.removeViewImmediate(v) } catch (_: Exception) {}
                wm.addView(v, lp)
                overlayAttached = true
            } catch (_: Exception) { }
        } else {
            try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
        }
        v.invalidate()
    }

    private fun applyPosition() {
        if (!::wm.isInitialized || !::v.isInitialized || !::lp.isInitialized) return
        if (lp.x != baseXPx || lp.y != baseYPx) {
            lp.x = baseXPx
            lp.y = baseYPx
            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        }
    }

    private fun updateFullScreenVisibility() {
        if (!::v.isInitialized || !v.isAttachedToWindow) return
        if (!autoHideInFullScreen) {
            if (hiddenForFullScreen) {
                hiddenForFullScreen = false
                v.alpha = 1f
                applyPosition()
            }
            return
        }

        val insets = v.rootWindowInsets ?: return
        val statusBarVisible = if (Build.VERSION.SDK_INT >= 30) {
            insets.isVisible(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            (v.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
        }
        val shouldHide = !statusBarVisible
        if (shouldHide != hiddenForFullScreen) {
            hiddenForFullScreen = shouldHide
            if (shouldHide) v.alpha = 0f
            else {
                v.alpha = 1f
                applyPosition()
            }
        }
    }

    private fun startFullScreenWatcher() {
        hiddenForFullScreen = false
        if (!::v.isInitialized) return
        v.alpha = 1f
        v.setOnApplyWindowInsetsListener { _, insets ->
            updateFullScreenVisibility()
            insets
        }
        v.requestApplyInsets()
        v.post { updateFullScreenVisibility() }
    }

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!::v.isInitialized) return
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            v.batteryPercent = if (scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
            v.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            v.invalidate()
        }
    }

    private fun resolvedColorMode(): String {
        val pref = getSharedPreferences("settings", MODE_PRIVATE)
        return when (val mode = pref.getString("color_mode", null)) {
            "black", "white", "custom" -> mode
            else -> if (pref.getBoolean("black5", false)) "black" else "white"
        }
    }

    private fun refreshColorMode() {
        if (!::v.isInitialized) return
        v.setColorMode(resolvedColorMode())
    }

    private fun refreshLayoutFromPrefs() {
        if (!::wm.isInitialized || !::v.isInitialized || !::lp.isInitialized) return
        val pref = getSharedPreferences("settings", MODE_PRIVATE)
        val density = resources.displayMetrics.density

        val baseScale = .62f + pref.getInt("size5", 24) / 200f
        val scale = baseScale * 1.56f * (pref.getInt("duo_scale_v3_pct", 100) / 100f)
        lp.width = (36 * density * scale).toInt()
        lp.height = (36 * density * scale).toInt()
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        clampUserPosition(density)
        applyPosition()
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val pref = getSharedPreferences("settings", MODE_PRIVATE)
        autoHideInFullScreen = true
        if (!BootPrefs.isEnabled(this)) { stopSelf(); return }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("duo5", "O.status overlay", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            5,
            Notification.Builder(this, "duo5")
                .setContentTitle("O.status")
                .setContentText("Active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        )

        val d = resources.displayMetrics.density
        val baseScale = .62f + pref.getInt("size5", 24) / 200f
        val scale = baseScale * 1.56f * (pref.getInt("duo_scale_v3_pct", 100) / 100f)
        val resolvedMode = resolvedColorMode()
        v = DuoIndicatorView(
            this,
            resolvedMode,
            pref.getBoolean("battery_percentage", BootPrefs.batteryPercentage(this))
        )
        refreshColorMode()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        lp = WindowManager.LayoutParams(
            (36 * d * scale).toInt(),
            (36 * d * scale).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = ((pref.getInt("y5", 0) + 7 + pref.getInt("duo_v_offset_v2", 0)) * d).toInt()
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        clampUserPosition(d)
        wm.addView(v, lp)
        overlayAttached = true
        registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        applyPosition()
        startFullScreenWatcher()
        v.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshColorMode()
        if (::v.isInitialized) {
            val pref = getSharedPreferences("settings", MODE_PRIVATE)
            v.setBatteryPercentageEnabled(
                pref.getBoolean("battery_percentage", BootPrefs.batteryPercentage(this))
            )
        }
        refreshLayoutFromPrefs()
        ensureOverlayAttached()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (BootPrefs.isEnabled(this) && Settings.canDrawOverlays(this)) {
            TaskRecoveryReceiver.schedule(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryRx) } catch (_: Exception) {}
        if (::v.isInitialized) v.setOnApplyWindowInsetsListener(null)
        if (::v.isInitialized) v.stop()
        if (::wm.isInitialized && ::v.isInitialized) {
            try {
                wm.removeView(v)
                overlayAttached = false
            } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
