package com.novelcharacter.app.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.novelcharacter.app.R

object ColorPickerHelper {

    fun showFullSpectrumColorPicker(ctx: Context, initialColor: String, onColorSelected: (String) -> Unit) {
        val density = ctx.resources.displayMetrics.density
        val dp8 = (8 * density).toInt()
        val dp16 = (16 * density).toInt()

        val hsv = FloatArray(3)
        try {
            Color.colorToHSV(Color.parseColor(initialColor), hsv)
        } catch (_: Exception) {
            hsv[0] = 0f; hsv[1] = 1f; hsv[2] = 1f
        }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp8)
        }

        // Color preview
        val previewView = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
                bottomMargin = dp16
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.HSVToColor(hsv))
                setStroke((1 * density).toInt(), Color.GRAY)
            }
        }
        layout.addView(previewView)

        // HEX display
        val hexEdit = EditText(ctx).apply {
            setText(String.format("#%06X", 0xFFFFFF and Color.HSVToColor(hsv)))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp8
            }
        }

        var isUpdating = false

        fun updateFromHSV() {
            isUpdating = true
            val color = Color.HSVToColor(hsv)
            (previewView.background as? GradientDrawable)?.setColor(color)
            hexEdit.setText(String.format("#%06X", 0xFFFFFF and color))
            isUpdating = false
        }

        // Hue SeekBar (0-360)
        val hueLabel = TextView(ctx).apply { text = "Hue (색상)"; textSize = 12f }
        val hueBar = SeekBar(ctx).apply {
            max = 360
            progress = hsv[0].toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { hsv[0] = progress.toFloat(); updateFromHSV() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        layout.addView(hueLabel)
        layout.addView(hueBar)

        // Saturation SeekBar (0-100)
        val satLabel = TextView(ctx).apply { text = "Saturation (채도)"; textSize = 12f }
        val satBar = SeekBar(ctx).apply {
            max = 100
            progress = (hsv[1] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { hsv[1] = progress / 100f; updateFromHSV() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        layout.addView(satLabel)
        layout.addView(satBar)

        // Value SeekBar (0-100)
        val valLabel = TextView(ctx).apply { text = "Brightness (명도)"; textSize = 12f }
        val valBar = SeekBar(ctx).apply {
            max = 100
            progress = (hsv[2] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { hsv[2] = progress / 100f; updateFromHSV() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        layout.addView(valLabel)
        layout.addView(valBar)

        // HEX manual input
        layout.addView(hexEdit)

        // HEX input → HSV sync
        hexEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdating) return
                val hex = s?.toString()?.trim() ?: ""
                try {
                    if (hex.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                        val color = Color.parseColor(hex)
                        Color.colorToHSV(color, hsv)
                        (previewView.background as? GradientDrawable)?.setColor(color)
                        hueBar.progress = hsv[0].toInt()
                        satBar.progress = (hsv[1] * 100).toInt()
                        valBar.progress = (hsv[2] * 100).toInt()
                    }
                } catch (_: Exception) {}
            }
        })

        val scrollView = ScrollView(ctx).apply {
            addView(layout)
            isFillViewport = true
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.color_picker_full_spectrum)
            .setView(scrollView)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val finalHex = hexEdit.text.toString().trim()
                if (finalHex.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                    onColorSelected(finalHex)
                } else {
                    onColorSelected(String.format("#%06X", 0xFFFFFF and Color.HSVToColor(hsv)))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
