package com.catch7ng.ostatus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
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

    // Slot order is deliberate: [0] = SIM 1, [1] = SIM 2.
    private val simLevels = intArrayOf(0, 0)
    private val simSubIds = intArrayOf(
        SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        SubscriptionManager.INVALID_SUBSCRIPTION_ID
    )

    private var cellularNetworkLabel = ""
    private var trackedDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var networkDisplayTracker: NetworkDisplayTracker? = null
    private var shizukuAutoBlack: Boolean? = null
    private var customColor: Int = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getInt("custom_color", Color.WHITE)

    fun setShizukuAutoBlack(value: Boolean?) {
        if (shizukuAutoBlack == value) return
        shizukuAutoBlack = value
        invalidate()
    }

    fun setBatteryPercentageEnabled(value: Boolean) {
        if (batteryPercentageEnabled == value) return
        batteryPercentageEnabled = value
        invalidate()
    }

    fun setColorMode(value: String) {
        if (colorMode == value) return
        colorMode = value
        shizukuAutoBlack = null
        customColor = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("custom_color", Color.WHITE)
        invalidate()
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

    private fun isBlackIndicator(): Boolean = when (colorMode) {
        "black" -> true
        "white" -> false
        else -> shizukuAutoBlack ?: ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES)
    }

    private fun resolvedActiveColor(): Int = when (colorMode) {
        "black" -> Color.BLACK
        "white" -> Color.WHITE
        "custom" -> customColor
        else -> if (isBlackIndicator()) Color.BLACK else Color.WHITE
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun refreshSystemStates() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerSaveMode = pm.isPowerSaveMode
        airplaneMode = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) {
            false
        }
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
        } else {
            wifiLevel = 0
        }
    }

    private fun resetSimState() {
        simLevels[0] = 0
        simLevels[1] = 0
        simSubIds[0] = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        simSubIds[1] = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private fun refreshCellular() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            resetSimState()
            cellularNetworkLabel = ""
            return
        }

        try {
            resetSimState()
            val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            val active = subscriptions.activeSubscriptionInfoList
                ?.sortedBy { it.simSlotIndex }
                .orEmpty()

            active.take(2).forEach { info ->
                val slot = info.simSlotIndex
                if (slot !in 0..1) return@forEach

                val tm = base.createForSubscriptionId(info.subscriptionId)
                @Suppress("DEPRECATION")
                val level = tm.signalStrength?.level?.coerceIn(0, 4) ?: 0
                simLevels[slot] = level
                simSubIds[slot] = info.subscriptionId
            }

            val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            val dataTm = if (SubscriptionManager.isValidSubscriptionId(defaultDataSubId)) {
                base.createForSubscriptionId(defaultDataSubId)
            } else {
                base
            }

            if (trackedDataSubId != defaultDataSubId) {
                networkDisplayTracker?.stop()
                trackedDataSubId = defaultDataSubId
                networkDisplayTracker = NetworkDisplayTracker(dataTm, context.mainExecutor) {
                    handler.post {
                        refreshCellular()
                        invalidate()
                    }
                }.also { it.start() }
            }

            @Suppress("DEPRECATION") val fallbackType = dataTm.dataNetworkType
            cellularNetworkLabel = NetworkDisplayTracker.label(
                networkDisplayTracker?.displayInfo,
                fallbackType
            ).let { if (it == "4G" || it == "5G") it else "" }
        } catch (_: Exception) {
            resetSimState()
            cellularNetworkLabel = ""
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)

        val active = resolvedActiveColor()
        val inactive = withAlpha(active, if (colorMode == "custom") 74 else 72)

        val s = min(width, height) / 436f
        fun X(v: Float) = width / 2f + (v - 237f) * s
        fun Y(v: Float) = height / 2f + (v - 211f) * s

        val cx = X(237f)
        val cy = Y(211f)
        val r = 154f * s
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        // Camera ring: left half = SIM 2, right half = SIM 1.
        // A gap at the top is reserved for battery text and a gap at the bottom
        // is reserved for Wi-Fi / mobile-data / airplane state.
        val leftStart = 110f
        val leftSweep = 140f
        val rightStart = 290f
        val rightSweep = 140f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 24f * s
        paint.strokeCap = Paint.Cap.ROUND

        drawSignalArc(c, rect, leftStart, leftSweep, if (airplaneMode) 0 else simLevels[1], active, inactive)
        drawSignalArc(c, rect, rightStart, rightSweep, if (airplaneMode) 0 else simLevels[0], active, inactive)

        val batteryColor = when {
            batteryPercent >= 100 -> Color.rgb(52, 199, 89)
            charging -> Color.rgb(52, 199, 89)
            powerSaveMode -> Color.rgb(255, 204, 0)
            batteryPercent <= 20 -> Color.rgb(255, 59, 48)
            else -> active
        }

        // Battery lives above the camera ring and never occupies the camera hole.
        paint.style = Paint.Style.FILL
        paint.color = batteryColor
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 58f * s
        val batteryText = if (batteryPercentageEnabled || batteryPercent <= 20) {
            "${batteryPercent.coerceIn(0, 100)}%"
        } else {
            batteryPercent.coerceIn(0, 100).toString()
        }
        val batteryFm = paint.fontMetrics
        val batteryY = Y(36f) - (batteryFm.ascent + batteryFm.descent) / 2f
        c.drawText(batteryText, cx, batteryY, paint)

        // Connectivity status sits below the camera opening.
        when {
            wifiConnected -> drawWifiCompact(c, s, active, inactive, ::X, ::Y)
            airplaneMode -> drawAirplaneCompact(c, s, active, ::X, ::Y)
            mobileDataConnected && cellularNetworkLabel.isNotEmpty() -> {
                paint.style = Paint.Style.FILL
                paint.color = active
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 64f * s
                val fm = paint.fontMetrics
                val textY = Y(382f) - (fm.ascent + fm.descent) / 2f
                c.drawText(cellularNetworkLabel, cx, textY, paint)
            }
        }
    }

    private fun drawSignalArc(
        c: Canvas,
        rect: RectF,
        start: Float,
        sweep: Float,
        level: Int,
        active: Int,
        inactive: Int
    ) {
        val normalized = level.coerceIn(0, 4) / 4f
        paint.color = inactive
        c.drawArc(rect, start, sweep, false, paint)
        if (normalized > 0f) {
            paint.color = active
            c.drawArc(rect, start, sweep * normalized, false, paint)
        }
    }

    private fun drawWifiCompact(
        c: Canvas,
        s: Float,
        active: Int,
        inactive: Int,
        X: (Float) -> Float,
        Y: (Float) -> Float
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 13f * s

        val outer = Path().apply {
            moveTo(X(198f), Y(363f))
            cubicTo(X(220f), Y(343f), X(254f), Y(343f), X(276f), Y(363f))
        }
        paint.color = if (wifiLevel >= 2) active else inactive
        c.drawPath(outer, paint)

        val inner = Path().apply {
            moveTo(X(216f), Y(381f))
            cubicTo(X(228f), Y(369f), X(246f), Y(369f), X(258f), Y(381f))
        }
        paint.color = if (wifiLevel >= 1) active else inactive
        c.drawPath(inner, paint)

        paint.style = Paint.Style.FILL
        paint.color = active
        c.drawCircle(X(237f), Y(397f), 7f * s, paint)
    }

    private fun drawAirplaneCompact(
        c: Canvas,
        s: Float,
        color: Int,
        X: (Float) -> Float,
        Y: (Float) -> Float
    ) {
        val p = Path().apply {
            moveTo(X(237f), Y(347f))
            lineTo(X(244f), Y(374f))
            lineTo(X(274f), Y(389f))
            lineTo(X(244f), Y(387f))
            lineTo(X(237f), Y(407f))
            lineTo(X(230f), Y(387f))
            lineTo(X(200f), Y(389f))
            lineTo(X(230f), Y(374f))
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(p, paint)
    }
}
