package com.catch7ng.ostatus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

class DuoIndicatorView(
    ctx: Context,
    private var colorMode: String,
    private var batteryPercentageEnabled: Boolean = false
) : View(ctx) {

    var batteryPercent = 0
    var charging = false

    private var powerSaveMode = false
    private var wifiConnected = false
    private var wifiLevel = 0
    private var mobileDataConnected = false
    private var airplaneMode = false
    private val simLevels = intArrayOf(0, 0)
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
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerSaveMode = pm.isPowerSaveMode
        airplaneMode = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) { false }
    }

    private fun refreshWifi() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        mobileDataConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (wifiConnected) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val rssi = wm.connectionInfo.rssi
            wifiLevel = WifiManager.calculateSignalLevel(rssi, 4).coerceIn(0, 3)
        } else wifiLevel = 0
    }

    private fun resetSimState() { simLevels[0] = 0; simLevels[1] = 0 }

    private fun refreshCellular() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            resetSimState(); cellularNetworkLabel = ""; return
        }
        try {
            resetSimState()
            val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            val active = subscriptions.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex }.orEmpty()
            active.take(2).forEach { info ->
                val slot = info.simSlotIndex
                if (slot !in 0..1) return@forEach
                val tm = base.createForSubscriptionId(info.subscriptionId)
                @Suppress("DEPRECATION") val level = tm.signalStrength?.level?.coerceIn(0, 4) ?: 0
                simLevels[slot] = level
            }

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
            resetSimState(); cellularNetworkLabel = ""
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val thicknessScale = prefs.getInt("ring_thickness_pct", 100).coerceIn(40, 160) / 100f
        val statusGapScale = prefs.getInt("status_gap_pct", 100).coerceIn(60, 180) / 100f
        val showNetworkLabel = prefs.getBoolean("show_network_label", true)

        val cx = width / 2f
        val cy = height / 2f
        val active = resolvedActiveColor()
        val inactive = withAlpha(active, 64)
        val radius = side * 0.285f
        val stroke = side * 0.052f * thicknessScale
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND

        drawSegmentedSignalArc(canvas, rect, 108f, 29f, 6f, if (airplaneMode) 0 else simLevels[1], active, inactive)
        drawSegmentedSignalArc(canvas, rect, 288f, 29f, 6f, if (airplaneMode) 0 else simLevels[0], active, inactive)

        drawBattery(canvas, cx, cy, radius, side, active, statusGapScale)
        drawConnectivity(canvas, cx, cy, radius, side, active, inactive, statusGapScale, showNetworkLabel)
    }

    private fun drawSegmentedSignalArc(
        canvas: Canvas, rect: RectF, start: Float, segmentSweep: Float, segmentGap: Float,
        level: Int, active: Int, inactive: Int
    ) {
        val lit = level.coerceIn(0, 4)
        repeat(4) { index ->
            paint.color = if (index < lit) active else inactive
            canvas.drawArc(rect, start + index * (segmentSweep + segmentGap), segmentSweep, false, paint)
        }
    }

    private fun drawBattery(canvas: Canvas, cx: Float, cy: Float, radius: Float, side: Float, active: Int, gapScale: Float) {
        val batteryColor = when {
            batteryPercent >= 100 || charging -> Color.rgb(52, 199, 89)
            powerSaveMode -> Color.rgb(255, 204, 0)
            batteryPercent <= 20 -> Color.rgb(255, 59, 48)
            else -> active
        }
        paint.style = Paint.Style.FILL
        paint.color = batteryColor
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = side * 0.145f
        val batteryText = if (batteryPercentageEnabled || batteryPercent <= 20) "${batteryPercent.coerceIn(0,100)}%" else batteryPercent.coerceIn(0,100).toString()
        val fm = paint.fontMetrics
        val centerY = cy - radius - side * 0.11f * gapScale
        canvas.drawText(batteryText, cx, centerY - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun drawConnectivity(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, side: Float,
        active: Int, inactive: Int, gapScale: Float, showNetworkLabel: Boolean
    ) {
        val centerY = cy + radius + side * 0.115f * gapScale
        when {
            wifiConnected -> drawWifi(canvas, cx, centerY, side, active, inactive)
            airplaneMode -> drawAirplane(canvas, cx, centerY, side, active)
            showNetworkLabel && mobileDataConnected && cellularNetworkLabel.isNotEmpty() -> {
                paint.style = Paint.Style.FILL
                paint.color = active
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = side * 0.135f
                val fm = paint.fontMetrics
                canvas.drawText(cellularNetworkLabel, cx, centerY - (fm.ascent + fm.descent) / 2f, paint)
            }
        }
    }

    private fun drawWifi(canvas: Canvas, cx: Float, cy: Float, side: Float, active: Int, inactive: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = side * 0.026f
        val outerRadius = side * 0.082f
        val midRadius = side * 0.055f
        paint.color = if (wifiLevel >= 2) active else inactive
        canvas.drawArc(RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius), 215f, 110f, false, paint)
        paint.color = if (wifiLevel >= 1) active else inactive
        canvas.drawArc(RectF(cx - midRadius, cy - midRadius, cx + midRadius, cy + midRadius), 220f, 100f, false, paint)
        paint.style = Paint.Style.FILL
        paint.color = active
        canvas.drawCircle(cx, cy + side * 0.038f, side * 0.014f, paint)
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
