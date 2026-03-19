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
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
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
        summaries.forEach { s ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm
                layoutParams = lp
            }
            row.addView(makeTextView(s.fieldName).apply {
                val textSizeSp = resources.getDimension(R.dimen.stats_text_subtitle) / resources.displayMetrics.scaledDensity
                textSize = textSizeSp
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            })
            row.addView(makeTextView(getString(R.string.stats_number_summary,
                s.min, s.max, s.avg, s.median, s.count)))
            container.addView(row)
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
