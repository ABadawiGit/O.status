package com.catch7ng.ostatus

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val d by lazy { resources.displayMetrics.density }
    private val bg = Color.rgb(8, 10, 14)
    private val card = Color.rgb(20, 23, 29)
    private val cardSoft = Color.rgb(27, 31, 39)
    private val primary = Color.WHITE
    private val secondary = Color.rgb(158, 164, 176)
    private val accent = Color.rgb(88, 166, 255)

    private var preview: RingPreviewView? = null
    private var colorValue: TextView? = null
    private var batteryColorValue: TextView? = null
    private var overlayValue: TextView? = null
    private var phoneValue: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLegacyColourMode()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
        preview?.invalidate()
        ensureOverlayStartedIfEnabled()
    }

    private fun migrateLegacyColourMode() {
        val current = prefs.getString("color_mode", null)
        if (current == null || current == "auto") prefs.edit().putString("color_mode", "white").apply()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(34), dp(20), dp(30))
        }

        root.addView(TextView(this).apply {
            text = "Camera Ring"
            textSize = 32f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(primary)
        })
        root.addView(TextView(this).apply {
            text = "Dual-SIM status around the punch-hole"
            textSize = 14f
            setTextColor(secondary)
            setPadding(0, dp(3), 0, dp(18))
        })

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(10))
            background = rounded(card, 22)
        }
        preview = RingPreviewView().also {
            previewCard.addView(it, LinearLayout.LayoutParams(-1, dp(190)))
        }
        previewCard.addView(TextView(this).apply {
            text = "SIM 2  ·  camera  ·  SIM 1"
            textSize = 12f
            setTextColor(secondary)
            gravity = Gravity.CENTER
        })
        root.addView(previewCard)

        root.addView(section("RING"))
        val ringCard = panel()
        ringCard.addView(toggleRow("Enable camera ring", prefs.getBoolean("duo_enabled", true)) { enabled ->
            prefs.edit().putBoolean("duo_enabled", enabled).apply()
            BootPrefs.setEnabled(this, enabled)
            if (enabled) requestOverlayOrStart() else stopService(Intent(this, StatusBarService::class.java))
        })
        ringCard.addView(divider())
        ringCard.addView(actionRow("Position & size", "Fine tune") {
            startActivity(Intent(this, PositionSizeActivity::class.java))
        })
        root.addView(ringCard)

        root.addView(section("APPEARANCE"))
        val appearance = panel()
        colorValue = valueChip(colorName())
        appearance.addView(actionRowWithView("Ring colour", colorValue!!) { showColourMenu() })
        appearance.addView(divider())

        batteryColorValue = valueChip(batteryColorName())
        appearance.addView(actionRowWithView("Battery colour", batteryColorValue!!) { showBatteryColourMenu() })
        appearance.addView(divider())

        appearance.addView(toggleRow("Show battery percentage", prefs.getBoolean("battery_percentage", true)) { enabled ->
            prefs.edit().putBoolean("battery_percentage", enabled).apply()
            BootPrefs.setBatteryPercentage(this, enabled)
            refreshOverlay()
            preview?.invalidate()
        })
        appearance.addView(divider())
        appearance.addView(toggleRow("Show 4G / 5G label", prefs.getBoolean("show_network_label", true)) { enabled ->
            prefs.edit().putBoolean("show_network_label", enabled).apply()
            refreshOverlay()
            preview?.invalidate()
        })
        root.addView(appearance)

        root.addView(section("PERMISSIONS"))
        val permissions = panel()
        overlayValue = permissionChip()
        permissions.addView(actionRowWithView("Display over apps", overlayValue!!) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        permissions.addView(divider())
        phoneValue = permissionChip()
        permissions.addView(actionRowWithView("Dual-SIM signal access", phoneValue!!) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 10)
        })
        root.addView(permissions)

        root.addView(TextView(this).apply {
            text = "Preview build · Camera Ring"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(100, 106, 118))
            setPadding(0, dp(24), 0, dp(4))
        })

        scroll.addView(root)
        setContentView(scroll)
        refreshPermissionStates()
    }

    private fun showColourMenu() {
        val labels = arrayOf("White", "Black", "Custom…")
        val current = prefs.getString("color_mode", "white") ?: "white"
        val checked = when (current) { "black" -> 1; "custom" -> 2; else -> 0 }
        AlertDialog.Builder(this)
            .setTitle("Ring colour")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                when (which) {
                    0 -> applyColour("white", Color.WHITE, dialog)
                    1 -> applyColour("black", Color.BLACK, dialog)
                    else -> {
                        dialog.dismiss()
                        IndicatorColorPickerDialog.show(this, prefs.getInt("custom_color", Color.rgb(88,166,255))) { color ->
                            prefs.edit().putString("color_mode", "custom").putInt("custom_color", color).apply()
                            colorValue?.text = String.format("#%06X", 0xFFFFFF and color)
                            refreshOverlay(); preview?.invalidate()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBatteryColourMenu() {
        val customEnabled = prefs.getBoolean("battery_custom_color_enabled", false)
        val checked = if (customEnabled) 1 else 0
        val labels = arrayOf("Match ring colour", "Custom…")
        AlertDialog.Builder(this)
            .setTitle("Battery colour")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (which == 0) {
                    prefs.edit().putBoolean("battery_custom_color_enabled", false).apply()
                    batteryColorValue?.text = "Ring"
                    dialog.dismiss()
                    refreshOverlay(); preview?.invalidate()
                } else {
                    dialog.dismiss()
                    IndicatorColorPickerDialog.show(this, prefs.getInt("battery_custom_color", Color.WHITE)) { color ->
                        prefs.edit()
                            .putBoolean("battery_custom_color_enabled", true)
                            .putInt("battery_custom_color", color)
                            .apply()
                        batteryColorValue?.text = String.format("#%06X", 0xFFFFFF and color)
                        refreshOverlay(); preview?.invalidate()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyColour(mode: String, color: Int, dialog: android.content.DialogInterface) {
        prefs.edit().putString("color_mode", mode).putInt("custom_color", color).apply()
        colorValue?.text = if (mode == "white") "White" else "Black"
        dialog.dismiss(); refreshOverlay(); preview?.invalidate()
    }

    private fun requestOverlayOrStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            ContextCompat.startForegroundService(this, Intent(this, StatusBarService::class.java))
        }
    }

    private fun ensureOverlayStartedIfEnabled() {
        if (prefs.getBoolean("duo_enabled", true) && Settings.canDrawOverlays(this)) {
            BootPrefs.setEnabled(this, true)
            ContextCompat.startForegroundService(this, Intent(this, StatusBarService::class.java))
        }
    }

    private fun refreshOverlay() {
        if (prefs.getBoolean("duo_enabled", true) && Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(this, Intent(this, StatusBarService::class.java))
        }
    }

    private fun refreshPermissionStates() {
        overlayValue?.let { setPermission(it, Settings.canDrawOverlays(this)) }
        phoneValue?.let {
            setPermission(it, ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun setPermission(v: TextView, granted: Boolean) {
        v.text = if (granted) "Allowed" else "Required"
        v.setTextColor(if (granted) Color.rgb(82, 210, 122) else Color.rgb(255, 190, 74))
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 11f
        letterSpacing = .12f
        setTextColor(secondary)
        setPadding(dp(4), dp(20), 0, dp(8))
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(card, 20)
    }

    private fun toggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = baseRow(title)
        val sw = Switch(this).apply {
            isChecked = checked
            buttonTintList = null
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        row.addView(sw, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun actionRow(title: String, value: String, click: () -> Unit): View {
        val chip = valueChip(value)
        return actionRowWithView(title, chip, click)
    }

    private fun actionRowWithView(title: String, right: View, click: () -> Unit): View {
        val row = baseRow(title)
        row.addView(right)
        row.setOnClickListener { click() }
        return row
    }

    private fun baseRow(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(11), dp(14), dp(11))
        minimumHeight = dp(58)
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 15.5f
            setTextColor(primary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun valueChip(value: String) = TextView(this).apply {
        text = value
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(206, 213, 225))
        setPadding(dp(10), 0, dp(10), 0)
        minimumHeight = dp(30)
        background = rounded(cardSoft, 10)
    }

    private fun permissionChip() = valueChip("")

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.rgb(39, 43, 52))
        layoutParams = LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(16) }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun colorName(): String = when (prefs.getString("color_mode", "white")) {
        "black" -> "Black"
        "custom" -> String.format("#%06X", 0xFFFFFF and prefs.getInt("custom_color", Color.WHITE))
        else -> "White"
    }

    private fun batteryColorName(): String = if (prefs.getBoolean("battery_custom_color_enabled", false)) {
        String.format("#%06X", 0xFFFFFF and prefs.getInt("battery_custom_color", Color.WHITE))
    } else "Ring"

    private inner class RingPreviewView : View(this) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val cx = width / 2f
            val cy = height / 2f + dp(4)
            val r = minOf(width, height) * .23f
            val color = when (prefs.getString("color_mode", "white")) {
                "black" -> Color.rgb(210,210,215)
                "custom" -> prefs.getInt("custom_color", accent)
                else -> Color.WHITE
            }
            val inactive = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            c.drawCircle(cx, cy, r * .45f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(7).toFloat()
            val rect = RectF(cx-r, cy-r, cx+r, cy+r)
            repeat(4) { i ->
                p.color = if (i < 3) color else inactive
                c.drawArc(rect, 108f + i*35f, 28f, false, p)
            }
            repeat(4) { i ->
                p.color = if (i < 2) color else inactive
                c.drawArc(rect, 288f + i*35f, 28f, false, p)
            }
            p.style = Paint.Style.FILL
            p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            p.textAlign = Paint.Align.CENTER
            val batteryScale = prefs.getInt("battery_label_size_pct", 120).coerceIn(70,220) / 100f
            p.textSize = dp(15).toFloat() * batteryScale
            p.color = if (prefs.getBoolean("battery_custom_color_enabled", false)) {
                prefs.getInt("battery_custom_color", color)
            } else color
            c.drawText("50%", cx, cy-r-dp(12), p)
            if (prefs.getBoolean("show_network_label", true)) {
                val networkScale = prefs.getInt("network_label_size_pct", 135).coerceIn(70,220) / 100f
                p.textSize = dp(12).toFloat() * networkScale
                p.color = color
                c.drawText("5G", cx, cy+r+dp(24), p)
            }
        }
    }

    private fun dp(v: Int) = (v * d + .5f).toInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionStates()
    }
}
