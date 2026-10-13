package com.catch7ng.ostatus

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Lightweight, dependency-free color picker for the indicator overlay. */
object IndicatorColorPickerDialog {

    fun show(
        context: Context,
        initialColor: Int,
        onSelected: (Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        val preview = TextView(context).apply {
            text = "Preview"
            gravity = Gravity.CENTER
            setTextColor(contrastTextColor(initialColor))
            background = roundedColor(initialColor, dp(12).toFloat())
            minimumHeight = dp(54)
        }
        container.addView(preview, LinearLayout.LayoutParams(-1, dp(54)))

        val input = EditText(context).apply {
            hint = "#RRGGBB"
            setSingleLine(true)
            setText(String.format("#%06X", 0xFFFFFF and initialColor))
            filters = arrayOf(InputFilter.LengthFilter(7))
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2))

        fun parsedColor(): Int? {
            val raw = input.text?.toString()?.trim().orEmpty()
            if (!raw.matches(Regex("^#[0-9A-Fa-f]{6}$"))) return null
            return try { Color.parseColor(raw) } catch (_: IllegalArgumentException) { null }
        }

        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                parsedColor()?.let { color ->
                    preview.background = roundedColor(color, dp(12).toFloat())
                    preview.setTextColor(contrastTextColor(color))
                }
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Custom indicator colour")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use colour", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val color = parsedColor()
                if (color == null) {
                    input.error = "Use a 6-digit hex colour, for example #00C853"
                    return@setOnClickListener
                }
                onSelected(color)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun roundedColor(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun contrastTextColor(background: Int): Int {
        val luminance = (0.299 * Color.red(background) +
            0.587 * Color.green(background) +
            0.114 * Color.blue(background)) / 255.0
        return if (luminance > 0.58) Color.BLACK else Color.WHITE
    }
}
