package com.novelcharacter.app.ui.timeline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.novelcharacter.app.R

/**
 * 타임라인 슬라이더 아래에 표시되는 사건 밀도 인디케이터.
 * 연도 구간별 사건 수를 색상 강도로 시각화한다.
 */
class EventDensityBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val densityData = mutableMapOf<Int, Int>() // year -> count
    private var rangeFrom: Int = 0
    private var rangeTo: Int = 100
    private var maxCount: Int = 1

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.item_background_light)
        style = Paint.Style.FILL
    }

    private val baseColor = ContextCompat.getColor(context, R.color.primary)

    private var onYearTapListener: ((Int) -> Unit)? = null

    fun setOnYearTapListener(listener: (Int) -> Unit) {
        onYearTapListener = listener
    }

    fun setDensityData(data: Map<Int, Int>, from: Int, to: Int) {
        densityData.clear()
        densityData.putAll(data)
        rangeFrom = from
        rangeTo = to
        maxCount = data.values.maxOrNull() ?: 1
        invalidate()
    }

    fun setRange(from: Int, to: Int) {
        rangeFrom = from
        rangeTo = to
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (8 * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (rangeTo <= rangeFrom || densityData.isEmpty()) return

        val totalRange = (rangeTo - rangeFrom).toFloat()

        // 밀도 데이터를 픽셀 구간으로 그리기
        for ((year, count) in densityData) {
            if (year < rangeFrom || year > rangeTo) continue

            val ratio = (year - rangeFrom) / totalRange
            val x = ratio * w
            val barWidth = (w / totalRange).coerceAtLeast(2f)

            // 색상 강도 = count / maxCount (0.2 ~ 1.0)
            val alpha = (0.2f + 0.8f * (count.toFloat() / maxCount)).coerceIn(0.2f, 1.0f)
            barPaint.color = baseColor
            barPaint.alpha = (alpha * 255).toInt()

            canvas.drawRect(x, 0f, x + barWidth, h, barPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && rangeTo > rangeFrom) {
            val ratio = event.x / width.toFloat()
            val year = rangeFrom + (ratio * (rangeTo - rangeFrom)).toInt()

            // 해당 구간 근처의 사건 수 표시
            val nearCount = densityData.entries
                .filter { kotlin.math.abs(it.key - year) <= ((rangeTo - rangeFrom) / 50).coerceAtLeast(1) }
                .sumOf { it.value }

            if (nearCount > 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.timeline_density_tooltip, nearCount),
                    Toast.LENGTH_SHORT
                ).show()
            }

            onYearTapListener?.invoke(year)
            performClick()
            return true
        }
        return event.action == MotionEvent.ACTION_DOWN
    }
}
