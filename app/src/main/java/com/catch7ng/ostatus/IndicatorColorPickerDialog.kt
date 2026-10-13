package com.catch7ng.ostatus

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

object IndicatorColorPickerDialog {

    fun show(context: Context, initialColor: Int, onSelected: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + .5f).toInt()

        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        var current = initialColor
        var syncing = false

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val preview = TextView(context).apply {
            text = "Camera Ring"
            gravity = Gravity.CENTER
            textSize = 16f
            minimumHeight = dp(58)
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(58)))

        root.addView(TextView(context).apply {
            text = "Quick palette"
            textSize = 13f
            setPadding(0, dp(18), 0, dp(8))
        })

        val swatchGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val palette = intArrayOf(
            Color.WHITE, Color.BLACK, Color.rgb(255,59,48), Color.rgb(255,149,0), Color.rgb(255,204,0), Color.rgb(52,199,89),
            Color.rgb(0,199,190), Color.rgb(48,176,199), Color.rgb(50,173,230), Color.rgb(0,122,255), Color.rgb(88,86,214), Color.rgb(175,82,222),
            Color.rgb(255,45,85), Color.rgb(162,132,94), Color.rgb(142,142,147), Color.rgb(10,132,255), Color.rgb(100,210,255), Color.rgb(191,90,242)
        )

        val hue = SeekBar(context).apply { max = 360 }
        val saturation = SeekBar(context).apply { max = 100 }
        val brightness = SeekBar(context).apply { max = 100 }
        val hex = EditText(context).apply {
            hint = "#RRGGBB"
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(7))
        }

        fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), Color.argb(55, 128, 128, 128))
        }

        fun contrast(background: Int): Int {
            val l = (0.299 * Color.red(background) + 0.587 * Color.green(background) + 0.114 * Color.blue(background)) / 255.0
            return if (l > .58) Color.BLACK else Color.WHITE
        }

        fun updateUi(color: Int, updateHex: Boolean = true, updateSliders: Boolean = true) {
            current = color
            preview.background = rounded(color, dp(14).toFloat())
            preview.setTextColor(contrast(color))
            if (updateSliders) {
                Color.colorToHSV(color, hsv)
                hue.progress = hsv[0].toInt().coerceIn(0, 360)
                saturation.progress = (hsv[1] * 100).toInt().coerceIn(0, 100)
                brightness.progress = (hsv[2] * 100).toInt().coerceIn(0, 100)
            }
            if (updateHex) {
                val value = String.format("#%06X", 0xFFFFFF and color)
                if (hex.text?.toString() != value) {
                    syncing = true
                    hex.setText(value)
                    hex.setSelection(value.length)
                    syncing = false
                }
            }
        }

        repeat(3) { rowIndex ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(6) { column ->
                val color = palette[rowIndex * 6 + column]
                val swatch = TextView(context).apply {
                    background = rounded(color, dp(10).toFloat())
                    setOnClickListener { updateUi(color) }
                }
                row.addView(swatch, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    marginStart = if (column == 0) 0 else dp(5)
                    bottomMargin = dp(5)
                })
            }
            swatchGrid.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(swatchGrid)

        fun sliderRow(title: String, bar: SeekBar): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 13f }, LinearLayout.LayoutParams(-1, -2))
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }

        root.addView(sliderRow("Hue", hue), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(sliderRow("Saturation", saturation))
        root.addView(sliderRow("Brightness", brightness))
        root.addView(hex, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val h = hue.progress.toFloat()
                val s = saturation.progress / 100f
                val v = brightness.progress / 100f
                updateUi(Color.HSVToColor(floatArrayOf(h, s, v)), updateSliders = false)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        hue.setOnSeekBarChangeListener(listener)
        saturation.setOnSeekBarChangeListener(listener)
        brightness.setOnSeekBarChangeListener(listener)

        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (syncing) return
                val raw = s?.toString()?.trim().orEmpty()
                if (raw.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                    try { updateUi(Color.parseColor(raw), updateHex = false) } catch (_: Exception) { }
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        updateUi(initialColor)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Custom ring colour")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use colour", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = hex.text?.toString()?.trim().orEmpty()
                if (!raw.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                    hex.error = "Choose a swatch or enter a valid colour such as #00C853"
                    return@setOnClickListener
                }
                onSelected(current)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
