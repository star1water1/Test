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
import com.github.mikephil.charting.charts.BarChart
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
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsDataOverviewBinding

class StatsDataOverviewFragment : Fragment() {

    private var _binding: FragmentStatsDataOverviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsDataOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadDataOverview()
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

        viewModel.dataOverview.observe(viewLifecycleOwner) { stats ->
            populateTotals(stats)
            populateFieldCompletion(stats)
            populateYearDensity(stats)
            populateNameBank(stats)
            populateHealthWarnings(stats)
        }
    }

    private fun populateTotals(stats: DataOverviewStats) {
        binding.overviewCharCount.text = stats.totalCharacters.toString()
        binding.overviewNovelCount.text = stats.totalNovels.toString()
        binding.overviewUniverseCount.text = stats.totalUniverses.toString()
        binding.overviewEventCount.text = stats.totalEvents.toString()
        binding.overviewRelCount.text = stats.totalRelationships.toString()
        binding.overviewNameCount.text = stats.totalNames.toString()
    }

    private fun populateFieldCompletion(stats: DataOverviewStats) {
        val container = binding.fieldCompletionContainer
        container.removeAllViews()

        if (stats.fieldCompletionByGroup.isEmpty() && stats.fieldCompletionByField.isEmpty()) {
            container.addView(makeTextView(getString(R.string.stats_no_data)))
            return
        }

        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)

        // 그룹별 완성도
        stats.fieldCompletionByGroup.entries.sortedByDescending { it.value }.forEach { (group, rate) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm
                layoutParams = lp
            }
            row.addView(makeTextView("$group: ${String.format("%.0f", rate)}%"))
            val progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    progressHeight
                )
                max = 100
                this.progress = rate.toInt().coerceIn(0, 100)
            }
            row.addView(progress)
            container.addView(row)
        }

        // 필드별 상세 (처음 10개 + 더 보기)
        val fieldItems = stats.fieldCompletionByField.sortedBy { it.completionRate }
        val initialCount = 10
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity

        fun addFieldRow(field: FieldCompletionDetail): LinearLayout {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm / 2
                layoutParams = lp
            }
            row.addView(TextView(requireContext()).apply {
                text = "[${field.groupName}] ${field.fieldName}  ${String.format("%.0f", field.completionRate)}% (${field.filledCount}/${field.totalCount})"
                textSize = textSizeSp
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            })
            val progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    progressHeight
                )
                max = 100
                this.progress = field.completionRate.toInt().coerceIn(0, 100)
            }
            row.addView(progress)
            return row
        }

        fieldItems.take(initialCount).forEach { container.addView(addFieldRow(it)) }

        if (fieldItems.size > initialCount) {
            val remaining = fieldItems.size - initialCount
            val expandedViews = mutableListOf<View>()
            val toggleBtn = TextView(requireContext()).apply {
                text = getString(R.string.stats_show_more, remaining)
                textSize = textSizeSp
                setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
                setPadding(0, marginSm, 0, marginSm)
            }
            toggleBtn.setOnClickListener {
                if (expandedViews.isEmpty()) {
                    val insertIndex = container.indexOfChild(toggleBtn)
                    fieldItems.drop(initialCount).forEachIndexed { i, field ->
                        val row = addFieldRow(field)
                        container.addView(row, insertIndex + i)
                        expandedViews.add(row)
                    }
                    toggleBtn.text = getString(R.string.stats_show_less)
                } else {
                    expandedViews.forEach { container.removeView(it) }
                    expandedViews.clear()
                    toggleBtn.text = getString(R.string.stats_show_more, remaining)
                }
            }
            container.addView(toggleBtn)
        }
    }

    private fun populateYearDensity(stats: DataOverviewStats) {
        val chart = binding.chartYearDensity
        val data = stats.yearDensity
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE

        val sorted = data.entries.sortedBy { it.key }
        val barEntries = sorted.mapIndexed { i, e -> BarEntry(i.toFloat(), e.value.toFloat()) }
        val labels = sorted.map { it.key.toString() }

        val dataSet = BarDataSet(barEntries, "").apply {
            color = ContextCompat.getColor(requireContext(), R.color.primary)
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
            }
        }

        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            xAxis.setDrawGridLines(false)
            xAxis.labelRotationAngle = -45f
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(600)
            invalidate()
        }
    }

    private fun populateNameBank(stats: DataOverviewStats) {
        binding.nameBankUsage.text = getString(R.string.stats_overview_namebank_usage, stats.nameBankUsageRate)

        val chart = binding.chartNameGender
        val data = stats.nameBankGenderDist
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }

        val entries = data.entries.map { PieEntry(it.value.toFloat(), it.key) }
        val captionSp = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val dataSet = PieDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = captionSp
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }
        chart.apply {
            this.data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 40f
            setHoleColor(ContextCompat.getColor(requireContext(), R.color.surface))
            setTransparentCircleColor(ContextCompat.getColor(requireContext(), R.color.surface))
            setUsePercentValues(true)
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            setEntryLabelColor(Color.WHITE)
            animateY(600)
            invalidate()
        }
    }

    private fun populateHealthWarnings(stats: DataOverviewStats) {
        val container = binding.healthWarningsContainer
        container.removeAllViews()

        val warnings = stats.healthWarnings
        val hasIssues = warnings.noImageCount > 0 || warnings.incompleteFieldCount > 0 ||
            warnings.isolatedCharCount > 0 || warnings.unlinkedCharCount > 0

        if (!hasIssues) {
            container.addView(makeTextView(getString(R.string.stats_overview_all_healthy)))
            return
        }

        if (warnings.noImageCount > 0) {
            container.addView(makeTextView(getString(R.string.stats_overview_no_image_count, warnings.noImageCount)))
        }
        if (warnings.incompleteFieldCount > 0) {
            container.addView(makeTextView(getString(R.string.stats_overview_incomplete_field_count, warnings.incompleteFieldCount)))
        }
        if (warnings.isolatedCharCount > 0) {
            container.addView(makeTextView(getString(R.string.stats_overview_isolated_count, warnings.isolatedCharCount)))
        }
        if (warnings.unlinkedCharCount > 0) {
            container.addView(makeTextView(getString(R.string.stats_overview_unlinked_count, warnings.unlinkedCharCount)))
        }
    }

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
        _binding?.chartYearDensity?.clear()
        _binding?.chartNameGender?.clear()
        super.onDestroyView()
        _binding = null
    }
}
