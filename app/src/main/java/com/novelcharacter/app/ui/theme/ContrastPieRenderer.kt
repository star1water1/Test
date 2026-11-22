package com.novelcharacter.app.ui.theme

import android.graphics.Canvas
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.renderer.PieChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler

/**
 * 조각 안의 **항목 이름도 그 조각의 값 글자색을 따르게** 하는 파이 렌더러.
 *
 * MPAndroidChart(v3.1.0)는 값 글자색은 조각마다 받지만(`setValueTextColors`) 항목 이름은
 * 색 하나뿐이다(`setEntryLabelColor`). 그래서 흰 이름표를 고정하면 밝은 조각에서, 어두운
 * 이름표를 고정하면 어두운 조각에서 안 읽힌다. 조각 안에 둘 다 그릴 때 렌더러는 **값을 먼저
 * 그리고 바로 이어 그 조각의 이름을 그린다**(`PieChartRenderer.drawValues`) — 그 값의 색을
 * 받아 두었다가 이름에 쓴다. 값이 없는 갈래(이름만 그리는 경우)는 기본 색을 그대로 쓴다.
 *
 * 설치와 조각별 색 지정은 [ChartTheme.contrastLabels]가 한다.
 */
class ContrastPieRenderer(
    chart: PieChart,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler
) : PieChartRenderer(chart, animator, viewPortHandler) {

    private var pendingLabelColor: Int? = null

    override fun drawValue(c: Canvas, valueText: String, x: Float, y: Float, color: Int) {
        pendingLabelColor = color
        super.drawValue(c, valueText, x, y, color)
    }

    override fun drawEntryLabel(c: Canvas, label: String, x: Float, y: Float) {
        val paint = paintEntryLabels
        val base = paint.color
        pendingLabelColor?.let { paint.color = it }
        super.drawEntryLabel(c, label, x, y)
        paint.color = base
        pendingLabelColor = null
    }
}
