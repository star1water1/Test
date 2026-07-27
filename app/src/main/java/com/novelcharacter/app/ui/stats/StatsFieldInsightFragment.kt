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
        viewModel.loadFieldInsights()
    }

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                // 교차분석 실패 사유(축 불일치·필드 없음)는 구체적인 문구로 온다 — 그대로 보여준다.
                val ctx = context ?: return@observe
                val message = error.takeIf { it.isNotBlank() } ?: getString(R.string.stats_load_error)
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            }
        }

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
                val entityPrefix = if (insight.fieldDefinition.entityType == com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT) {
                    getString(R.string.stats_event_field_prefix) + " · "
                } else ""
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
                    showAnalysisSettingsBottomSheet(insight.fieldDefinition, insight.statsConfig)
                }
            }
            addView(settingsBtn)
        }
    }

    private fun makeCompletionText(insight: FieldInsightResult): TextView {
        val rate = if (insight.totalCount > 0) insight.filledCount * 100f / insight.totalCount else 0f
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        // 사건 필드의 모수는 캐릭터가 아니라 사건 수 — 단위를 구분해 표기
        val isEventField = insight.fieldDefinition.entityType == com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT
        val completionRes = if (isEventField) R.string.stats_field_insight_completion_events else R.string.stats_field_insight_completion
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
                val chart = createChartForDistribution(data, result.entry.chart, result.entry.limit)
                attachChartTapListener(chart, data.entries.sortedByDescending { it.value }.take(result.entry.limit).map { it.key }, insight)
                wrapper.addView(chart)
                wrapper.addView(createDistributionTable(data, result.entry.limit))
            }
            FieldStatsConfig.StatsType.RANKING -> {
                val data = result.distributionData ?: return wrapper
                val sorted = data.entries.sortedByDescending { it.value }.take(result.entry.limit)
                val chart = createRankingChart(sorted)
                attachChartTapListener(chart, sorted.map { it.key }, insight)
                wrapper.addView(chart)
            }
            FieldStatsConfig.StatsType.NUMERIC -> {
                val summary = result.numericSummary ?: return wrapper
                wrapper.addView(createNumericSummaryView(summary))
                if (summary.histogram.isNotEmpty()) {
                    wrapper.addView(createHistogramChart(summary.histogram))
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
    private fun attachChartTapListener(chart: View, labels: List<String>, insight: FieldInsightResult) {
        val open = { value: String -> showDrilldownBottomSheet(insight, value) }
        when (chart) {
            is PieChart -> {
                chart.setTouchEnabled(true)
                chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        val pieEntry = e as? PieEntry ?: return
                        open(pieEntry.label)
                    }
                    override fun onNothingSelected() {}
                })
            }
            is HorizontalBarChart -> {
                chart.setTouchEnabled(true)
                chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        val index = h?.x?.toInt() ?: return
                        open(labels.getOrNull(index) ?: return)
                    }
                    override fun onNothingSelected() {}
                })
            }
            is BarChart -> {
                chart.setTouchEnabled(true)
                chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        val index = h?.x?.toInt() ?: return
                        open(labels.getOrNull(index) ?: return)
                    }
                    override fun onNothingSelected() {}
                })
            }
        }
    }

    private fun showDrilldownBottomSheet(insight: FieldInsightResult, value: String) {
        val isEvent = insight.fieldDefinition.entityType ==
            com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT
        val sheet = StatsCharacterListBottomSheet.newInstance(
            fieldDefIds = insight.mergedFieldDefIds,
            fieldName = insight.fieldDefinition.name,
            selectedValue = value,
            isEventAxis = isEvent
        )
        sheet.onCharacterClick = { characterId ->
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigate(R.id.characterDetailFragment, bundle)
        }
        sheet.onEventClick = { year ->
            // 사건 상세 화면은 없다 — 전역 검색과 **같은 규약**으로 연표를 그 연도에 맞춰 연다
            // (center_year는 TimelineFragment.onResume이 소비한다).
            val prefs = requireContext()
                .getSharedPreferences("timeline_ui_state", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("center_year", year).putBoolean("pending_navigate", true).commit()
            findNavController().navigate(R.id.timelineFragment)
        }
        sheet.show(childFragmentManager, StatsCharacterListBottomSheet.TAG)
    }

    // ===== 차트 생성 =====

    private fun createChartForDistribution(
        data: Map<String, Int>,
        chartType: FieldStatsConfig.ChartType,
        limit: Int
    ): View {
        val entries = data.entries.sortedByDescending { it.value }.take(limit)
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
        entries: List<Map.Entry<String, Int>>,
        isDonut: Boolean,
        chartHeight: Int
    ): PieChart {
        val chart = PieChart(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, chartHeight
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.stats_margin_sm) }
        }
        val pieEntries = entries.map { PieEntry(it.value.toFloat(), it.key) }
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

    private fun createBarChart(entries: List<Map.Entry<String, Int>>, chartHeight: Int): BarChart {
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
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(600)
            invalidate()
        }
        return chart
    }

    private fun createHorizontalBarChart(entries: List<Map.Entry<String, Int>>, chartHeight: Int): HorizontalBarChart {
        val height = (entries.size * 40).coerceAtLeast(chartHeight / 2)
            .coerceAtMost(chartHeight)
        val chart = HorizontalBarChart(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
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
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateX(600)
            invalidate()
        }
        return chart
    }

    private fun createRankingChart(entries: List<Map.Entry<String, Int>>): View {
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

    private fun createDistributionTable(data: Map<String, Int>, limit: Int): TableLayout {
        val sorted = data.entries.sortedByDescending { it.value }.take(limit)
        val total = data.values.sum().toFloat()
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

        sorted.forEach { (value, count) ->
            val ratio = if (total > 0) count / total * 100 else 0f
            val row = TableRow(requireContext())
            row.addView(makeTableCell(value, textSizeSp, false))
            row.addView(makeTableCell(count.toString(), textSizeSp, false))
            row.addView(makeTableCell(String.format("%.1f%%", ratio), textSizeSp, false))
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

        val byAxis = insights.groupBy {
            it.fieldDefinition.entityType == com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT
        }
        // 축별로 2개 이상인 축만 후보다 — 축 안에서 두 필드를 골라야 표가 만들어진다.
        val axisOptions = mutableListOf<Pair<String, List<FieldInsightResult>>>()
        byAxis[false]?.takeIf { it.size >= 2 }
            ?.let { axisOptions.add(getString(R.string.stats_cross_axis_character) to it) }
        byAxis[true]?.takeIf { it.size >= 2 }
            ?.let { axisOptions.add(getString(R.string.stats_cross_axis_event) to it) }

        if (axisOptions.isEmpty()) {
            Toast.makeText(requireContext(), R.string.stats_cross_need_two_fields, Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cross_analysis, null)
        val spinnerAxis = dialogView.findViewById<Spinner>(R.id.spinnerAxis)
        val axisLabel = dialogView.findViewById<TextView>(R.id.axisLabel)
        val spinner1 = dialogView.findViewById<Spinner>(R.id.spinnerField1)
        val spinner2 = dialogView.findViewById<Spinner>(R.id.spinnerField2)
        val spinnerFilter = dialogView.findViewById<Spinner>(R.id.spinnerFilterField)
        val editFilterValue = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editFilterValue)

        // 축이 하나뿐이면 고를 것이 없으므로 선택기를 숨긴다 (조작 마찰 최소화).
        val singleAxis = axisOptions.size == 1
        spinnerAxis.visibility = if (singleAxis) View.GONE else View.VISIBLE
        axisLabel.visibility = if (singleAxis) View.GONE else View.VISIBLE

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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.stats_cross_analysis)
            .setView(dialogView)
            .setPositiveButton(R.string.stats_cross_run) { _, _ ->
                val ids = currentFieldIds
                if (ids.size < 2) {
                    // 고른 축·필드·필터값을 통보 없이 버리지 않는다(S-12와 같은 부류).
                    Toast.makeText(
                        requireContext(), R.string.stats_cross_need_two_fields, Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                val f1 = ids[spinner1.selectedItemPosition.coerceIn(0, ids.size - 1)]
                val f2 = ids[spinner2.selectedItemPosition.coerceIn(0, ids.size - 1)]
                val filterPos = spinnerFilter.selectedItemPosition
                val filterFieldId = if (filterPos in 1..ids.size) ids[filterPos - 1] else null
                val filterValue = editFilterValue.text?.toString()?.takeIf { it.isNotBlank() }
                viewModel.loadCrossAnalysis(f1, f2, filterFieldId, filterValue)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        if (captions.isNotEmpty()) {
            binding.crossAnalysisFilter.visibility = View.VISIBLE
            binding.crossAnalysisFilter.text = captions.joinToString("\n")
        } else {
            binding.crossAnalysisFilter.visibility = View.GONE
        }

        // 교차표 렌더링
        renderCrossTable(result)

        // 스택 바 차트
        renderCrossBarChart(result)
    }

    private fun renderCrossTable(result: CrossAnalysisResult) {
        val container = binding.crossTableContainer
        container.removeAllViews()

        val crossTable = result.crossTable
        if (crossTable.isEmpty()) {
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

        // 모든 field2 값 수집
        val field2Values = crossTable.values.flatMap { it.keys }.distinct().sorted()

        val textSizeSp = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val pad = resources.getDimensionPixelSize(R.dimen.stats_margin_xs)

        val table = TableLayout(requireContext()).apply {
            isStretchAllColumns = false
        }

        // 헤더 행
        val headerRow = TableRow(requireContext())
        headerRow.addView(makeTableCell("", textSizeSp, true))
        field2Values.forEach { v2 ->
            headerRow.addView(makeTableCell(v2, textSizeSp, true).apply { gravity = Gravity.CENTER })
        }
        table.addView(headerRow)

        // 데이터 행
        crossTable.entries.sortedByDescending { it.value.values.sum() }.forEach { (v1, v2Map) ->
            val row = TableRow(requireContext())
            row.addView(makeTableCell(v1, textSizeSp, true))
            field2Values.forEach { v2 ->
                val count = v2Map[v2] ?: 0
                row.addView(makeTableCell(count.toString(), textSizeSp, false).apply { gravity = Gravity.CENTER })
            }
            table.addView(row)
        }

        container.addView(table)
    }

    private fun renderCrossBarChart(result: CrossAnalysisResult) {
        val chart = binding.crossBarChart
        val crossTable = result.crossTable
        if (crossTable.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE

        val field2Values = crossTable.values.flatMap { it.keys }.distinct().sorted()
        if (field2Values.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val field1Values = crossTable.keys.sortedByDescending { crossTable[it]?.values?.sum() ?: 0 }

        val dataSets = mutableListOf<BarDataSet>()
        val colors = chartColors()

        field2Values.forEachIndexed { idx, v2 ->
            val barEntries = field1Values.mapIndexed { i, v1 ->
                BarEntry(i.toFloat(), (crossTable[v1]?.get(v2) ?: 0).toFloat())
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

    private fun showAnalysisSettingsBottomSheet(fieldDef: FieldDefinition, currentConfig: FieldStatsConfig) {
        if (!isAdded) return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val allowedTypes = FieldStatsConfig.StatsType.forFieldType(fieldDef.type)
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
                // 기존 설정의 나머지(binning, valueLabels 등)는 유지하고 analyses만 교체
                val newConfig = currentConfig.copy(analyses = listOf(newEntry))
                viewModel.updateFieldStatsConfig(fieldDef, newConfig)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
