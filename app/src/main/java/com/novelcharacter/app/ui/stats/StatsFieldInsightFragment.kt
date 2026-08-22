package com.novelcharacter.app.ui.stats

import com.novelcharacter.app.ui.theme.ChartTheme
import android.graphics.Color
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.novelcharacter.app.data.model.FieldDefinition
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
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
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.databinding.FragmentStatsFieldInsightBinding
import com.novelcharacter.app.util.CrossTableFold
import com.novelcharacter.app.util.DisplayCap
import com.novelcharacter.app.util.FieldValueMatchSpec
import com.novelcharacter.app.util.ValueDistributions
import com.novelcharacter.app.util.setValidatedPositiveButton

class StatsFieldInsightFragment : Fragment() {

    private var _binding: FragmentStatsFieldInsightBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsFieldInsightBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.btnCrossAnalysis.setOnClickListener { showCrossAnalysisDialog() }
        setupObservers()
        // 되살아난 드릴다운 시트에 콜백을 다시 붙인다(회전 뒤 목록은 멀쩡한데 탭만 죽는다).
        rebindDrilldownSheet()
        viewModel.loadFieldInsights()
    }

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        // 교차분석 실패 사유(축 불일치·필드 없음)는 구체적인 문구로 온다 — 그대로 보여준다(B-32).
        viewModel.error.observe(viewLifecycleOwner) { error -> showStatsError(viewModel, error) }

        viewModel.fieldInsights.observe(viewLifecycleOwner) { insights ->
            populateInsights(insights)
        }

        viewModel.crossAnalysis.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                showCrossAnalysisResult(result)
            }
        }
    }

    // ===== 필드 인사이트 렌더링 =====

    private fun populateInsights(insights: List<FieldInsightResult>) {
        if (!isAdded) return
        val container = binding.insightContainer
        container.removeAllViews()

        if (insights.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            return
        }
        binding.emptyText.visibility = View.GONE

        val marginMd = resources.getDimensionPixelSize(R.dimen.stats_margin_md)

        insights.forEach { insight ->
            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = marginMd }
                radius = resources.getDimension(R.dimen.stats_card_corner_radius)
                cardElevation = resources.getDimension(R.dimen.stats_card_elevation)
            }

            val cardContent = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val pad = resources.getDimensionPixelSize(R.dimen.stats_padding_card)
                setPadding(pad, pad, pad, pad)
            }

            // 필드 헤더
            cardContent.addView(makeFieldHeader(insight))

            // 완성도 표시
            cardContent.addView(makeCompletionText(insight))

            // 각 분석 결과 렌더링
            insight.analysisResults.forEach { result ->
                cardContent.addView(renderAnalysisResult(result, insight))
            }

            card.addView(cardContent)
            container.addView(card)
        }
    }

    private fun makeFieldHeader(insight: FieldInsightResult): LinearLayout {
        val ctx = requireContext()
        val textSizeSp = resources.getDimension(R.dimen.stats_text_subtitle) / resources.displayMetrics.scaledDensity
        val density = resources.displayMetrics.density

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
            layoutParams = lp

            // 필드 이름 텍스트
            val headerText = TextView(ctx).apply {
                val uniPrefix = if (insight.universeName.isNotEmpty()) "${insight.universeName} · " else ""
                // 사건 필드는 캐릭터 필드와 구분해 표기 (모수도 캐릭터 수가 아닌 사건 수)
                // 캐릭터가 아닌 축은 모수 단위가 다르므로(사건 수·작품 수) 이름 앞에 종류를 밝힌다.
                val entityPrefix = when (insight.fieldDefinition.entityType) {
                    com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT ->
                        getString(R.string.stats_event_field_prefix) + " · "
                    com.novelcharacter.app.data.model.FieldDefinition.ENTITY_NOVEL ->
                        getString(R.string.stats_novel_field_prefix) + " · "
                    else -> ""
                }
                val typeLabel = com.novelcharacter.app.data.model.FieldType.fromName(insight.fieldDefinition.type)?.label ?: insight.fieldDefinition.type
                text = "$entityPrefix$uniPrefix[${insight.fieldDefinition.groupName}] ${insight.fieldDefinition.name} ($typeLabel)"
                textSize = textSizeSp
                setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(headerText)

            // 분석 설정 기어 아이콘
            val settingsBtn = ImageButton(ctx).apply {
                val size = (32 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageResource(R.drawable.ic_settings)
                setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                setBackgroundResource(android.R.color.transparent)
                contentDescription = getString(R.string.stats_inline_config_title)
                setOnClickListener {
                    showAnalysisSettingsBottomSheet(insight)
                }
            }
            addView(settingsBtn)
        }
    }

    private fun makeCompletionText(insight: FieldInsightResult): TextView {
        val rate = if (insight.totalCount > 0) insight.filledCount * 100f / insight.totalCount else 0f
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        // 모수 단위는 축마다 다르다(캐릭터 명 · 사건 건 · 작품 개) — 단위를 구분해 표기
        val completionRes = when (insight.fieldDefinition.entityType) {
            com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT ->
                R.string.stats_field_insight_completion_events
            com.novelcharacter.app.data.model.FieldDefinition.ENTITY_NOVEL ->
                R.string.stats_field_insight_completion_novels
            else -> R.string.stats_field_insight_completion
        }
        return TextView(requireContext()).apply {
            text = getString(completionRes, insight.filledCount, insight.totalCount, rate)
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
            layoutParams = lp
        }
    }

    private fun renderAnalysisResult(result: AnalysisResult, insight: FieldInsightResult): View {
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_md)
            layoutParams = lp
        }

        // 분석 타입 라벨
        val typeLabel = TextView(requireContext()).apply {
            val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
            text = if (result.entry.label.isNotBlank()) {
                "${result.entry.type.label} — ${result.entry.label}"
            } else {
                result.entry.type.label
            }
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_xs)
            layoutParams = lp
        }
        wrapper.addView(typeLabel)

        when (result.entry.type) {
            FieldStatsConfig.StatsType.DISTRIBUTION -> {
                val data = result.distributionData ?: return wrapper
                // 상한 적용은 여기 한 곳 — 잘린 나머지는 '기타' 조각으로 접어 존재를 알린다(R-14).
                // **자동 구간으로 접힌 분포는 순서를 지킨다**(B-196) — 건수순으로 재정렬하면
                // 인접 구간이 흩어져 '어디에 몰렸는가'를 읽을 수 없다. 순서 자체가 정보다.
                val view = ValueDistributions.view(data, result.entry.limit, preserveOrder = result.autoBinned)
                // '기타' 합계 조각은 **전체가 100%여야 하는 그림**(파이·도넛)에서만 정당하다.
                // 막대에 끼우면 잘린 값들의 합이 최댓값을 차지해 값이 아닌 것이 1위로 보인다.
                val isProportional = result.entry.chart == FieldStatsConfig.ChartType.PIE ||
                    result.entry.chart == FieldStatsConfig.ChartType.DONUT
                val slices = toSlices(view, includeOthers = isProportional, specs = result.distributionSpecs)
                val chart = createChartForDistribution(slices, result.entry.chart)
                attachChartTapListener(chart, slices, insight)
                wrapper.addView(chart)
                // **접었으면 접었다고 말한다**(R-14 — 상한은 감추는 장치가 아니라 접는 장치다).
                // 구간을 직접 정하는 경로가 이미 있으므로 그쪽도 함께 가리킨다(자율성 우선).
                if (result.autoBinned) {
                    wrapper.addView(createAutoBinNote(result.preFoldKinds, result.entry.limit))
                }
                wrapper.addView(createDistributionTable(view, result.entry.limit))
            }
            FieldStatsConfig.StatsType.RANKING -> {
                val data = result.distributionData ?: return wrapper
                val view = ValueDistributions.view(data, result.entry.limit)
                val slices = toSlices(view, includeOthers = false)
                val chart = createRankingChart(slices)
                attachChartTapListener(chart, slices, insight)
                wrapper.addView(chart)
                if (view.hasHidden) wrapper.addView(createTruncationNote(view, result.entry.limit))
            }
            FieldStatsConfig.StatsType.NUMERIC -> {
                val summary = result.numericSummary ?: return wrapper
                wrapper.addView(createNumericSummaryView(summary))
                if (summary.histogram.isNotEmpty()) {
                    // 막대도 조각이다 — 분포·순위와 같은 드릴다운을 갖는다(B-39). 스펙은 구간을
                    // 만든 쪽이 실어 보낸 것을 그대로 쓴다: 라벨은 계산 결과라 값 일치가
                    // 성립하지 않고, 라벨로 조회하면 어떤 입력에서도 0명이 된다(S-16).
                    //
                    // **스펙이 없으면 라벨로 대신하지 않는다.** `Values(구간 라벨)`은 곧 그
                    // 0명짜리 조회이고, 그것을 폴백으로 두면 **고장이 '아무도 없음'처럼 보인다.**
                    // 하나라도 비면 아예 안 붙인다 — 막대만 걸러내면 조각 순서가 차트 인덱스와
                    // 어긋나 **엉뚱한 구간의 목록이 열린다**(그쪽이 더 나쁘다).
                    val chart = createHistogramChart(summary.histogram)
                    val specs = summary.histogram.keys.map { summary.matchSpecs[it] }
                    if (specs.none { it == null }) {
                        val slices = summary.histogram.entries.mapIndexed { i, e ->
                            ChartSlice(e.key, e.value, specs[i]!!)
                        }
                        attachChartTapListener(chart, slices, insight)
                    }
                    wrapper.addView(chart)
                }
            }
        }

        return wrapper
    }

    // ===== 차트 탭 인터랙션 (개선 6) =====

    /**
     * 차트 조각 탭 → 그 값을 가진 대상 목록.
     *
     * [insight]를 통째로 받는 이유가 둘이다:
     * - 카드가 합산한 **머지 id 전체**([FieldInsightResult.mergedFieldDefIds])를 그대로 넘겨야
     *   목록 인원이 조각 수치와 일치한다(S-7).
     * - 사건 필드 카드인지(entityType) 알아야 사건 목록으로 보낼 수 있다 — 종전에는 사건 카드도
     *   캐릭터 조회로 흘러가 항상 0명짜리 빈 시트가 떴다(S-9).
     */
    private fun attachChartTapListener(chart: View, slices: List<ChartSlice>, insight: FieldInsightResult) {
        val open = { slice: ChartSlice -> showDrilldownBottomSheet(insight, slice) }
        val byLabel = slices.associateBy { it.label }
        when (chart) {
            is PieChart -> {
                chart.setTouchEnabled(true)
                chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        val pieEntry = e as? PieEntry ?: return
                        open(byLabel[pieEntry.label] ?: return)
                    }
                    override fun onNothingSelected() {}
                })
            }
            is HorizontalBarChart -> {
                chart.setTouchEnabled(true)
                chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        val index = h?.x?.toInt() ?: return
                        open(slices.getOrNull(index) ?: return)
                    }
                    override fun onNothingSelected() {}
                })
            }
            is BarChart -> {
                chart.setTouchEnabled(true)
                chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        val index = h?.x?.toInt() ?: return
                        open(slices.getOrNull(index) ?: return)
                    }
                    override fun onNothingSelected() {}
                })
            }
        }
    }

    /**
     * 회전·프로세스 재생성 뒤 되살아난 드릴다운 시트에 **콜백을 다시 붙인다**.
     *
     * 시트는 arguments에서 매치 스펙을 복원해 목록을 정상적으로 다시 채우지만, 행을 누를 때
     * 부르는 람다는 인스턴스 필드라 새 인스턴스에서는 null이다 — 목록은 멀쩡한데 탭만 죽은
     * 상태가 되고, 사용자에게는 고장과 구분되지 않는다(시트를 닫았다 다시 열어야 살아난다).
     */
    private fun rebindDrilldownSheet() {
        val sheet = childFragmentManager
            .findFragmentByTag(StatsCharacterListBottomSheet.TAG) as? StatsCharacterListBottomSheet
            ?: return
        bindDrilldownCallbacks(sheet)
    }

    private fun bindDrilldownCallbacks(sheet: StatsCharacterListBottomSheet) {
        sheet.onCharacterClick = { characterId ->
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigate(R.id.characterDetailFragment, bundle)
        }
        sheet.onNovelClick = { universeId ->
            // 작품 상세 화면이 없으므로 그 작품이 있는 목록으로 보낸다 — 사건 축이 연표로
            // 보내는 것과 같은 규약이다. 세계관이 없는 작품은 전체 작품 목록으로 보낸다.
            val bundle = Bundle().apply { if (universeId > 0) putLong("universeId", universeId) }
            findNavController().navigate(R.id.novelListFragment, bundle)
        }
        sheet.onEventClick = { year ->
            // 사건 상세 화면은 없다 — 전역 검색과 **같은 규약**으로 연표를 그 연도에 맞춰 연다
            // (center_year는 TimelineFragment.onResume이 소비한다).
            val prefs = requireContext()
                .getSharedPreferences("timeline_ui_state", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("center_year", year).putBoolean("pending_navigate", true).commit()
            findNavController().navigate(R.id.timelineFragment)
        }
    }

    /**
     * 카드가 그린 필드의 **종류가 축을 정한다.**
     *
     * 불리언(`== 사건`)으로 두면 작품 카드가 캐릭터 쪽으로 흘러간다 — 드릴다운에서는
     * 0명짜리 빈 시트가, 교차분석에서는 *"작품 필터를 확인"*이라는 **거짓 사유**가 됐다.
     * 판정을 한 자리에 두어 두 소비처가 같은 답을 쓰게 한다(R-51).
     */
    private fun axisOf(fd: com.novelcharacter.app.data.model.FieldDefinition): StatsEntityAxis =
        when (fd.entityType) {
            com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT -> StatsEntityAxis.EVENT
            com.novelcharacter.app.data.model.FieldDefinition.ENTITY_NOVEL -> StatsEntityAxis.NOVEL
            else -> StatsEntityAxis.CHARACTER
        }

    private fun showDrilldownBottomSheet(insight: FieldInsightResult, slice: ChartSlice) {
        val axis = axisOf(insight.fieldDefinition)
        val sheet = StatsCharacterListBottomSheet.newInstance(
            fieldDefIds = insight.mergedFieldDefIds,
            fieldName = insight.fieldDefinition.name,
            selectedValue = slice.label,
            axis = axis,
            // 화면이 보여준 그 조각의 규칙을 그대로 넘긴다 — 접힌 '기타'도 목록을 볼 수 있다.
            matchSpec = slice.spec,
            sliceCount = slice.count
        )
        bindDrilldownCallbacks(sheet)
        sheet.show(childFragmentManager, StatsCharacterListBottomSheet.TAG)
    }

    // ===== 차트 생성 =====

    /**
     * 화면에 그릴 조각 하나 — 라벨·건수와 함께 **드릴다운 규칙**을 들고 다닌다.
     *
     * 라벨을 매칭 키로 재사용하면 접힌 '기타'처럼 라벨이 값이 아닌 조각은 조회할 수 없다(S-16·S-17).
     */
    private data class ChartSlice(val label: String, val count: Int, val spec: FieldValueMatchSpec)

    /**
     * 전량 분포 → 표시 조각 + 접힌 '기타' 한 조각. '기타'도 눌러서 목록을 볼 수 있다.
     *
     * [includeOthers]를 끄는 곳은 **순위 차트**다. 파이·표는 전체가 100%가 돼야 하므로 '기타'
     * 조각이 정당하지만, 순위 막대는 "어떤 값 하나가 가장 많은가"를 말하는 그림이라 잘린 값들의
     * **합계 막대**가 끼면 그것이 1위처럼 보인다(값이 아닌 것이 순위 1위가 된다).
     * 순위에서는 잘림을 막대가 아니라 아래 고지 문구가 알린다.
     *
     * [specs]는 **라벨이 값이 아닌 분포**가 실어 보낸 규칙이다(B-196의 자동 구간). `null`이면
     * 라벨이 곧 값이라 종전처럼 라벨로 스펙을 만든다 — 그것이 지금까지의 전부였다.
     * 스펙이 있는데 무시하고 라벨로 조회하면 구간 조각이 **어떤 입력에서도 0명**이 된다(S-16).
     */
    private fun toSlices(
        view: ValueDistributions.View,
        includeOthers: Boolean = true,
        specs: Map<String, FieldValueMatchSpec>? = null
    ): List<ChartSlice> {
        val specOf = { label: String ->
            specs?.get(label) ?: FieldValueMatchSpec.Values(label)
        }
        val slices = view.shown.map { ChartSlice(it.label, it.count, specOf(it.label)) }
        if (!includeOthers || !view.hasHidden) return slices
        // 접힌 '기타'의 스펙은 **잘린 라벨들의 스펙을 합친 것**이다. 라벨을 그대로 값으로 쓰면
        // 구간으로 접힌 분포에서 '기타'가 0명이 된다 — 라벨이 값이 아니기 때문이다.
        val hiddenValues = view.hiddenLabels.flatMapTo(mutableSetOf()) { label ->
            (specOf(label) as? FieldValueMatchSpec.Values)?.values ?: setOf(label)
        }
        return slices + ChartSlice(
            getString(R.string.stats_distribution_others, view.hiddenKinds),
            view.hiddenCount,
            FieldValueMatchSpec.Values(hiddenValues)
        )
    }

    /**
     * 자동 구간으로 접었다는 고지 (B-196 · R-14).
     *
     * 상한은 감추는 장치가 아니라 접는 장치다 — 접은 사실과 **직접 구간을 정하는 경로**를
     * 함께 말한다(자율성 우선: 원하는 구간이 있으면 사용자가 정할 수 있다).
     */
    private fun createAutoBinNote(preFoldKinds: Int, limit: Int): TextView =
        TextView(requireContext()).apply {
            text = getString(R.string.stats_distribution_auto_binned_note, preFoldKinds, limit)
            textSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }

    /** 잘림 고지 문구 — 상한 숫자는 상수가 아니라 그 필드의 설정값이 단일 소스다(R-14). */
    private fun createTruncationNote(view: ValueDistributions.View, limit: Int): TextView =
        TextView(requireContext()).apply {
            text = getString(
                R.string.stats_distribution_others_note,
                limit, view.hiddenKinds, view.hiddenCount
            )
            textSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
            layoutParams = lp
        }

    private fun createChartForDistribution(
        entries: List<ChartSlice>,
        chartType: FieldStatsConfig.ChartType
    ): View {
        if (entries.isEmpty()) {
            return TextView(requireContext()).apply {
                text = getString(R.string.stats_no_data)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 24)
            }
        }
        val chartHeight = resources.getDimensionPixelSize(R.dimen.stats_chart_height)

        return when (chartType) {
            FieldStatsConfig.ChartType.PIE, FieldStatsConfig.ChartType.DONUT -> {
                createPieChart(entries, chartType == FieldStatsConfig.ChartType.DONUT, chartHeight)
            }
            FieldStatsConfig.ChartType.BAR -> {
                createBarChart(entries, chartHeight)
            }
            FieldStatsConfig.ChartType.HORIZONTAL_BAR -> {
                createHorizontalBarChart(entries, chartHeight)
            }
        }
    }

    private fun createPieChart(
        entries: List<ChartSlice>,
        isDonut: Boolean,
        chartHeight: Int
    ): PieChart {
        val chart = PieChart(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, chartHeight
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm) }
        }
        val pieEntries = entries.map { PieEntry(it.count.toFloat(), it.label) }
        val captionSp = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val dataSet = PieDataSet(pieEntries, "").apply {
            colors = chartColors()
            valueTextSize = captionSp
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }
        chart.apply {
            data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = if (isDonut) 55f else 40f
            setHoleColor(ContextCompat.getColor(requireContext(), R.color.surface))
            setTransparentCircleColor(ContextCompat.getColor(requireContext(), R.color.surface))
            setUsePercentValues(true)
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            setEntryLabelColor(Color.WHITE)
            animateY(600)
            invalidate()
        }
        return chart
    }

    private fun createBarChart(entries: List<ChartSlice>, chartHeight: Int): BarChart {
        val chart = BarChart(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, chartHeight
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm) }
        }
        val barEntries = entries.mapIndexed { i, e -> BarEntry(i.toFloat(), e.count.toFloat()) }
        val labels = entries.map { it.label }
        val dataSet = BarDataSet(barEntries, "").apply {
            colors = chartColors()
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
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
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(600)
            invalidate()
        }
        return chart
    }

    private fun createHorizontalBarChart(entries: List<ChartSlice>, chartHeight: Int): HorizontalBarChart {
        val height = (entries.size * 40).coerceAtLeast(chartHeight / 2)
            .coerceAtMost(chartHeight)
        val chart = HorizontalBarChart(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm) }
        }
        val barEntries = entries.mapIndexed { i, e -> BarEntry(i.toFloat(), e.count.toFloat()) }
        val labels = entries.map { it.label }
        val dataSet = BarDataSet(barEntries, "").apply {
            colors = chartColors()
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
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
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateX(600)
            invalidate()
        }
        return chart
    }

    private fun createRankingChart(entries: List<ChartSlice>): View {
        if (entries.isEmpty()) {
            return TextView(requireContext()).apply {
                text = getString(R.string.stats_no_data)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 24)
            }
        }
        val chartHeight = resources.getDimensionPixelSize(R.dimen.stats_chart_height)
        return createHorizontalBarChart(entries, chartHeight)
    }

    private fun createHistogramChart(histogram: Map<String, Int>): BarChart {
        val chartHeight = resources.getDimensionPixelSize(R.dimen.stats_chart_height)
        val entries = histogram.entries.toList()
        val chart = BarChart(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, chartHeight
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm) }
        }
        val barEntries = entries.mapIndexed { i, e -> BarEntry(i.toFloat(), e.value.toFloat()) }
        val labels = entries.map { it.key }
        val dataSet = BarDataSet(barEntries, "").apply {
            colors = chartColors()
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
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
            xAxis.labelRotationAngle = -45f
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(600)
            invalidate()
        }
        return chart
    }

    // ===== 상세 테이블 =====

    /**
     * 값·건수·비율 표.
     *
     * **비율의 분모는 언제나 전체 합**이다(S-17). 종전에는 계산 계층이 상위 N개만 남기고 넘겨
     * 분모가 '상위 N개의 합'이 되어 각 값의 점유율이 부풀려졌다 — 편향을 발견하려는 화면에서
     * 편향의 정도 자체가 왜곡됐다. 잘린 나머지는 '기타' 행으로 함께 보여 존재를 알린다(R-14).
     */
    private fun createDistributionTable(view: ValueDistributions.View, limit: Int): TableLayout {
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity

        val table = TableLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = marginSm }
            isStretchAllColumns = true
        }

        // 헤더
        val headerRow = TableRow(requireContext())
        headerRow.addView(makeTableCell(getString(R.string.stats_table_value), textSizeSp, true))
        headerRow.addView(makeTableCell(getString(R.string.stats_table_count), textSizeSp, true))
        headerRow.addView(makeTableCell(getString(R.string.stats_table_ratio), textSizeSp, true))
        table.addView(headerRow)

        view.shown.forEach { slice ->
            val row = TableRow(requireContext())
            row.addView(makeTableCell(slice.label, textSizeSp, false))
            row.addView(makeTableCell(slice.count.toString(), textSizeSp, false))
            row.addView(makeTableCell(String.format("%.1f%%", view.ratioOf(slice.count)), textSizeSp, false))
            table.addView(row)
        }

        // 잘린 값 묶음 — 몇 종 몇 건인지 반드시 함께 보인다(R-14).
        if (view.hasHidden) {
            val row = TableRow(requireContext())
            row.addView(makeTableCell(
                getString(R.string.stats_distribution_others, view.hiddenKinds), textSizeSp, false))
            row.addView(makeTableCell(view.hiddenCount.toString(), textSizeSp, false))
            row.addView(makeTableCell(String.format("%.1f%%", view.hiddenRatio()), textSizeSp, false))
            table.addView(row)
        }

        return table
    }

    private fun createNumericSummaryView(summary: NumericSummaryData): LinearLayout {
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
            layoutParams = lp

            addView(makeTextView(getString(R.string.stats_numeric_min, summary.min), textSizeSp))
            addView(makeTextView(getString(R.string.stats_numeric_max, summary.max), textSizeSp))
            addView(makeTextView(getString(R.string.stats_numeric_avg, summary.avg), textSizeSp))
            addView(makeTextView(getString(R.string.stats_numeric_median, summary.median), textSizeSp))
            addView(makeTextView(getString(R.string.stats_numeric_stddev, summary.stdDev), textSizeSp))
        }
    }

    private fun makeTableCell(text: String, textSizeSp: Float, isHeader: Boolean): TextView {
        val pad = resources.getDimensionPixelSize(R.dimen.stats_margin_xs)
        return TextView(requireContext()).apply {
            this.text = text
            this.textSize = textSizeSp
            setPadding(pad, pad, pad, pad)
            if (isHeader) {
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            } else {
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
        }
    }

    private fun makeTextView(text: String, textSizeSp: Float): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            this.textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_xs)
            layoutParams = lp
        }
    }

    // ===== 교차 분석 다이얼로그 =====

    /**
     * 교차분석 다이얼로그 (B-4).
     *
     * 집계 축(캐릭터/사건)을 먼저 고르고, 세 스피너는 **그 축의 필드만** 싣는다.
     * 축이 다른 필드를 섞으면 셀이 무엇의 개수인지 말할 수 없으므로 아예 고를 수 없게 한다
     * (계산 쪽에도 같은 판정이 남아 있어, 어떤 경로로 들어와도 조용히 실패하지 않는다).
     */
    private fun showCrossAnalysisDialog() {
        if (!isAdded) return
        // 로딩 전에 눌러도 버튼은 눌린다 — 아무 일도 일어나지 않으면 고장과 구분되지 않는다.
        val insights = viewModel.fieldInsights.value ?: run {
            Toast.makeText(requireContext(), R.string.stats_cross_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        // **작품 필드를 캐릭터 축에 싣지 않는다.** 종전에는 불리언(`== 사건`)이라 작품 카드가
        // `false` 쪽으로 떨어져 '캐릭터 필드' 목록에 섰다 — 고르면 조회가 그 id를 캐릭터
        // 색인에서 못 찾아 *"작품 필터를 확인하세요"*라는 **거짓 사유**로 실패했다(R-17 —
        // 눌렀는데 아무 일도 안 되거나 엉뚱한 사유가 뜨는 자리는 없어야 한다).
        //
        // **작품 축을 새로 열지 않는 것은 사용자 확정이다**(B-66 보류 — *"교차분석 축에도
        // 작품은 없습니다 … 골랐는데 아무 일도 일어나지 않는 자리는 없어야 합니다"*).
        // 지금 동작은 그 확정이 전제한 상태와 어긋나 있었던 것이고, 여기서 되돌린다.
        val byAxis = insights.groupBy { axisOf(it.fieldDefinition) }
        // 축별로 2개 이상인 축만 후보다 — 축 안에서 두 필드를 골라야 표가 만들어진다.
        val axisOptions = mutableListOf<Pair<String, List<FieldInsightResult>>>()
        byAxis[StatsEntityAxis.CHARACTER]?.takeIf { it.size >= 2 }
            ?.let { axisOptions.add(getString(R.string.stats_cross_axis_character) to it) }
        byAxis[StatsEntityAxis.EVENT]?.takeIf { it.size >= 2 }
            ?.let { axisOptions.add(getString(R.string.stats_cross_axis_event) to it) }

        if (axisOptions.isEmpty()) {
            Toast.makeText(requireContext(), R.string.stats_cross_need_two_fields, Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cross_analysis, null)
        val spinnerAxis = dialogView.findViewById<Spinner>(R.id.spinnerAxis)
        val axisLabel = dialogView.findViewById<TextView>(R.id.axisLabel)
        val axisPurpose = dialogView.findViewById<TextView>(R.id.axisPurpose)
        val spinner1 = dialogView.findViewById<Spinner>(R.id.spinnerField1)
        val spinner2 = dialogView.findViewById<Spinner>(R.id.spinnerField2)
        val spinnerFilter = dialogView.findViewById<Spinner>(R.id.spinnerFilterField)
        val editFilterValue = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editFilterValue)

        // 축이 하나뿐이면 고를 것이 없으므로 선택기를 숨긴다 (조작 마찰 최소화).
        val singleAxis = axisOptions.size == 1
        spinnerAxis.visibility = if (singleAxis) View.GONE else View.VISIBLE
        axisLabel.visibility = if (singleAxis) View.GONE else View.VISIBLE
        // 목적문은 그 설정이 보일 때만 뜻이 있다 — 라벨과 함께 숨는다(R-25).
        axisPurpose.visibility = if (singleAxis) View.GONE else View.VISIBLE

        spinnerAxis.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, axisOptions.map { it.first }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // 현재 축의 필드 id — 실행 시 스피너 위치를 id로 옮기는 데 쓴다.
        var currentFieldIds: List<Long> = emptyList()

        fun applyAxis(axisIndex: Int) {
            val fields = axisOptions[axisIndex].second
            currentFieldIds = fields.map { it.fieldDefinition.id }
            val fieldNames = fields.map { insight ->
                val uni = insight.universeName.takeIf { it.isNotEmpty() }?.let { "$it · " } ?: ""
                "$uni[${insight.fieldDefinition.groupName}] ${insight.fieldDefinition.name}"
            }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fieldNames)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner1.adapter = adapter
            spinner2.adapter = adapter
            spinner1.setSelection(0)
            spinner2.setSelection(if (fieldNames.size > 1) 1 else 0)

            // 필터 필드도 같은 축에서만 고른다 (다른 축 필터는 대상 집합을 좁힐 수 없다).
            val filterNames = listOf(getString(R.string.stats_cross_filter_none)) + fieldNames
            spinnerFilter.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, filterNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerFilter.setSelection(0)
        }

        applyAxis(0)
        spinnerAxis.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyAxis(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // B-28: 통보는 있었으나 창이 함께 닫혀, 고른 축·필드·필터값을 다시 처음부터 골라야 했다.
        // 알리는 것과 고칠 자리를 남기는 것은 다른 일이다 — 실패 시 창을 유지한다.
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.stats_cross_analysis)
            .setView(dialogView)
            .setPositiveButton(R.string.stats_cross_run, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setValidatedPositiveButton {
            val ids = currentFieldIds
            if (ids.size < 2) {
                Toast.makeText(
                    requireContext(), R.string.stats_cross_need_two_fields, Toast.LENGTH_LONG
                ).show()
                return@setValidatedPositiveButton false
            }
            val f1 = ids[spinner1.selectedItemPosition.coerceIn(0, ids.size - 1)]
            val f2 = ids[spinner2.selectedItemPosition.coerceIn(0, ids.size - 1)]
            val filterPos = spinnerFilter.selectedItemPosition
            val filterFieldId = if (filterPos in 1..ids.size) ids[filterPos - 1] else null
            val filterValue = editFilterValue.text?.toString()?.takeIf { it.isNotBlank() }
            viewModel.loadCrossAnalysis(f1, f2, filterFieldId, filterValue)
            true
        }
        dialog.show()
    }

    // ===== 교차 분석 결과 표시 =====

    private fun showCrossAnalysisResult(result: CrossAnalysisResult) {
        if (!isAdded) return
        binding.cardCrossAnalysis.visibility = View.VISIBLE
        val isEventAxis = result.axis == CrossAxis.EVENT
        val axisPrefix = if (isEventAxis) getString(R.string.stats_event_field_prefix) + " · " else ""
        binding.crossAnalysisTitle.text =
            axisPrefix + getString(R.string.stats_cross_title, result.field1Name, result.field2Name)

        // 셀 단위·필터·다중값·세계관 통합 여부 고지 — 표를 읽는 기준을 화면에서 알 수 있어야 한다.
        val captions = mutableListOf<String>()
        captions.add(
            getString(
                if (isEventAxis) R.string.stats_cross_cell_unit_event else R.string.stats_cross_cell_unit_character
            )
        )
        if (result.filterFieldName != null && result.filterValue != null) {
            val filterRes =
                if (isEventAxis) R.string.stats_cross_filter_info_events else R.string.stats_cross_filter_info
            captions.add(getString(filterRes, result.filterFieldName, result.filterValue, result.filteredCount, result.totalCount))
        }
        if (result.mergedUniverseCount > 1) {
            captions.add(getString(R.string.stats_cross_merged_universes, result.mergedUniverseCount))
        }
        if (result.multiValue) {
            val multiRes =
                if (isEventAxis) R.string.stats_cross_multi_value_note_events else R.string.stats_cross_multi_value_note
            captions.add(getString(multiRes))
        }
        // 표시 상한은 **한 번만** 적용하고 고지·표·차트가 그 하나를 나눠 쓴다 — 자리마다 따로
        // 접으면 셋이 갈릴 수 있고(R-19), 접는 일 자체도 원본 셀 수만큼 도는 계산이라 세 번 돌 일이 아니다.
        val folded = foldCross(result.crossTable)

        // 접었으면 개수로 알린다(R-14). 표에서 '기타' 줄을 보고 *"이게 뭐지"* 하지 않도록
        // **여기서 먼저** 말한다 — 접힌 것은 사라진 것이 아니라 합쳐진 것이다.
        if (folded.hasHidden) {
            // 한 축만 접혔을 때 *"열 0종"*이라 적지 않는다 — 0은 접힌 것이 아니다.
            val foldedAxes = buildList {
                if (folded.hasHiddenRows) {
                    add(getString(R.string.stats_cross_folded_rows, folded.hiddenRowKinds))
                }
                if (folded.hasHiddenCols) {
                    add(getString(R.string.stats_cross_folded_cols, folded.hiddenColKinds))
                }
            }.joinToString(" · ")
            captions.add(
                getString(R.string.stats_cross_folded_note, DisplayCap.CROSS_AXIS_LIMIT, foldedAxes)
            )
        }
        if (captions.isNotEmpty()) {
            binding.crossAnalysisFilter.visibility = View.VISIBLE
            binding.crossAnalysisFilter.text = captions.joinToString("\n")
        } else {
            binding.crossAnalysisFilter.visibility = View.GONE
        }

        // 교차표 렌더링
        renderCrossTable(result, folded)

        // 스택 바 차트
        renderCrossBarChart(result, folded)
    }

    private fun renderCrossTable(result: CrossAnalysisResult, folded: CrossTableFold.Folded) {
        val container = binding.crossTableContainer
        container.removeAllViews()

        if (result.crossTable.isEmpty()) {
            // 빈 표를 조용히 두면 "실행했는데 아무 일도 안 일어난" 화면이 된다 — 사유를 밝힌다
            // (변수 제어: 검증→알림). 계산 필드 누락이라는 원인 자체는 provider에서 고쳤고(S-8),
            // 이것은 진짜로 데이터가 없는 경우까지 덮는 방어선이다.
            container.addView(TextView(requireContext()).apply {
                text = getString(
                    if (result.axis == CrossAxis.EVENT) R.string.stats_cross_empty_events
                    else R.string.stats_cross_empty_characters
                )
                textSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                val pad = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
                setPadding(pad, pad, pad, pad)
            })
            return
        }

        val textSizeSp = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity

        val table = TableLayout(requireContext()).apply {
            isStretchAllColumns = false
        }

        // 헤더 행
        val headerRow = TableRow(requireContext())
        headerRow.addView(makeTableCell("", textSizeSp, true))
        folded.cols.forEach { v2 ->
            headerRow.addView(makeTableCell(v2, textSizeSp, true).apply { gravity = Gravity.CENTER })
        }
        table.addView(headerRow)

        // 데이터 행 — 행도 열도 합계 내림차순이다(넘친 것은 '기타'로 **합쳐져** 남는다).
        folded.rows.forEach { v1 ->
            val row = TableRow(requireContext())
            row.addView(makeTableCell(v1, textSizeSp, true))
            folded.cols.forEach { v2 ->
                row.addView(
                    makeTableCell(folded.count(v1, v2).toString(), textSizeSp, false)
                        .apply { gravity = Gravity.CENTER }
                )
            }
            table.addView(row)
        }

        container.addView(table)
    }

    /**
     * 교차표를 표시 상한으로 접는다 — **이 화면에서 유일하게 비용이 곱셈인 자리다**(B-212).
     *
     * 셀 수가 `(필드1 값 종수 × 필드2 값 종수)`이고 두 축 모두 캐릭터가 늘면 함께 늘어난다.
     * 자유 입력 필드 둘을 고르면 값 종수가 사실상 캐릭터 수를 따라가므로, 상한이 없으면
     * 규모가 아니라 **차수**의 문제가 된다(200종 × 200종 = 40,000칸, 전부 `TextView`다).
     *
     * 넘친 것은 버리지 않고 '기타'로 합친다 — [CrossTableFold]가 총합을 보존하므로
     * 표·차트·요약이 서로 다른 말을 하지 않는다(R-19).
     */
    private fun foldCross(crossTable: Map<String, Map<String, Int>>): CrossTableFold.Folded =
        CrossTableFold.fold(
            table = crossTable,
            rowLimit = DisplayCap.CROSS_AXIS_LIMIT,
            colLimit = DisplayCap.CROSS_AXIS_LIMIT,
            rowOtherLabel = { getString(R.string.stats_distribution_others, it) },
            colOtherLabel = { getString(R.string.stats_distribution_others, it) }
        )

    /**
     * 표와 **같은 [folded]**를 받는다 — 따로 접으면 그림과 숫자가 다른 말을 할 수 있다(R-19).
     * 차트 쪽 비용도 같은 곱이다: 데이터셋이 열 종수, 막대가 행 종수다.
     */
    private fun renderCrossBarChart(result: CrossAnalysisResult, folded: CrossTableFold.Folded) {
        val chart = binding.crossBarChart
        if (result.crossTable.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE

        val field2Values = folded.cols
        if (field2Values.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val field1Values = folded.rows

        val dataSets = mutableListOf<BarDataSet>()
        val colors = chartColors()

        field2Values.forEachIndexed { idx, v2 ->
            val barEntries = field1Values.mapIndexed { i, v1 ->
                BarEntry(i.toFloat(), folded.count(v1, v2).toFloat())
            }
            dataSets.add(BarDataSet(barEntries, v2).apply {
                color = colors[idx % colors.size]
                valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        if (value > 0) value.toInt().toString() else ""
                }
            })
        }

        chart.apply {
            data = BarData(dataSets.toList()).apply {
                barWidth = 0.8f / field2Values.size
            }
            if (field2Values.size > 1) {
                groupBars(0f, 0.08f, 0.02f)
            }
            description.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(field1Values)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            xAxis.setDrawGridLines(false)
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisRight.isEnabled = false
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            animateY(600)
            invalidate()
        }
    }

    private fun chartColors(): List<Int> =
        ChartTheme.palette(requireContext())

    // ===== 인라인 분석 설정 =====

    /**
     * 카드의 톱니 — 분석 항목(종류·차트·표시 개수)을 바꾼다.
     *
     * 머지된 카드(세계관 여러 개를 한 장으로 합친 카드)에서는 **그룹 전체에 적용**이 기본이다.
     * 대표 def 하나만 고치면 작품 필터를 바꿔 형제가 대표가 되는 순간 옛 설정이 되살아나
     * "바꾼 것이 안 바뀐" 것으로 보인다(B-34). 다만 세계관마다 일부러 다르게 둘 자유도
     * 남겨야 하므로(자율성 우선) 체크박스로 끌 수 있게 한다.
     */
    private fun showAnalysisSettingsBottomSheet(insight: FieldInsightResult) {
        if (!isAdded) return
        val fieldDef = insight.fieldDefinition
        val currentConfig = insight.statsConfig
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val allowedTypes = FieldStatsConfig.StatsType.forFieldType(fieldDef.fieldType)
        val chartTypes = FieldStatsConfig.ChartType.entries
        val limitOptions = listOf(5, 10, 15, 20)

        // 첫 번째 분석 항목을 기준으로 표시 (대부분 1개)
        val currentEntry = currentConfig.analyses.firstOrNull() ?: FieldStatsConfig.AnalysisEntry()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // 분석 타입 Spinner
        container.addView(TextView(ctx).apply {
            text = getString(R.string.stats_inline_analysis_type)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
        })
        val spinnerType = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, allowedTypes.map { it.label }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val idx = allowedTypes.indexOf(currentEntry.type)
            if (idx >= 0) setSelection(idx)
        }
        container.addView(spinnerType)

        // 차트 타입 Spinner
        container.addView(TextView(ctx).apply {
            text = getString(R.string.stats_inline_chart_type)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * density).toInt()
            layoutParams = lp
        })
        val spinnerChart = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, chartTypes.map { it.label }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val idx = chartTypes.indexOf(currentEntry.chart)
            if (idx >= 0) setSelection(idx)
        }
        container.addView(spinnerChart)

        // 표시 개수 Spinner
        container.addView(TextView(ctx).apply {
            text = getString(R.string.stats_inline_limit)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * density).toInt()
            layoutParams = lp
        })
        val spinnerLimit = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, limitOptions.map { it.toString() }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val idx = limitOptions.indexOf(currentEntry.limit)
            if (idx >= 0) setSelection(idx)
        }
        container.addView(spinnerLimit)

        // ── 머지된 카드: 적용 범위 ──
        val mergedIds = insight.mergedFieldDefIds
        val applyToGroup = if (mergedIds.size > 1) {
            android.widget.CheckBox(ctx).apply {
                // '다른' 세계관 필드 수 — 자기 자신을 빼야 사용자가 적용 범위를 정확히 읽는다.
                text = getString(R.string.stats_inline_apply_group, mergedIds.size - 1)
                isChecked = true
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (12 * density).toInt()
                layoutParams = lp
            }.also { container.addView(it) }
        } else null

        // 형제들의 현재 설정이 서로 다르면 그 사실을 알린다 — 모르고 덮어쓰지 않도록.
        if (applyToGroup != null) {
            // 첫 항목만 비교하면 "A는 분석 2개, B는 1개"인 차이를 못 잡는다 — 목록 전체를 본다.
            val siblingConfigs = viewModel.fieldDefsByIds(mergedIds)
                .map { FieldStatsConfig.fromConfig(it.config).analyses }
            if (siblingConfigs.distinct().size > 1) {
                container.addView(TextView(ctx).apply {
                    text = getString(R.string.stats_inline_group_differs)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                })
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.stats_inline_config_title)
            .setView(container)
            .setPositiveButton(R.string.stats_inline_save) { _, _ ->
                val selectedType = allowedTypes[spinnerType.selectedItemPosition.coerceIn(0, allowedTypes.size - 1)]
                val selectedChart = chartTypes[spinnerChart.selectedItemPosition.coerceIn(0, chartTypes.size - 1)]
                val selectedLimit = limitOptions[spinnerLimit.selectedItemPosition.coerceIn(0, limitOptions.size - 1)]

                val newEntry = FieldStatsConfig.AnalysisEntry(
                    type = selectedType,
                    chart = selectedChart,
                    limit = selectedLimit
                )
                if (applyToGroup?.isChecked == true) {
                    // 그룹 전체 — 각 def의 나머지 설정은 그대로 두고 분석 항목만 얹는다.
                    viewModel.updateMergedFieldAnalysis(mergedIds, newEntry)
                } else {
                    // 기존 설정의 나머지(binning, valueLabels 등)는 유지하고 **첫 분석 항목만** 교체한다.
                    // 목록 전체를 덮으면 사용자가 따로 만들어 둔 두 번째 분석 항목이 조용히 사라진다.
                    val keptAnalyses = if (currentConfig.analyses.size <= 1) listOf(newEntry)
                                       else listOf(newEntry) + currentConfig.analyses.drop(1)
                    val newConfig = currentConfig.copy(analyses = keptAnalyses)
                    viewModel.updateFieldStatsConfig(fieldDef, newConfig)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
