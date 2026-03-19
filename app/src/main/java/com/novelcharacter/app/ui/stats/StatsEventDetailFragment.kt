package com.novelcharacter.app.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsEventDetailBinding

class StatsEventDetailFragment : Fragment() {

    private var _binding: FragmentStatsEventDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsEventDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadEventStats()
    }

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Toast.makeText(context ?: return@observe, R.string.stats_load_error, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.eventStats.observe(viewLifecycleOwner) { stats ->
            setupNarrativeDensityChart(stats.narrativeDensityCurve)
            setupNovelEventBarChart(stats.novelEventCounts)
            setupTimePrecisionChart(stats.timePrecision)
            setupCalendarTypeChart(stats.calendarTypeDistribution)
            binding.textAvgCharsPerEvent.text = String.format("%.1f", stats.avgCharsPerEvent)
            binding.textOrphanCount.text = stats.orphanEventCount.toString()
            binding.textAvgDescLength.text = getString(R.string.stats_chars_unit, stats.eventDescriptionLengthAvg.toInt())
            setupMonthDistChart(stats.monthDistribution)
        }
    }

    private fun setupNarrativeDensityChart(data: List<Pair<Int, Int>>) {
        val chart = binding.chartNarrativeDensity
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSmSize = resources.getDimension(R.dimen.stats_text_chart_value_sm) / resources.displayMetrics.scaledDensity
        val labels = data.map { it.first.toString() }
        val entries = data.mapIndexed { i, (_, count) -> BarEntry(i.toFloat(), count.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            color = ContextCompat.getColor(ctx, R.color.primary)
            valueTextSize = chartValueSmSize
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
            setDrawValues(false) // 빽빽한 연도 데이터에서는 값 숨김
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            xAxis.labelRotationAngle = -45f
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(600)
            invalidate()
        }
    }

    private fun setupTimePrecisionChart(data: TimePrecisionStats) {
        val chart = binding.chartTimePrecision
        val total = data.yearOnly + data.yearMonth + data.yearMonthDay
        if (total == 0) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val entries = listOf(
            PieEntry(data.yearOnly.toFloat(), getString(R.string.stats_precision_year)),
            PieEntry(data.yearMonth.toFloat(), getString(R.string.stats_precision_month)),
            PieEntry(data.yearMonthDay.toFloat(), getString(R.string.stats_precision_day))
        ).filter { it.value > 0 }
        val dataSet = PieDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = captionSize
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }
        chart.apply {
            this.data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 40f
            applyDarkModeHole(this)
            setUsePercentValues(true)
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            setEntryLabelColor(Color.WHITE)
            animateY(600)
            invalidate()
        }
    }

    private fun setupCalendarTypeChart(data: Map<String, Int>) {
        val chart = binding.chartCalendarType
        if (data.size <= 1) {
            chart.visibility = View.GONE
            binding.labelCalendarType.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val entries = data.entries.map { PieEntry(it.value.toFloat(), it.key) }
        val dataSet = PieDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = captionSize
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }
        chart.apply {
            this.data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 40f
            applyDarkModeHole(this)
            setUsePercentValues(true)
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            setEntryLabelColor(Color.WHITE)
            animateY(600)
            invalidate()
        }
    }

    private fun setupNovelEventBarChart(data: Map<String, Int>) {
        val chart = binding.chartNovelEventCounts
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val labels = data.keys.toList()
        val entries = data.values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = chartValueSize
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(600)
            invalidate()
        }
    }

    private fun setupMonthDistChart(data: Map<Int, Int>) {
        val chart = binding.chartMonthDist
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            binding.labelMonthDist.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSmSize = resources.getDimension(R.dimen.stats_text_chart_value_sm) / resources.displayMetrics.scaledDensity
        val monthLabels = listOf(
            "1월", "2월", "3월", "4월", "5월", "6월",
            "7월", "8월", "9월", "10월", "11월", "12월"
        )
        val entries = (1..12).map { month ->
            BarEntry((month - 1).toFloat(), (data[month] ?: 0).toFloat())
        }
        val dataSet = BarDataSet(entries, "").apply {
            color = ContextCompat.getColor(ctx, R.color.accent)
            valueTextSize = chartValueSmSize
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(monthLabels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(600)
            invalidate()
        }
    }

    private fun applyDarkModeHole(chart: PieChart) {
        chart.setHoleColor(ContextCompat.getColor(requireContext(), R.color.surface))
        chart.setTransparentCircleColor(ContextCompat.getColor(requireContext(), R.color.surface))
    }

    private fun chartColors(): List<Int> {
        val ctx = requireContext()
        return listOf(
            ContextCompat.getColor(ctx, R.color.primary),
            ContextCompat.getColor(ctx, R.color.accent),
            ContextCompat.getColor(ctx, R.color.search_type_novel),
            ContextCompat.getColor(ctx, R.color.primary_light),
            ContextCompat.getColor(ctx, R.color.primary_dark)
        ) + ColorTemplate.MATERIAL_COLORS.toList()
    }

    override fun onDestroyView() {
        _binding?.let {
            it.chartNarrativeDensity.clear()
            it.chartNovelEventCounts.clear()
            it.chartMonthDist.clear()
            it.chartTimePrecision.clear()
            it.chartCalendarType.clear()
        }
        super.onDestroyView()
        _binding = null
    }
}
