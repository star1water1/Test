package com.novelcharacter.app.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.novelcharacter.app.util.navigateSafe
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.ColorTemplate
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsFieldAnalysisDetailBinding

class StatsFieldAnalysisDetailFragment : Fragment() {

    private var _binding: FragmentStatsFieldAnalysisDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsFieldAnalysisDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadFieldAnalysisStats()
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

        viewModel.fieldAnalysisStats.observe(viewLifecycleOwner) { stats ->
            populateFieldCompletionList(stats.fieldCompletionByField)
            populateValueDistributions(stats.fieldValueDistributions)
            populateNumberSummaries(stats.numberFieldSummaries)
            populateStateChanges(stats.stateChangesByField)
        }
    }

    private fun populateFieldCompletionList(items: List<FieldCompletionDetail>) {
        val container = binding.listFieldCompletion
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)
        items.forEach { field ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm
                layoutParams = lp
            }
            row.addView(makeTextView("[${field.groupName}] ${field.fieldName}  ${String.format("%.0f", field.completionRate)}% (${field.filledCount}/${field.totalCount})"))
            val progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    progressHeight
                )
                max = 100
                this.progress = field.completionRate.toInt().coerceIn(0, 100)
            }
            row.addView(progress)
            container.addView(row)
        }
    }

    private fun populateValueDistributions(dists: List<FieldValueDistribution>) {
        val container = binding.containerValueDists
        container.removeAllViews()
        if (dists.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val marginLg = resources.getDimensionPixelSize(R.dimen.stats_margin_lg)
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val chartHeight = resources.getDimensionPixelSize(R.dimen.stats_chart_height)

        dists.forEach { dist ->
            // 필드명 헤더
            val header = makeTextView("[${dist.groupName}] ${dist.fieldName} (${dist.fieldType})").apply {
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                val textSizeSp = resources.getDimension(R.dimen.stats_text_subtitle) / resources.displayMetrics.scaledDensity
                textSize = textSizeSp
                val lp = layoutParams as LinearLayout.LayoutParams
                lp.bottomMargin = marginSm
            }
            container.addView(header)

            // PieChart로 값 분포 표시
            val chart = PieChart(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    chartHeight
                ).apply { bottomMargin = marginLg }
            }
            val entries = dist.distribution.entries.take(10).map { PieEntry(it.value.toFloat(), it.key) }
            val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
            val dataSet = PieDataSet(entries, "").apply {
                colors = chartColors()
                valueTextSize = captionSize
                valueTextColor = Color.WHITE
                valueFormatter = PercentFormatter(chart)
            }
            chart.apply {
                data = PieData(dataSet)
                description.isEnabled = false
                isDrawHoleEnabled = true
                holeRadius = 40f
                setHoleColor(ContextCompat.getColor(requireContext(), R.color.surface))
                setTransparentCircleColor(ContextCompat.getColor(requireContext(), R.color.surface))
                setUsePercentValues(true)
                legend.isEnabled = true
                legend.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
                setEntryLabelColor(Color.WHITE)
                setTouchEnabled(true)
                setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        val pieEntry = e as? PieEntry ?: return
                        showCharacterListBottomSheet(dist.fieldDefId, dist.fieldName, pieEntry.label)
                    }
                    override fun onNothingSelected() {}
                })
                animateY(600)
                invalidate()
            }
            container.addView(chart)
        }
    }

    private fun populateNumberSummaries(summaries: List<NumberFieldSummary>) {
        val container = binding.containerNumberSummaries
        container.removeAllViews()
        if (summaries.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val marginLg = resources.getDimensionPixelSize(R.dimen.stats_margin_lg)
        val chartHeight = resources.getDimensionPixelSize(R.dimen.stats_chart_height_sm)
        summaries.forEach { s ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginLg
                layoutParams = lp
            }
            row.addView(makeTextView(s.fieldName).apply {
                val textSizeSp = resources.getDimension(R.dimen.stats_text_subtitle) / resources.displayMetrics.scaledDensity
                textSize = textSizeSp
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            })
            row.addView(makeTextView(getString(R.string.stats_number_summary,
                s.min, s.max, s.avg, s.median, s.count)))

            // 히스토그램 BarChart
            if (s.values.size >= 2) {
                val histogram = buildHistogram(s.values)
                val chart = BarChart(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        chartHeight
                    ).apply { topMargin = marginSm }
                }
                val barEntries = histogram.mapIndexed { i, bucket ->
                    BarEntry(i.toFloat(), bucket.second.toFloat())
                }
                val labels = histogram.map { it.first }
                val dataSet = BarDataSet(barEntries, "").apply {
                    color = ContextCompat.getColor(requireContext(), R.color.primary)
                    valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String =
                            if (value > 0) value.toInt().toString() else ""
                    }
                }
                chart.apply {
                    data = BarData(dataSet)
                    description.isEnabled = false
                    xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    xAxis.granularity = 1f
                    xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
                    xAxis.setDrawGridLines(false)
                    xAxis.labelRotationAngle = -30f
                    axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
                    axisLeft.granularity = 1f
                    axisRight.isEnabled = false
                    legend.isEnabled = false
                    setTouchEnabled(false)
                    animateY(400)
                    invalidate()
                }
                row.addView(chart)
            }

            container.addView(row)
        }
    }

    private fun buildHistogram(values: List<Float>): List<Pair<String, Int>> {
        val min = values.first()
        val max = values.last()
        if (min == max) return listOf(String.format("%.0f", min) to values.size)
        val bucketCount = minOf(8, values.size).coerceAtLeast(3)
        val range = max - min
        val bucketSize = range / bucketCount
        val buckets = Array(bucketCount) { 0 }
        values.forEach { v ->
            val idx = ((v - min) / bucketSize).toInt().coerceIn(0, bucketCount - 1)
            buckets[idx]++
        }
        return buckets.mapIndexed { i, count ->
            val lo = min + i * bucketSize
            val hi = lo + bucketSize
            val label = if (bucketSize >= 1f) "${lo.toInt()}~${hi.toInt()}" else String.format("%.1f~%.1f", lo, hi)
            label to count
        }
    }

    private fun populateStateChanges(data: Map<String, Int>) {
        val container = binding.listStateChanges
        container.removeAllViews()
        if (data.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        data.entries.sortedByDescending { it.value }.forEach { (field, count) ->
            container.addView(makeTextView("$field: ${count}건"))
        }
    }

    private fun makeEmptyTextView(): TextView = makeTextView(getString(R.string.stats_no_data))

    private fun makeTextView(text: String): TextView {
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        val marginXs = resources.getDimensionPixelSize(R.dimen.stats_margin_xs)
        return TextView(requireContext()).apply {
            this.text = text
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = marginXs
            layoutParams = lp
        }
    }

    // ===== 차트 탭 인터랙션 (개선 6) =====

    private fun showCharacterListBottomSheet(fieldDefId: Long, fieldName: String, value: String) {
        val sheet = StatsCharacterListBottomSheet.newInstance(fieldDefId, fieldName, value)
        sheet.onCharacterClick = { characterId ->
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigateSafe(R.id.statsFieldAnalysisDetailFragment, R.id.characterDetailFragment, bundle)
        }
        sheet.show(childFragmentManager, StatsCharacterListBottomSheet.TAG)
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
