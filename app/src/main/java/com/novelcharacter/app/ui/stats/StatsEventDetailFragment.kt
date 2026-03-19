package com.novelcharacter.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
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
            setupYearDensityChart(stats.yearDensity)
            setupNovelEventBarChart(stats.novelEventCounts)
            binding.textAvgCharsPerEvent.text = String.format("%.1f", stats.avgCharsPerEvent)
            binding.textOrphanCount.text = stats.orphanEventCount.toString()
            setupMonthDistChart(stats.monthDistribution)
        }
    }

    private fun setupYearDensityChart(data: Map<Int, Int>) {
        val chart = binding.chartYearDensity
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSmSize = resources.getDimension(R.dimen.stats_text_chart_value_sm) / resources.displayMetrics.scaledDensity
        val sorted = data.entries.sortedBy { it.key }
        val labels = sorted.map { it.key.toString() }
        val entries = sorted.mapIndexed { i, e -> BarEntry(i.toFloat(), e.value.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            color = ContextCompat.getColor(ctx, R.color.primary)
            valueTextSize = chartValueSmSize
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
            xAxis.labelRotationAngle = -45f
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisRight.isEnabled = false
            setFitBars(true)
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
            it.chartYearDensity.clear()
            it.chartNovelEventCounts.clear()
            it.chartMonthDist.clear()
        }
        super.onDestroyView()
        _binding = null
    }
}
