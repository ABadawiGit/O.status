package com.catch7ng.ostatus

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class DuoIndicatorView(
    ctx: Context,
    private var colorMode: String,
    private var batteryPercentageEnabled: Boolean = false
) : View(ctx) {

    var batteryPercent = 0
    var charging = false

    private var wifiConnected = false
    private var wifiLevel = 0
    private var airplaneMode = false
    private val simLevels = intArrayOf(0, 0)
    private val animatedSimLevels = floatArrayOf(0f, 0f)
    private val signalAnimators = arrayOfNulls<ValueAnimator>(2)
    private var cellularNetworkLabel = ""
    private var trackedDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var networkDisplayTracker: NetworkDisplayTracker? = null
    private var customColor: Int = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getInt("custom_color", Color.WHITE)

    fun setBatteryPercentageEnabled(value: Boolean) {
        if (batteryPercentageEnabled == value) return
        batteryPercentageEnabled = value
        invalidate()
    }

    fun setColorMode(value: String) {
        val latestCustom = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("custom_color", Color.WHITE)
        val changed = colorMode != value || customColor != latestCustom
        colorMode = value
        customColor = latestCustom
        if (changed) invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshWifi()
            refreshCellular()
            refreshSystemStates()
            invalidate()
            handler.postDelayed(this, 1200)
        }
    }

    fun start() { handler.post(poll) }

    fun stop() {
        handler.removeCallbacks(poll)
        networkDisplayTracker?.stop()
        networkDisplayTracker = null
        trackedDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        signalAnimators.forEach { it?.cancel() }
    }

    private fun resolvedActiveColor(): Int = when (colorMode) {
        "black" -> Color.BLACK
        "custom" -> customColor
        else -> Color.WHITE
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun refreshSystemStates() {
        airplaneMode = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) { false }
    }

    private fun refreshWifi() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (wifiConnected) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val rssi = wm.connectionInfo.rssi
            wifiLevel = WifiManager.calculateSignalLevel(rssi, 4).coerceIn(0, 3)
        } else wifiLevel = 0
    }

    private fun animateSimLevel(slot: Int, target: Int) {
        val bounded = target.coerceIn(0, 4)
        if (slot !in 0..1) return
        if (simLevels[slot] == bounded && signalAnimators[slot]?.isRunning != true) return
        simLevels[slot] = bounded
        signalAnimators[slot]?.cancel()
        signalAnimators[slot] = ValueAnimator.ofFloat(animatedSimLevels[slot], bounded.toFloat()).apply {
            duration = 380L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedSimLevels[slot] = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun refreshCellular() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            animateSimLevel(0, 0)
            animateSimLevel(1, 0)
            cellularNetworkLabel = ""
            return
        }
        try {
            val newLevels = intArrayOf(0, 0)
            val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            val active = subscriptions.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex }.orEmpty()
            active.take(2).forEach { info ->
                val slot = info.simSlotIndex
                if (slot !in 0..1) return@forEach
                val tm = base.createForSubscriptionId(info.subscriptionId)
                @Suppress("DEPRECATION") val level = tm.signalStrength?.level?.coerceIn(0, 4) ?: 0
                newLevels[slot] = level
            }
            animateSimLevel(0, newLevels[0])
            animateSimLevel(1, newLevels[1])

            val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            val dataTm = if (SubscriptionManager.isValidSubscriptionId(defaultDataSubId)) base.createForSubscriptionId(defaultDataSubId) else base
            if (trackedDataSubId != defaultDataSubId) {
                networkDisplayTracker?.stop()
                trackedDataSubId = defaultDataSubId
                networkDisplayTracker = NetworkDisplayTracker(dataTm, context.mainExecutor) {
                    handler.post { refreshCellular(); invalidate() }
                }.also { it.start() }
            }
            @Suppress("DEPRECATION") val fallbackType = dataTm.dataNetworkType
            cellularNetworkLabel = NetworkDisplayTracker.label(networkDisplayTracker?.displayInfo, fallbackType)
                .let { if (it == "4G" || it == "5G") it else "" }
        } catch (_: Exception) {
            animateSimLevel(0, 0)
            animateSimLevel(1, 0)
            cellularNetworkLabel = ""
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val thicknessScale = prefs.getInt("ring_thickness_pct", 100).coerceIn(40, 180) / 100f
        val statusGapScale = prefs.getInt("status_gap_pct", 100).coerceIn(60, 220) / 100f
        val showNetworkLabel = prefs.getBoolean("show_network_label", true)

        val cx = width / 2f
        val cy = height / 2f
        val active = resolvedActiveColor()
        val inactive = withAlpha(active, 72)
        val radius = side * 0.31f
        val haloStroke = (side * 0.018f * thicknessScale).coerceAtLeast(side * 0.010f)

        // Subtle full halo gives the punch-hole a Dynamic-Island-like visual anchor.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = haloStroke
        paint.color = withAlpha(active, 30)
        canvas.drawCircle(cx, cy, radius, paint)

        drawSignalDotsOnArc(canvas, cx, cy, radius, 140f, 18f, if (airplaneMode) 0f else animatedSimLevels[1], active, inactive, side, thicknessScale)
        drawSignalDotsOnArc(canvas, cx, cy, radius, 320f, 18f, if (airplaneMode) 0f else animatedSimLevels[0], active, inactive, side, thicknessScale)

        drawBatteryDots(canvas, cx, cy, radius, side, active, inactive, statusGapScale)
        drawConnectivity(canvas, cx, cy, radius, side, active, inactive, statusGapScale, showNetworkLabel)
    }

    private fun drawSignalDotsOnArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        startDeg: Float,
        stepDeg: Float,
        level: Float,
        active: Int,
        inactive: Int,
        side: Float,
        thicknessScale: Float
    ) {
        val dotRadius = (side * 0.026f * thicknessScale).coerceAtLeast(side * 0.018f)
        paint.style = Paint.Style.FILL
        repeat(4) { index ->
            val angle = Math.toRadians((startDeg + index * stepDeg).toDouble())
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            val fill = (level - index).coerceIn(0f, 1f)
            val baseAlpha = Color.alpha(inactive)
            val alpha = (baseAlpha + (255 - baseAlpha) * fill).toInt()
            paint.color = withAlpha(active, alpha)
            canvas.drawCircle(x, y, dotRadius, paint)
        }
    }

    private fun drawBatteryDots(canvas: Canvas, cx: Float, cy: Float, radius: Float, side: Float, active: Int, inactive: Int, gapScale: Float) {
        if (!batteryPercentageEnabled) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val sizeScale = prefs.getInt("battery_label_size_pct", 120).coerceIn(70, 220) / 100f
        val useCustom = prefs.getBoolean("battery_custom_color_enabled", false)
        val batteryColor = if (useCustom) prefs.getInt("battery_custom_color", active) else active
        val batteryInactive = withAlpha(batteryColor, 72)
        val lit = when {
            batteryPercent >= 75 -> 4
            batteryPercent >= 50 -> 3
            batteryPercent >= 25 -> 2
            batteryPercent > 0 -> 1
            else -> 0
        }
        val dotR = side * 0.018f * sizeScale
        val gap = dotR * 2.8f
        val y = cy - radius - side * 0.13f * gapScale
        val startX = cx - gap * 1.5f
        paint.style = Paint.Style.FILL
        repeat(4) { i ->
            paint.color = if (i < lit) batteryColor else batteryInactive
            canvas.drawCircle(startX + i * gap, y, dotR, paint)
        }
    }

    private fun drawConnectivity(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, side: Float,
        active: Int, inactive: Int, gapScale: Float, showNetworkLabel: Boolean
    ) {
        val baseY = cy + radius + side * 0.13f * gapScale

        if (airplaneMode) {
            drawAirplane(canvas, cx, baseY, side, active)
            return
        }

        if (wifiConnected) {
            drawWifiDots(canvas, cx, baseY, side, active, inactive)
            return
        }

        if (showNetworkLabel && cellularNetworkLabel.isNotEmpty()) {
            drawNetworkLabel(canvas, cellularNetworkLabel, cx, baseY, side, active)
        }
    }

    private fun drawNetworkLabel(canvas: Canvas, label: String, cx: Float, cy: Float, side: Float, color: Int) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val labelScale = prefs.getInt("network_label_size_pct", 135).coerceIn(70, 220) / 100f
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = side * 0.13f * labelScale
        val fm = paint.fontMetrics
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun drawWifiDots(canvas: Canvas, cx: Float, cy: Float, side: Float, active: Int, inactive: Int) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val scale = prefs.getInt("network_label_size_pct", 135).coerceIn(70, 220) / 100f
        val dotR = side * 0.017f * scale
        val gap = dotR * 2.8f
        val startX = cx - gap * 1.5f
        val lit = (wifiLevel + 1).coerceIn(1, 4)
        paint.style = Paint.Style.FILL
        repeat(4) { i ->
            paint.color = if (i < lit) active else inactive
            canvas.drawCircle(startX + i * gap, cy, dotR, paint)
        }
    }

    private fun drawAirplane(canvas: Canvas, cx: Float, cy: Float, side: Float, color: Int) {
        val u = side * 0.018f
        val p = Path().apply {
            moveTo(cx, cy - 4.2f * u); lineTo(cx + 1.2f * u, cy - 0.7f * u)
            lineTo(cx + 4.4f * u, cy + 0.6f * u); lineTo(cx + 1.3f * u, cy + 0.6f * u)
            lineTo(cx, cy + 3.2f * u); lineTo(cx - 1.3f * u, cy + 0.6f * u)
            lineTo(cx - 4.4f * u, cy + 0.6f * u); lineTo(cx - 1.2f * u, cy - 0.7f * u); close()
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(p, paint)
    }
}
