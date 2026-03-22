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
import com.novelcharacter.app.databinding.FragmentStatsCharacterDetailBinding

class StatsCharacterDetailFragment : Fragment() {

    private var _binding: FragmentStatsCharacterDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsCharacterDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadCharacterStats()
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

        viewModel.characterStats.observe(viewLifecycleOwner) { stats ->
            // 복잡도 순위
            populateComplexityList(stats.complexityScores)

            // 특화 유형 분포 차트
            setupSpecializationPieChart(stats.complexityScores)
            // 잠재력 등급 분포 차트
            setupPotentialGradeBarChart(stats.complexityScores)

            setupTagPieChart(stats.tagDistribution)
            setupNovelCharBarChart(stats.novelCharacterCounts)
            setupSurvivalChart(stats.survivalPeriods)
            setupRelTypePieChart(stats.relationshipTypeDist)
            populateList(binding.listTopRelChars, stats.topRelationshipChars)
            populateList(binding.listTopEventChars, stats.topEventLinkedChars)

            // 그룹별 필드 완성도
            populateGroupCompletionList(stats.fieldCompletionByGroup)

            populateFieldCompletionList(stats.fieldCompletionRates)

            // 메모/별명 통계
            val memo = stats.memoStats
            binding.textMemoStats.text = getString(R.string.stats_memo_detail,
                memo.withMemo, memo.withMemo + memo.withoutMemo, memo.avgMemoLength.toInt())
            binding.textAnotherNameRate.text = getString(R.string.stats_another_name_rate,
                stats.anotherNameRate, stats.totalAliasCount)

            // 성씨 분포
            populateList(binding.listLastNameDist, stats.lastNameDistribution)
        }
    }

    private fun populateComplexityList(scores: List<CharacterComplexity>) {
        val container = binding.listComplexity
        container.removeAllViews()
        if (scores.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        scores.take(10).forEachIndexed { index, c ->
            val itemView = inflater.inflate(R.layout.item_complexity_rank, container, false)
            itemView.findViewById<TextView>(R.id.rankNumber).text = "${index + 1}"
            itemView.findViewById<TextView>(R.id.characterName).text = c.name
            itemView.findViewById<TextView>(R.id.scoreBadge).text =
                getString(R.string.stats_complexity_score, c.totalScore)
            itemView.findViewById<TextView>(R.id.statRelations).text =
                getString(R.string.stats_stat_relations, c.relationshipCount)
            itemView.findViewById<TextView>(R.id.statEvents).text =
                getString(R.string.stats_stat_events, c.eventLinkCount)
            itemView.findViewById<TextView>(R.id.statFields).text =
                if (c.fieldCompletionRate != null) getString(R.string.stats_stat_fields, c.fieldCompletionRate.toInt())
                else getString(R.string.stats_stat_fields_na)
            itemView.findViewById<TextView>(R.id.statChanges).text =
                getString(R.string.stats_stat_changes, c.stateChangeCount)

            // 특화 잠재력 레이블
            val specLabel = itemView.findViewById<TextView>(R.id.specializationLabel)
            if (c.specialization != CharacterComplexity.Specialization.NONE) {
                specLabel.text = "${c.specialization.icon} ${c.specialization.label}"
                specLabel.visibility = View.VISIBLE
            }

            // 등급별 배지 색상
            val scoreBadge = itemView.findViewById<TextView>(R.id.scoreBadge)
            val gradeColor = ContextCompat.getColor(requireContext(), gradeColorRes(c.overallPotential))
            val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(gradeColor)
                cornerRadius = 12 * resources.displayMetrics.density
            }
            scoreBadge.background = badgeBg

            container.addView(itemView)
        }
    }

    private fun setupSpecializationPieChart(scores: List<CharacterComplexity>) {
        val chart = binding.chartSpecializationDist
        val dist = scores
            .filter { it.specialization != CharacterComplexity.Specialization.NONE }
            .groupBy { "${it.specialization.icon} ${it.specialization.label}" }
            .mapValues { it.value.size }
        if (dist.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val entries = dist.entries.map { PieEntry(it.value.toFloat(), it.key) }
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

    private fun setupPotentialGradeBarChart(scores: List<CharacterComplexity>) {
        val chart = binding.chartPotentialGradeDist
        val gradeDist = scores.groupBy { it.overallPotential }
            .mapValues { it.value.size }
        if (gradeDist.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val grades = listOf(
            CharacterComplexity.PotentialGrade.S,
            CharacterComplexity.PotentialGrade.A,
            CharacterComplexity.PotentialGrade.B,
            CharacterComplexity.PotentialGrade.C,
            CharacterComplexity.PotentialGrade.D
        )
        val labels = grades.map { it.label }
        val entries = grades.mapIndexed { i, grade ->
            BarEntry(i.toFloat(), (gradeDist[grade] ?: 0).toFloat())
        }
        val gradeColors = grades.map { ContextCompat.getColor(ctx, gradeColorRes(it)) }
        val dataSet = BarDataSet(entries, "").apply {
            colors = gradeColors
            valueTextSize = chartValueSize
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
        }
        chart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.granularity = 1f
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(600)
            invalidate()
        }
    }

    private fun setupSurvivalChart(data: List<Pair<String, Int>>) {
        val chart = binding.chartSurvivalPeriods
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            binding.labelSurvival.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val sorted = data.sortedByDescending { it.second }
        val labels = sorted.map { it.first }
        val entries = sorted.mapIndexed { i, (_, years) -> BarEntry(i.toFloat(), years.toFloat()) }
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

    private fun setupTagPieChart(data: Map<String, Int>) {
        val chart = binding.chartTagDistribution
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val entries = data.entries.take(10).map { PieEntry(it.value.toFloat(), it.key) }
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

    private fun setupNovelCharBarChart(data: Map<String, Int>) {
        val chart = binding.chartNovelCharCounts
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

    private fun setupRelTypePieChart(data: Map<String, Int>) {
        val chart = binding.chartRelTypeDist
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

    private fun populateList(container: LinearLayout, items: List<Pair<String, Int>>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        items.forEachIndexed { index, (name, count) ->
            container.addView(makeTextView("${index + 1}. $name ($count)"))
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
        data.entries.sortedByDescending { it.value }.forEach { (group, rate) ->
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

    private fun populateFieldCompletionList(items: List<Pair<String, Float>>) {
        val container = binding.listFieldCompletion
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)
        items.sortedByDescending { it.second }.take(20).forEach { (name, rate) ->
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

    private fun gradeColorRes(grade: CharacterComplexity.PotentialGrade): Int = when (grade) {
        CharacterComplexity.PotentialGrade.S -> R.color.rank_s
        CharacterComplexity.PotentialGrade.A -> R.color.rank_a
        CharacterComplexity.PotentialGrade.B -> R.color.rank_b
        CharacterComplexity.PotentialGrade.C -> R.color.rank_c
        CharacterComplexity.PotentialGrade.D -> R.color.rank_d
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
            it.chartSpecializationDist.clear()
            it.chartPotentialGradeDist.clear()
            it.chartTagDistribution.clear()
            it.chartNovelCharCounts.clear()
            it.chartRelTypeDist.clear()
            it.chartSurvivalPeriods.clear()
        }
        super.onDestroyView()
        _binding = null
    }
}
