package com.catch7ng.ostatus

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PositionSizeActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val d by lazy { resources.displayMetrics.density }
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val pageColor get() = if (dark) Color.rgb(9,10,13) else Color.rgb(246,247,250)
    private val cardColor get() = if (dark) Color.rgb(24,26,31) else Color.WHITE
    private val primary get() = if (dark) Color.WHITE else Color.rgb(24,24,27)
    private val secondary get() = if (dark) Color.rgb(164,166,174) else Color.rgb(105,105,115)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageColor)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(28))
        }

        content.addView(TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(primary)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        content.addView(TextView(this).apply {
            text = "Ring calibration"
            textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(primary)
        })
        content.addView(TextView(this).apply {
            text = "Tune the ring and status labels independently around your camera cutout."
            textSize = 14f
            setTextColor(secondary)
            setPadding(0, dp(4), 0, dp(18))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(20).toFloat()
            }
        }

        addSlider(card, "Ring size", 50, 180, prefs.getInt("duo_scale_v3_pct", 100)) {
            prefs.edit().putInt("duo_scale_v3_pct", it).apply(); applyNow()
        }
        addSlider(card, "Thickness", 40, 180, prefs.getInt("ring_thickness_pct", 100)) {
            prefs.edit().putInt("ring_thickness_pct", it).apply(); applyNow()
        }
        addSlider(card, "4G / 5G size", 70, 220, prefs.getInt("network_label_size_pct", 135)) {
            prefs.edit().putInt("network_label_size_pct", it).apply(); applyNow()
        }
        addSlider(card, "Horizontal", -60, 60, prefs.getInt("duo_h_offset", 0)) {
            prefs.edit().putInt("duo_h_offset", it).apply(); applyNow()
        }
        addSlider(card, "Vertical", -60, 120, prefs.getInt("duo_v_offset_v2", 0)) {
            prefs.edit().putInt("duo_v_offset_v2", it).apply(); applyNow()
        }
        addSlider(card, "Status spacing", 60, 180, prefs.getInt("status_gap_pct", 100)) {
            prefs.edit().putInt("status_gap_pct", it).apply(); applyNow()
        }

        content.addView(card)
        content.addView(Button(this).apply {
            text = "Reset calibration"
            isAllCaps = false
            textSize = 15f
            setTextColor(primary)
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener {
                prefs.edit()
                    .putInt("duo_scale_v3_pct", 100)
                    .putInt("ring_thickness_pct", 100)
                    .putInt("network_label_size_pct", 135)
                    .putInt("duo_h_offset", 0)
                    .putInt("duo_v_offset_v2", 0)
                    .putInt("status_gap_pct", 100)
                    .apply()
                recreate()
                applyNow()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })

        scroll.addView(content)
        setContentView(scroll)
    }

    private fun addSlider(parent: LinearLayout, title: String, min: Int, max: Int, current: Int, onChange: (Int) -> Unit) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val label = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(primary)
        }
        val value = TextView(this).apply {
            text = formatValue(title, current)
            textSize = 14f
            gravity = Gravity.END
            setTextColor(secondary)
        }
        header.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(value, LinearLayout.LayoutParams(dp(82), -2))
        parent.addView(header)

        parent.addView(SeekBar(this).apply {
            this.max = max - min
            progress = (current - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val actual = min + progress
                    value.text = formatValue(title, actual)
                    onChange(actual)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(46)))
    }

    private fun formatValue(title: String, value: Int): String = when (title) {
        "Horizontal", "Vertical" -> if (value > 0) "+$value" else "$value"
        else -> "$value%"
    }

    private fun applyNow() {
        if (prefs.getBoolean("duo_enabled", true) && Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(this, Intent(this, StatusBarService::class.java))
        }
    }

    private fun dp(v: Int) = (v * d + .5f).toInt()
}
