package com.novelcharacter.app.ui.stats

import com.novelcharacter.app.ui.theme.ChartTheme
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsDataOverviewBinding
import com.novelcharacter.app.util.CompletionWeights

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
        binding.completionWeightButton.setOnClickListener { showCompletionWeightDialog() }
        setupObservers()
        viewModel.loadDataOverview()
    }

    /**
     * 완성도 필수 가중 설정 (B-100 — 사용자 확정 ㄱ2).
     *
     * 저장은 [CompletionWeightPrefs] 하나를 탄다. 바꾼 뒤 스냅샷을 다시 읽는 것은,
     * 가중이 **스냅샷에 실려** 계산되기 때문이다 — 캐시된 스냅샷을 그대로 두면 설정을
     * 바꿔도 숫자가 안 움직이고, 그것이 R-24가 금지한 '고를 수 있는데 아무 일도 없는' 자리다.
     */
    private fun showCompletionWeightDialog() {
        val ctx = context ?: return
        val current = CompletionWeightPrefs.weights(ctx)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.stats_padding_card)
            setPadding(pad, pad, pad, 0)
        }
        // R-25 목적문 — 무엇이 어디서 어떻게 바뀌는가.
        content.addView(TextView(ctx).apply {
            text = getString(R.string.stats_completion_weight_purpose)
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        })
        val valueLabel = TextView(ctx).apply {
            text = getString(R.string.stats_completion_weight_value, current.requiredWeight)
            textSize = 15f
            setPadding(0, resources.getDimensionPixelSize(R.dimen.stats_margin_sm), 0, 0)
        }
        content.addView(valueLabel)

        val slider = com.google.android.material.slider.Slider(ctx).apply {
            valueFrom = CompletionWeights.MIN_REQUIRED_WEIGHT
            valueTo = CompletionWeights.MAX_REQUIRED_WEIGHT
            stepSize = 0.5f
            value = current.requiredWeight
            addOnChangeListener { _, v, _ ->
                valueLabel.text = getString(R.string.stats_completion_weight_value, v)
            }
        }
        content.addView(slider)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.stats_completion_weight_title))
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                CompletionWeightPrefs.save(ctx, CompletionWeights.clamp(slider.value))
                viewModel.onCompletionWeightChanged()
                viewModel.loadDataOverview()
            }
            .setNeutralButton(R.string.stats_completion_weight_reset) { _, _ ->
                CompletionWeightPrefs.save(ctx, CompletionWeights.DEFAULT)
                viewModel.onCompletionWeightChanged()
                viewModel.loadDataOverview()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        // 사유가 있으면 사유를, 없으면 통짜 문구를 — 어느 쪽이든 반드시 띄운다(B-32).
        viewModel.error.observe(viewLifecycleOwner) { error -> showStatsError(viewModel, error) }

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

        // 가중이 걸리는지를 먼저 말한다 — 필수 칸이 없으면 설정을 만져도 숫자가 안 움직이고,
        // 그 이유를 화면이 말하지 않으면 사용자는 설정이 고장 난 줄 안다(원칙 04).
        binding.completionWeightNote.text = when {
            stats.requiredSlotCount == 0 -> getString(R.string.stats_completion_weight_note_none)
            else -> getString(R.string.stats_completion_weight_note, stats.requiredWeight)
        }

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

    private fun chartColors(): List<Int> =
        ChartTheme.palette(requireContext())

    override fun onDestroyView() {
        _binding?.chartYearDensity?.clear()
        _binding?.chartNameGender?.clear()
        super.onDestroyView()
        _binding = null
    }
}
