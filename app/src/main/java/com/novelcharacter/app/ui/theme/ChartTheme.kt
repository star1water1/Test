package com.novelcharacter.app.ui.theme

import android.content.Context
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.novelcharacter.app.R

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
