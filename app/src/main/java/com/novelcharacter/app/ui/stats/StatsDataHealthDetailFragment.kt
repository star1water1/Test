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
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsDataHealthDetailBinding

class StatsDataHealthDetailFragment : Fragment() {

    private var _binding: FragmentStatsDataHealthDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsDataHealthDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadDataHealthStats()
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

        viewModel.dataHealthStats.observe(viewLifecycleOwner) { stats ->
            // 데이터 건강도 개요 도넛 차트
            setupHealthOverviewChart(stats)

            binding.textNoImageCount.text = getString(R.string.stats_person_suffix, stats.noImageChars.size)
            populateSimpleList(binding.listNoImage, stats.noImageChars)
            populateIncompleteList(binding.listIncompleteFields, stats.incompleteFieldChars)

            // 그룹별 완성도
            populateGroupCompletionList(stats.fieldCompletionByGroup)

            // 메모 미작성
            binding.textNoMemoCount.text = getString(R.string.stats_person_suffix, stats.noMemoChars.size)

            // 관계 설명 미작성
            binding.textEmptyRelDesc.text = getString(R.string.stats_count_format, stats.emptyDescRelationships)

            // 시간 정밀도 낮은 사건
            binding.textLowPrecision.text = getString(R.string.stats_count_format, stats.lowPrecisionEvents)

            populateSimpleList(binding.listIsolated, stats.isolatedChars)
            populateSimpleList(binding.listUnlinked, stats.unlinkedChars)
            populateSimpleList(binding.listDuplicateTags, stats.duplicateTags)
        }
    }

    private fun setupHealthOverviewChart(stats: DataHealthStats) {
        val chart = binding.chartDataHealthOverview
        val totalChars = stats.noImageChars.size + stats.isolatedChars.size +
            stats.unlinkedChars.size + stats.incompleteFieldChars.size
        if (totalChars == 0 && stats.noImageChars.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()

        // 이미지, 고립, 사건 미연계 비율을 도넛으로 표시
        val entries = mutableListOf<PieEntry>()
        val noImage = stats.noImageChars.size
        val isolated = stats.isolatedChars.size
        val unlinked = stats.unlinkedChars.size

        if (noImage > 0) entries.add(PieEntry(noImage.toFloat(), getString(R.string.stats_health_no_image)))
        if (isolated > 0) entries.add(PieEntry(isolated.toFloat(), getString(R.string.stats_health_isolated)))
        if (unlinked > 0) entries.add(PieEntry(unlinked.toFloat(), getString(R.string.stats_health_event_unlinked)))

        if (entries.isEmpty()) {
            chart.visibility = View.GONE
            return
        }

        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val dataSet = PieDataSet(entries, "").apply {
            colors = listOf(
                ContextCompat.getColor(ctx, R.color.accent),
                ContextCompat.getColor(ctx, R.color.primary_dark),
                ContextCompat.getColor(ctx, R.color.search_type_novel)
            )
            valueTextSize = captionSize
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }
        chart.apply {
            data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 50f
            setHoleColor(ContextCompat.getColor(ctx, R.color.surface))
            setTransparentCircleColor(ContextCompat.getColor(ctx, R.color.surface))
            setUsePercentValues(true)
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(chartValueSize)
            animateY(600)
            invalidate()
        }
    }

    private fun populateGroupCompletionList(data: Map<String, Float>) {
        val container = binding.listGroupCompletion
        container.removeAllViews()
        if (data.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)
        data.entries.sortedBy { it.value }.forEach { (group, rate) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm
                layoutParams = lp
            }
            row.addView(makeTextView("$group  ${String.format("%.0f", rate)}%"))
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

    private fun populateIncompleteList(container: LinearLayout, items: List<Pair<String, Float>>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)
        items.sortedBy { it.second }.forEach { (name, rate) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm
                layoutParams = lp
            }
            row.addView(makeTextView("$name  ${String.format("%.0f", rate)}%"))
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

    override fun onDestroyView() {
        _binding?.chartDataHealthOverview?.clear()
        super.onDestroyView()
        _binding = null
    }
}
