package com.novelcharacter.app.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsEventDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsEventDetailFragment : Fragment() {

    private var _binding: FragmentStatsEventDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsEventDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        loadData()
    }

    private fun loadData() {
        binding.loadingProgress.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val app = requireActivity().application as NovelCharacterApp
                val provider = StatsDataProvider(app)
                val snapshot = withContext(Dispatchers.IO) { provider.loadSnapshot() }
                val stats = withContext(Dispatchers.IO) { provider.computeEventStats(snapshot) }

                if (!isAdded) return@launch

                binding.loadingProgress.visibility = View.GONE
                binding.contentLayout.visibility = View.VISIBLE

                setupYearDensityChart(stats.yearDensity)
                setupNovelEventBarChart(stats.novelEventCounts)
                binding.textAvgCharsPerEvent.text = String.format("%.1f", stats.avgCharsPerEvent)
                binding.textOrphanCount.text = stats.orphanEventCount.toString()
                setupMonthDistChart(stats.monthDistribution)
            } catch (e: Exception) {
                if (isAdded) {
                    binding.loadingProgress.visibility = View.GONE
                }
            }
        }
    }

    private fun setupYearDensityChart(data: Map<Int, Int>) {
        val chart = binding.chartYearDensity
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val sorted = data.entries.sortedBy { it.key }
        val labels = sorted.map { it.key.toString() }
        val entries = sorted.mapIndexed { i, e -> BarEntry(i.toFloat(), e.value.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            color = ContextCompat.getColor(requireContext(), R.color.primary)
            valueTextSize = 9f
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            xAxis.labelRotationAngle = -45f
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
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
        val labels = data.keys.toList()
        val entries = data.values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = 10f
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
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
        val monthLabels = listOf(
            "1월", "2월", "3월", "4월", "5월", "6월",
            "7월", "8월", "9월", "10월", "11월", "12월"
        )
        val entries = (1..12).map { month ->
            BarEntry((month - 1).toFloat(), (data[month] ?: 0).toFloat())
        }
        val dataSet = BarDataSet(entries, "").apply {
            color = ContextCompat.getColor(requireContext(), R.color.accent)
            valueTextSize = 9f
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(monthLabels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
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
        super.onDestroyView()
        _binding = null
    }
}
