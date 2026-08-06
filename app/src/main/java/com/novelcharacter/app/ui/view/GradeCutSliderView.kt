package com.novelcharacter.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.DuelGradeRef
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 대결 등급 컷 편집기 — **100%를 총량으로 한 구간 슬라이더** (B-113, Q2 사용자 제3안).
 *
 * 화면 정본은 목업 `docs/mockups/duel_grade_ui_2026-08.html` ①이고, 설계는
 * `docs/feature_roadmap_2026-08.md` 4-4장이다.
 *
 * ## 왜 막대 하나인가
 *
 * 등급마다 숫자 칸을 두면 **합이 100이 되도록 지키는 일이 사용자 몫**이 된다(9단이면
 * 아홉 칸을 서로 맞춰야 한다). 막대 하나에 구분선을 놓으면 **합 100 제약이 손이 아니라
 * 구조로** 지켜진다 — 구분선을 끌면 이웃 구간만 줄고 늘어난다.
 *
 * ## 이중 경로 (원칙 04)
 *
 * - **드래그 = 러프.** 구분선을 잡아 끈다. 9단 × 360dp면 칸당 40dp가 안 되므로 이것만으로는
 *   1% 단위 조정이 불가능하다 — 그래서 정밀 경로가 따로 있다.
 * - **탭 선택 + 스테퍼·숫자 = 정밀.** 구분선을 탭하면 [selectedIndex]가 서고, 다이얼로그가
 *   그 경계에 ± 스테퍼와 숫자 칸을 붙인다(이 뷰 밖이다 — 뷰는 그리기와 잡기만 한다).
 *
 * ## 0% 구간은 정상이다
 *
 * 구분선 둘이 겹친 상태이고 *"이 축에서는 안 쓰는 등급"*을 뜻한다(검증이 단조 **비**감소인
 * 이유). 겹친 자리를 탭하면 어느 경계인지 알 수 없으므로 **[onAmbiguousTap]으로 물어본다** —
 * 임의로 하나를 고르면 사용자가 끌던 것과 다른 경계가 움직인다.
 */
class GradeCutSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val barHeight = 34f * density
    private val labelBand = 15f * density
    private val bubbleBand = 22f * density
    private val barRadius = 8f * density
    private val dividerWidth = 2f * density
    private val selectedDividerWidth = 4f * density

    /** 구분선을 잡았다고 볼 거리. 손가락 너비를 감안한 값이고, 좁은 구간에서도 잡혀야 한다. */
    private val touchSlop = 20f * density

    private val warmColor = context.getColor(R.color.accent)
    private val coolColor = context.getColor(R.color.primary)
    private val gapColor = context.getColor(R.color.surface)
    private val labelColor = context.getColor(R.color.text_secondary)
    private val outlineColor = context.getColor(R.color.outline)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = outlineColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * density
        color = labelColor
        textAlign = Paint.Align.CENTER
    }
    private val segmentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val barRect = RectF()

    /** 높은 등급부터의 라벨 차례. 마지막 등급은 컷이 없고 나머지 전부를 받는다. */
    private var labels: List<String> = emptyList()
    private var cuts: List<DuelGradeRef.Cut> = emptyList()

    /** 선택된 경계(=컷)의 인덱스. null이면 선택 없음. */
    var selectedIndex: Int? = null
        private set

    /** 값이 바뀔 때마다 — 드래그 중에도 부른다(러프 조정이 즉시 보여야 한다). */
    var onCutsChanged: ((List<DuelGradeRef.Cut>) -> Unit)? = null

    /** 선택이 바뀔 때 — 다이얼로그가 정밀 조정 줄을 그 경계로 맞춘다. */
    var onSelectionChanged: ((Int?) -> Unit)? = null

    /**
     * **겹친 구분선을 탭했다** — 어느 경계인지 물어야 한다(0% 구간이 있는 자리).
     * 인자는 후보 컷 인덱스들이며, 호출부가 목록으로 물어 [select]를 부른다.
     */
    var onAmbiguousTap: ((List<Int>) -> Unit)? = null

    private var draggingIndex: Int? = null

    fun setData(labels: List<String>, cuts: List<DuelGradeRef.Cut>) {
        this.labels = labels
        this.cuts = cuts
        if (selectedIndex?.let { it >= cuts.size } == true) select(null)
        invalidate()
    }

    fun cuts(): List<DuelGradeRef.Cut> = cuts

    fun select(index: Int?) {
        val next = index?.takeIf { it in cuts.indices }
        if (next == selectedIndex) return
        selectedIndex = next
        onSelectionChanged?.invoke(next)
        invalidate()
    }

    /**
     * 선택된 경계를 [delta]만큼 옮긴다 — 스테퍼(정밀 경로)가 부른다.
     * 이웃을 넘지 못하게 막는 것은 드래그와 **같은 규칙**이다([moveTo]).
     */
    fun nudgeSelected(delta: Double) {
        val index = selectedIndex ?: return
        moveTo(index, cuts[index].topPercent + delta)
    }

    /** 선택된 경계를 [percent]로 놓는다 — 숫자 칸(정밀 경로)이 부른다. */
    fun setSelectedPercent(percent: Double) {
        val index = selectedIndex ?: return
        moveTo(index, percent)
    }

    /**
     * 한 경계를 옮긴다 — **이웃 사이로 가둔다.**
     *
     * 가두지 않으면 컷이 서로를 넘어 단조 비감소가 깨진다. 넘어선 값을 오류로 띄우는 대신
     * 막는 쪽을 고른 것은, 여기서는 사용자가 *끌고 있는 중*이라 오류 문구가 곧 조작 마찰이기
     * 때문이다(숫자 칸으로 넘어선 값을 넣어도 같은 규칙으로 가둬진다).
     */
    private fun moveTo(index: Int, percent: Double) {
        if (index !in cuts.indices) return
        val lower = if (index == 0) 0.0 else cuts[index - 1].topPercent
        val upper = if (index == cuts.lastIndex) 100.0 else cuts[index + 1].topPercent
        val clamped = DuelGradeRef.roundPercent(percent.coerceIn(lower, upper))
        if (clamped == cuts[index].topPercent) return
        cuts = cuts.toMutableList().also { it[index] = it[index].copy(topPercent = clamped) }
        onCutsChanged?.invoke(cuts)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((320 * density).toInt(), widthMeasureSpec)
        val height = (bubbleBand + labelBand + barHeight + labelBand).toInt()
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.size < 2 || cuts.size != labels.size - 1) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = bubbleBand + labelBand
        barRect.set(left, top, right, top + barHeight)

        canvas.save()
        // 둥근 모서리 안에서만 그린다 — 구간을 사각으로 칠하고 막대를 둥글게 자르는 편이
        // 구간마다 모서리를 따지는 것보다 단순하고, 0% 구간에서도 어긋나지 않는다.
        canvas.clipRect(barRect)

        val span = right - left
        var previous = 0.0
        for (i in labels.indices) {
            val until = if (i < cuts.size) cuts[i].topPercent else 100.0
            val x0 = left + (previous / 100.0 * span).toFloat()
            val x1 = left + (until / 100.0 * span).toFloat()
            fillPaint.color = segmentColor(i, labels.size)
            canvas.drawRect(x0, barRect.top, x1, barRect.bottom, fillPaint)
            drawSegmentPercent(canvas, x0, x1, until - previous)
            previous = until
        }
        canvas.restore()

        // 구분선 — 겹친 둘도 그대로 겹쳐 그린다(0% 구간이 눈에 보이는 방식이다).
        cuts.forEachIndexed { index, cut ->
            val x = left + (cut.topPercent / 100.0 * span).toFloat()
            val selected = index == selectedIndex
            dividerPaint.color = if (selected) Color.WHITE else gapColor
            val half = (if (selected) selectedDividerWidth else dividerWidth) / 2f
            canvas.drawRect(x - half, barRect.top, x + half, barRect.bottom, dividerPaint)
        }

        canvas.drawRoundRect(barRect, barRadius, barRadius, outlinePaint)
        drawLabels(canvas, left, span)
        drawBubble(canvas, left, span)
    }

    /**
     * 구간이 넓을 때만 그 안에 비율을 적는다.
     *
     * 좁은 구간에 억지로 넣으면 이웃 글자와 겹쳐 **둘 다 못 읽는다.** 못 그린 구간의 값은
     * 선택 시 말풍선과 정밀 조정 줄이 말하므로 사라지는 정보가 아니다.
     */
    private fun drawSegmentPercent(canvas: Canvas, x0: Float, x1: Float, percent: Double) {
        val text = "${DuelGradeRef.roundPercent(percent).let { if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString() }}%"
        val needed = segmentTextPaint.measureText(text) + 6f * density
        if (x1 - x0 < needed) return
        segmentTextPaint.color = Color.WHITE
        val baseline = barRect.centerY() - (segmentTextPaint.descent() + segmentTextPaint.ascent()) / 2f
        canvas.drawText(text, (x0 + x1) / 2f, baseline, segmentTextPaint)
    }

    /**
     * 라벨을 막대 **위아래로 교차 배치**한다(설계 4-4 ⓑ).
     *
     * 9단이면 구간 하나가 40dp가 안 되어 한 줄에 늘어놓으면 이름이 서로 겹친다. 교차 배치는
     * 이웃끼리 줄이 달라 그 겹침을 절반으로 줄인다. 그래도 못 그릴 폭이면 숨기고, 선택 시
     * 말풍선으로 보인다.
     */
    private fun drawLabels(canvas: Canvas, left: Float, span: Float) {
        var previous = 0.0
        for (i in labels.indices) {
            val until = if (i < cuts.size) cuts[i].topPercent else 100.0
            val center = left + ((previous + until) / 2.0 / 100.0 * span).toFloat()
            val width = ((until - previous) / 100.0 * span).toFloat()
            previous = until
            val label = labels[i]
            if (width < labelPaint.measureText(label) * 0.9f) continue
            val baseline = if (i % 2 == 0) {
                barRect.top - 4f * density
            } else {
                barRect.bottom + labelBand - 4f * density
            }
            canvas.drawText(label, center, baseline, labelPaint)
        }
    }

    /** 선택된 경계의 값 — 좁아서 막대 안에 못 적은 구간의 값을 여기서 읽는다. */
    private fun drawBubble(canvas: Canvas, left: Float, span: Float) {
        val index = selectedIndex ?: return
        val cut = cuts.getOrNull(index) ?: return
        val text = context.getString(R.string.duel_grade_cut_bubble, formatPercent(cut.topPercent))
        val x = (left + (cut.topPercent / 100.0 * span).toFloat())
            .coerceIn(left + bubbleTextPaint.measureText(text) / 2f, left + span - bubbleTextPaint.measureText(text) / 2f)
        val halfWidth = bubbleTextPaint.measureText(text) / 2f + 6f * density
        val bubbleBottom = barRect.top - labelBand
        val rect = RectF(x - halfWidth, bubbleBottom - bubbleBand + 4f * density, x + halfWidth, bubbleBottom)
        bubblePaint.color = coolColor
        canvas.drawRoundRect(rect, 6f * density, 6f * density, bubblePaint)
        bubbleTextPaint.color = Color.WHITE
        val baseline = rect.centerY() - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f
        canvas.drawText(text, x, baseline, bubbleTextPaint)
    }

    /** 위(높은 등급)에서 아래로 따뜻한 색 → 차가운 색. 서열이 색으로도 읽히게 한다. */
    private fun segmentColor(index: Int, total: Int): Int {
        if (total <= 1) return warmColor
        val t = index.toFloat() / (total - 1).toFloat()
        fun mix(from: Int, to: Int) = (from + (to - from) * t).roundToInt()
        return Color.rgb(
            mix(Color.red(warmColor), Color.red(coolColor)),
            mix(Color.green(warmColor), Color.green(coolColor)),
            mix(Color.blue(warmColor), Color.blue(coolColor))
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.size < 2 || cuts.size != labels.size - 1) return false
        val left = paddingLeft.toFloat()
        val span = (width - paddingRight).toFloat() - left
        if (span <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val nearest = nearestCuts(event.x, left, span)
                when {
                    nearest.isEmpty() -> return false
                    // 겹친 구분선 — 임의로 고르면 사용자가 끌려던 것과 다른 경계가 움직인다.
                    nearest.size > 1 -> {
                        onAmbiguousTap?.invoke(nearest)
                        return true
                    }
                    else -> {
                        select(nearest.first())
                        draggingIndex = nearest.first()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = draggingIndex ?: return false
                moveTo(index, ((event.x - left) / span * 100.0).toDouble())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                draggingIndex = null
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 탭 지점에서 잡히는 구분선들 — **같은 자리에 겹친 것은 함께** 돌려준다.
     *
     * 가장 가까운 것 하나만 돌려주면 0% 구간의 두 경계 중 하나가 영원히 잡히지 않는다.
     */
    private fun nearestCuts(x: Float, left: Float, span: Float): List<Int> {
        val distances = cuts.mapIndexed { index, cut ->
            index to abs(left + (cut.topPercent / 100.0 * span).toFloat() - x)
        }.filter { it.second <= touchSlop }
        if (distances.isEmpty()) return emptyList()
        val min = distances.minOf { it.second }
        return distances.filter { it.second - min < 0.5f * density }.map { it.first }
    }

    private fun formatPercent(value: Double): String =
        if (value == value.toInt().toDouble()) "${value.toInt()}.0" else value.toString()
}
