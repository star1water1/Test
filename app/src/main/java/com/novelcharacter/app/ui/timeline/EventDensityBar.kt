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

    /**
     * 이 바의 **연도 축** — 세우는 자리는 [setRange] **하나**다.
     *
     * 종전에는 축을 세우는 자리가 둘이었다: [setDensityData]가 데이터와 함께 *전체 연도*를,
     * `visibleRange` 관측자가 [setRange]로 *목록 창(중심±폭)*을 각각 세웠고 나중에 불린 쪽이
     * 이겼다. 둘은 뜻이 다른 값인데 같은 필드를 써서, 슬라이더를 한 번 움직이는 순간 바의
     * 축만 창으로 좁혀지고 **같은 x가 바 위와 슬라이더 위에서 서로 다른 해**를 가리켰다.
     * 줌을 올려 창 폭이 0이 되면 바가 배경만 남고 탭·롱프레스까지 죽었다.
     *
     * 축은 슬라이더를 세우는 그 계산에서만 온다 — 둘이 한 벌의 눈금으로 읽히도록
     * 레이아웃이 같은 좌우 여백으로 얹어 둔 것이 그 근거다.
     */
    private var rangeFrom: Int = 0
    private var rangeTo: Int = 100
    private var maxCount: Int = 1

    /** '지금 목록에 실린 연도 창' — **축이 아니라 강조 구간**이다. null이면 안 그린다. */
    private var windowFrom: Int? = null
    private var windowTo: Int? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.item_background_light)
        style = Paint.Style.FILL
    }

    private val baseColor = ContextCompat.getColor(context, R.color.primary)

    /** 창 강조 — 축을 바꾸지 않고 '지금 보는 구간'만 옅게 덮는다. */
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = baseColor
        alpha = 40
        style = Paint.Style.FILL
    }

    private var onYearTapListener: ((Int) -> Unit)? = null
    private var onYearLongPressListener: ((Int) -> Unit)? = null

    fun setOnYearTapListener(listener: (Int) -> Unit) {
        onYearTapListener = listener
    }

    fun setOnYearLongPressListener(listener: (Int) -> Unit) {
        onYearLongPressListener = listener
    }

    /** 데이터만 받는다 — **축은 [setRange]가 든다**(두 벌이 남는 한 한쪽이 다시 낡는다). */
    fun setDensityData(data: Map<Int, Int>) {
        densityData.clear()
        densityData.putAll(data)
        maxCount = (data.values.maxOrNull() ?: 1).coerceAtLeast(1)
        invalidate()
    }

    /** 연도 축 — 슬라이더와 **같은 수**를 받는다. */
    fun setRange(from: Int, to: Int) {
        rangeFrom = from
        rangeTo = to
        invalidate()
    }

    /**
     * 지금 목록에 실린 연도 창을 **강조**한다(축은 그대로).
     *
     * 창을 축으로 쓰면 창 밖 연도의 사건 표시가 사라져 *"연도를 열어 보지 않아도 사건의
     * 존재가 보인다"*(원칙 04)가 깨진다. 덮어 그리면 둘 다 산다.
     */
    fun setWindow(from: Int?, to: Int?) {
        windowFrom = from
        windowTo = to
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

        // 창 강조는 밀도 위에 옅게 덮는다 — 축을 바꾸지 않으므로 창 밖 사건도 그대로 보인다.
        val wf = windowFrom
        val wt = windowTo
        if (wf != null && wt != null && wt > wf) {
            val left = ((wf - rangeFrom) / totalRange * w).coerceIn(0f, w)
            val right = ((wt - rangeFrom) / totalRange * w).coerceIn(0f, w)
            if (right > left) canvas.drawRect(left, 0f, right, h, windowPaint)
        }
    }

    private var longPressRunnable: Runnable? = null
    private var downX = 0f
    private var isLongPressHandled = false

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        longPressRunnable?.let { handler?.removeCallbacks(it) }
        longPressRunnable = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rangeTo <= rangeFrom) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                isLongPressHandled = false
                longPressRunnable = Runnable {
                    isLongPressHandled = true
                    val ratio = downX / width.toFloat()
                    val year = rangeFrom + (ratio * (rangeTo - rangeFrom)).toInt()
                    onYearLongPressListener?.invoke(year)
                }
                handler?.postDelayed(longPressRunnable!!, 500L)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler?.removeCallbacks(longPressRunnable ?: return false)
                if (!isLongPressHandled) {
                    val ratio = event.x / width.toFloat()
                    val year = rangeFrom + (ratio * (rangeTo - rangeFrom)).toInt()

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
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler?.removeCallbacks(longPressRunnable ?: return false)
                return true
            }
        }
        return false
    }
}
