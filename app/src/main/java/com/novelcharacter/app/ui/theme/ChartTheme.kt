package com.novelcharacter.app.ui.theme

import android.content.Context
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieDataSet
import com.novelcharacter.app.R
import com.novelcharacter.app.util.ContrastText

/**
 * MPAndroidChart 공용 테마 — 차트 팔레트·축·범례 색을 한곳에서 관리한다.
 * 색은 리소스(chart_1..8 등)에서 읽으므로 라이트/다크가 자동 반영된다.
 * 기존 파편화된 per-fragment chartColors()·ColorTemplate.MATERIAL_COLORS를 대체.
 */
object ChartTheme {

    /** 범주형 차트 팔레트 — 8색 순환(항목이 더 많으면 MPAndroidChart가 순환 적용) */
    fun palette(ctx: Context): List<Int> = listOf(
        ContextCompat.getColor(ctx, R.color.chart_1),
        ContextCompat.getColor(ctx, R.color.chart_2),
        ContextCompat.getColor(ctx, R.color.chart_3),
        ContextCompat.getColor(ctx, R.color.chart_4),
        ContextCompat.getColor(ctx, R.color.chart_5),
        ContextCompat.getColor(ctx, R.color.chart_6),
        ContextCompat.getColor(ctx, R.color.chart_7),
        ContextCompat.getColor(ctx, R.color.chart_8)
    )

    fun apply(chart: PieChart) {
        val ctx = chart.context
        chart.description.isEnabled = false
        chart.setHoleColor(ContextCompat.getColor(ctx, R.color.surface))
        chart.setTransparentCircleColor(ContextCompat.getColor(ctx, R.color.surface))
        chart.legend.textColor = ContextCompat.getColor(ctx, R.color.text_secondary)
        chart.setEntryLabelColor(ContextCompat.getColor(ctx, R.color.on_surface))
    }

    /**
     * 파이 조각 안의 값·항목 이름을 **조각 색에 맞춰 읽히는 색**으로 칠한다.
     *
     * 흰 글자를 고정하면 다크 팔레트의 모든 조각(1.5~3.5:1)과 라이트의 밝은 조각에서 안 읽혔다.
     * 값은 조각마다 [ContrastText.on]으로 고르고, 이름은 [ContrastPieRenderer]가 같은 색을 따른다.
     *
     * **데이터셋의 색을 정한 뒤, 차트의 다른 설정(구멍 색·이름표 크기 등)보다 먼저 부른다** —
     * 렌더러를 바꾸면 렌더러가 든 페인트가 새것이 되므로, 앞서 걸어 둔 페인트 설정이 사라진다.
     */
    fun contrastLabels(chart: PieChart, dataSet: PieDataSet) {
        if (chart.renderer !is ContrastPieRenderer) {
            chart.renderer = ContrastPieRenderer(chart, chart.animator, chart.viewPortHandler)
        }
        dataSet.setValueTextColors(dataSet.colors.map { ContrastText.on(it) })
    }

    fun apply(chart: BarChart) = applyBarLike(chart)

    fun apply(chart: HorizontalBarChart) = applyBarLike(chart)

    fun apply(chart: LineChart) {
        val ctx = chart.context
        chart.description.isEnabled = false
        chart.legend.textColor = ContextCompat.getColor(ctx, R.color.text_secondary)
        chart.xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
        chart.axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
        chart.axisRight.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
        chart.xAxis.gridColor = ContextCompat.getColor(ctx, R.color.outline_variant)
        chart.axisLeft.gridColor = ContextCompat.getColor(ctx, R.color.outline_variant)
    }

    private fun applyBarLike(chart: BarChart) {
        val ctx = chart.context
        chart.description.isEnabled = false
        chart.legend.textColor = ContextCompat.getColor(ctx, R.color.text_secondary)
        chart.xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
        chart.axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
        chart.axisRight.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
        chart.xAxis.gridColor = ContextCompat.getColor(ctx, R.color.outline_variant)
        chart.axisLeft.gridColor = ContextCompat.getColor(ctx, R.color.outline_variant)
    }
}
