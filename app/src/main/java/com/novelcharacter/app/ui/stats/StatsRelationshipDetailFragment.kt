package com.novelcharacter.app.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsRelationshipDetailBinding

class StatsRelationshipDetailFragment : Fragment() {

    private var _binding: FragmentStatsRelationshipDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsRelationshipDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadRelationshipStats()
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

        viewModel.relationshipStats.observe(viewLifecycleOwner) { stats ->
            // 네트워크 메트릭
            binding.textNetworkDensity.text = getString(R.string.stats_network_density_label, stats.networkDensity * 100)
            binding.textAvgConnections.text = getString(R.string.stats_avg_connections, stats.avgConnectionsPerChar)
            binding.textDescCompleteness.text = getString(R.string.stats_desc_completeness, stats.descriptionCompleteness, stats.emptyDescriptionCount)
            binding.textReciprocalPairs.text = getString(R.string.stats_reciprocal_pairs, stats.reciprocalPairCount)

            setupTypePieChart(stats.typeDistribution)
            populateRankedList(binding.listTopConnected, stats.topConnectedChars)
            populateSimpleList(binding.listIsolated, stats.isolatedCharacters)

            // 강도/방향성
            binding.textAvgIntensity.text = getString(R.string.stats_avg_intensity, stats.avgIntensity)
            binding.textDirectionRatio.text = getString(R.string.stats_direction_ratio, stats.bidirectionalCount, stats.unidirectionalCount)
            setupIntensityBarChart(stats.intensityDistribution)

            // 시간 추세
            if (stats.changeTimeline.isNotEmpty()) {
                binding.labelTimeTrend.visibility = View.VISIBLE
                binding.chartTimeTrend.visibility = View.VISIBLE
                setupTimeTrendChart(stats.changeTimeline)
            } else {
                binding.labelTimeTrend.visibility = View.GONE
                binding.chartTimeTrend.visibility = View.GONE
            }
        }
    }

    private fun setupTypePieChart(data: Map<String, Int>) {
        val chart = binding.chartTypeDist
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
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
            setEntryLabelTextSize(chartValueSize)
            animateY(600)
            invalidate()
        }
    }

    private fun populateRankedList(container: LinearLayout, items: List<Pair<String, Int>>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        items.forEachIndexed { index, (name, count) ->
            container.addView(makeTextView("${index + 1}. $name ($count)"))
        }
    }

    private fun populateSimpleList(container: LinearLayout, items: List<String>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        items.forEach { name ->
            container.addView(makeTextView(name))
        }
    }

    private fun makeEmptyTextView(): TextView = makeTextView(getString(R.string.stats_no_data))

    private fun makeTextView(text: String): TextView {
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        val marginXs = resources.getDimensionPixelSize(R.dimen.stats_margin_xs)
        return TextView(requireContext()).apply {
            this.text = text
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = marginXs
            layoutParams = lp
        }
    }

    private fun setupIntensityBarChart(data: Map<Int, Int>) {
        val chart = binding.chartIntensityDist
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE
        val ctx = requireContext()

        val entries = (1..10).map { intensity ->
            BarEntry(intensity.toFloat(), (data[intensity] ?: 0).toFloat())
        }
        val dataSet = BarDataSet(entries, getString(R.string.stats_intensity_label)).apply {
            color = ContextCompat.getColor(ctx, R.color.primary)
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
            valueTextSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = if (value > 0) value.toInt().toString() else ""
            }
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.axisMinimum = 0f
            axisRight.isEnabled = false
            animateY(400)
            invalidate()
        }
    }

    private fun setupTimeTrendChart(data: List<Pair<Int, Int>>) {
        val chart = binding.chartTimeTrend
        val ctx = requireContext()

        val entries = data.mapIndexed { _, (year, count) ->
            Entry(year.toFloat(), count.toFloat())
        }
        val dataSet = LineDataSet(entries, getString(R.string.stats_rel_time_trend)).apply {
            color = ContextCompat.getColor(ctx, R.color.primary)
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
            setCircleColor(ContextCompat.getColor(ctx, R.color.primary))
            circleRadius = 3f
            lineWidth = 2f
            valueTextSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = value.toInt().toString()
            }
        }
        chart.apply {
            this.data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = value.toInt().toString()
            }
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.axisMinimum = 0f
            axisRight.isEnabled = false
            animateX(600)
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
        _binding?.chartTypeDist?.clear()
        _binding?.chartIntensityDist?.clear()
        _binding?.chartTimeTrend?.clear()
        super.onDestroyView()
        _binding = null
    }
}
