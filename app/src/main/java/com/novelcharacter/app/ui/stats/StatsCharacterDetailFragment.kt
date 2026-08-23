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
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsCharacterDetailBinding
import com.novelcharacter.app.util.ValueDistributions

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

        // 사유가 있으면 사유를, 없으면 통짜 문구를 — 어느 쪽이든 반드시 띄운다(B-32).
        viewModel.error.observe(viewLifecycleOwner) { error -> showStatsError(viewModel, error) }

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

    private fun setupTagPieChart(data: Map<String, Int>) = setupPieChart(binding.chartTagDistribution, data)

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

    private fun setupRelTypePieChart(data: Map<String, Int>) = setupPieChart(binding.chartRelTypeDist, data)

    /**
     * 이 화면의 파이 둘(태그 분포 · 관계 유형 분포) — **상한을 적용하되 분모는 모집단으로 남긴다**
     * (R-14 · S-17이 `ValueDistributions`로 세운 그 규칙).
     *
     * **종전에 둘이 서로 반대로 틀려 있었다.** 태그 파이는 `data.entries.take(10)`으로 자른 뒤
     * `setUsePercentValues(true)`를 켰는데, MPAndroidChart의 백분율 분모는 **넘긴 엔트리의 합**이라
     * 남은 열 조각끼리 100%가 됐다 — 태그 30종·총 100건에서 10건짜리 태그가 **10%가 아니라 25%**로
     * 그려졌고, 나머지 20종은 조각도 범례도 고지도 없이 사라져 *태그가 열 종뿐*으로 읽혔다.
     * 관계 유형 파이는 반대로 상한이 **아예 없어** 유형이 많으면 범례가 무너졌다.
     *
     * 그래서 한 함수로 모은다(둘의 나머지 코드는 글자 그대로 같았다). `ValueDistributions.view`가
     * 접고, 접힌 몫은 **'기타 N종' 조각으로 파이에 남는다** — 조각으로 남기는 것이 요점이다:
     * 분모가 모집단으로 돌아와 나머지 조각의 비율이 참이 되고, 잘린 종수·건수도 함께 보고된다
     * (상한은 감추는 장치가 아니라 접는 장치다 — R-14).
     */
    private fun setupPieChart(chart: PieChart, data: Map<String, Int>) {
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE
        val ctx = requireContext()
        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val view = ValueDistributions.view(data, ValueDistributions.DEFAULT_DISPLAY_LIMIT)
        val entries = view.shown.map { PieEntry(it.count.toFloat(), it.label) } +
            if (view.hasHidden) {
                listOf(PieEntry(
                    view.hiddenCount.toFloat(),
                    getString(R.string.stats_distribution_others, view.hiddenKinds)
                ))
            } else emptyList()
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

    /**
     * 캐릭터별 필드 완성도 — **잘린 수를 말하고, 끝까지 갈 수 있다**(R-14 · R-19).
     *
     * 종전에는 `take(20)`으로 잘라 놓고 잘린 사실을 어디에도 적지 않았다 — 캐릭터가 21명
     * 넘는 순간부터 이 카드는 *"이게 전부"*라고 거짓말했고, 게다가 **완성도 내림차순**이라
     * 잘려 나가는 쪽이 정확히 *가장 덜 채운 캐릭터들*이었다(이 카드가 도우려는 그 사람들).
     * 같은 축의 다른 명단들은 이미 접기 장치를 쓴다 — 이 자리만 그 훑기에서 빠졌다.
     *
     * 정렬은 **접기 전에** 한 번 한다(형제 화면과 같은 규율) — 접힌 뒤에 정렬하면 남는 것이
     * '가장 낮은 쪽'이 아니게 되고, 그러면 목록이 말하는 것 자체가 달라진다.
     */
    private fun populateFieldCompletionList(items: List<Pair<String, Float>>) {
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)
        StatsCappedList.populate(
            container = binding.listFieldCompletion,
            // 덜 채운 쪽이 먼저다 — 이 카드가 무엇을 도우려는지가 그 차례에 있다.
            items = items.sortedBy { it.second },
            emptyView = { makeEmptyTextView() },
            toggleView = { makeToggleTextView(it) },
            moreText = { getString(R.string.stats_show_more, it) },
            lessText = { getString(R.string.stats_show_less) },
            makeRow = { (name, rate) ->
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = marginSm
                    layoutParams = lp
                    addView(makeTextView("$name  ${String.format("%.0f", rate)}%"))
                    addView(
                        ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                progressHeight
                            )
                            max = 100
                            this.progress = rate.toInt().coerceIn(0, 100)
                        }
                    )
                }
            }
        )
    }

    private fun makeToggleTextView(label: CharSequence): TextView {
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        return TextView(requireContext()).apply {
            text = label
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
            setPadding(0, marginSm, 0, marginSm)
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

    private fun chartColors(): List<Int> =
        ChartTheme.palette(requireContext())

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
