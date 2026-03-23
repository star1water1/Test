package com.novelcharacter.app.ui.stats

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsMainBinding
import com.novelcharacter.app.ui.adapter.RankingAdapter
import com.novelcharacter.app.util.navigateSafe

class StatsMainFragment : Fragment() {

    private var _binding: FragmentStatsMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    // 순위 탭
    private var rankingAdapter: RankingAdapter? = null
    private var rankingInitialized = false
    private var currentRankableFields: List<RankableField> = emptyList()
    private var currentAscending = false
    private var selectedUniverseId: Long? = null
    private var selectedNovelIdForRanking: Long? = null
    private var selectedFieldIndex = -1
    private var selectedBodySizePartIndex = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupObservers()
        setupClickListeners()
        viewModel.loadAllStats()
    }

    private fun setupTabs() {
        val tabLayout = binding.statsTabLayout
        tabLayout.addTab(tabLayout.newTab().setText(R.string.stats_tab_overview))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.stats_tab_ranking))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                updateTabVisibility()
                if (tab?.position == 1 && !rankingInitialized) {
                    initRankingUI()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Fragment 재생성 시 탭 위치가 0이 아닐 수 있으므로 현재 상태 반영
        if (tabLayout.selectedTabPosition == 1) {
            updateTabVisibility()
            if (!rankingInitialized) initRankingUI()
        }
    }

    /** 현재 탭 선택 + 로딩 상태에 따라 visibility를 일원적으로 관리 */
    private fun updateTabVisibility() {
        val isLoading = viewModel.loading.value == true
        val isOverviewTab = binding.statsTabLayout.selectedTabPosition == 0
        binding.contentLayout.visibility = if (!isLoading && isOverviewTab) View.VISIBLE else View.GONE
        binding.rankingLayout.visibility = if (!isLoading && !isOverviewTab) View.VISIBLE else View.GONE
    }

    private fun initRankingUI() {
        rankingInitialized = true
        val ctx = context ?: return

        // RecyclerView
        rankingAdapter = RankingAdapter()
        binding.rankingRecyclerView.layoutManager = LinearLayoutManager(ctx)
        binding.rankingRecyclerView.adapter = rankingAdapter

        // BODY_SIZE 파트 스피너 리스너를 한 번만 등록
        binding.rankingBodySizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, partPos: Int, partId: Long) {
                selectedBodySizePartIndex = partPos
                if (selectedFieldIndex >= 0) executeRanking()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 필드 스피너 리스너
        binding.rankingFieldSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressFieldSpinnerCallback) return
                if (pos == 0) {
                    selectedFieldIndex = -1
                    binding.rankingBodySizeRow.visibility = View.GONE
                    binding.rankingEmpty.visibility = View.VISIBLE
                    binding.rankingRecyclerView.visibility = View.GONE
                    binding.rankingSummary.text = ""
                    return
                }
                selectedFieldIndex = pos - 1
                val field = currentRankableFields.getOrNull(selectedFieldIndex) ?: return

                // BODY_SIZE일 때만 파트 스피너 표시 (adapter만 교체, 리스너는 재등록하지 않음)
                if (field.fieldDef.type == "BODY_SIZE" && field.bodySizeParts != null) {
                    binding.rankingBodySizeRow.visibility = View.VISIBLE
                    val partAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, field.bodySizeParts)
                    partAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.rankingBodySizeSpinner.adapter = partAdapter
                    // adapter 변경 시 onItemSelected(pos=0) 자동 발생 → 거기서 executeRanking() 호출됨
                    return
                } else {
                    binding.rankingBodySizeRow.visibility = View.GONE
                }

                executeRanking()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 정렬 토글
        binding.rankingSortToggle.setOnClickListener {
            currentAscending = !currentAscending
            binding.rankingSortToggle.setImageResource(
                if (currentAscending) android.R.drawable.arrow_up_float
                else android.R.drawable.arrow_down_float
            )
            if (selectedFieldIndex >= 0) executeRanking()
        }

        // 순위 결과 옵저버
        setupRankingObservers()

        // 초기 로딩: cachedSnapshot이 있으면 즉시, 없으면 loading 완료 후 자동 세팅
        if (viewModel.getUniverseList().isNotEmpty()) {
            setupRankingUniverseSpinner()
            viewModel.loadRankableFields(null)
        }
    }

    private var suppressFieldSpinnerCallback = false

    private fun setupRankingObservers() {
        viewModel.rankableFields.observe(viewLifecycleOwner) { fields ->
            currentRankableFields = fields
            // adapter 교체 시 자동 콜백 방지
            suppressFieldSpinnerCallback = true
            populateFieldSpinner(fields)
            _binding?.rankingFieldSpinner?.post {
                suppressFieldSpinnerCallback = false
            }
        }

        viewModel.rankingResult.observe(viewLifecycleOwner) { result ->
            val b = _binding ?: return@observe
            if (result == null) return@observe
            if (result.entries.isEmpty()) {
                b.rankingRecyclerView.visibility = View.GONE
                b.rankingEmpty.visibility = View.VISIBLE
                b.rankingEmpty.text = getString(R.string.stats_ranking_empty)
            } else {
                b.rankingRecyclerView.visibility = View.VISIBLE
                b.rankingEmpty.visibility = View.GONE
                rankingAdapter?.submitList(result.entries)
            }
            b.rankingSummary.text = getString(
                R.string.stats_ranking_summary, result.totalCharacters, result.excludedCount
            )
        }
    }

    private fun setupRankingUniverseSpinner() {
        val ctx = context ?: return
        val universes = viewModel.getUniverseList()
        val items = mutableListOf(getString(R.string.stats_ranking_all_universes))
        items.addAll(universes.map { it.second })

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // 리스너를 adapter 세팅 전에 등록하여 초기 콜백 받음
        binding.rankingUniverseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) {
                    selectedUniverseId = null
                    binding.rankingNovelRow.visibility = View.GONE
                    selectedNovelIdForRanking = null
                } else {
                    val uid = universes.getOrNull(pos - 1)?.first ?: return
                    selectedUniverseId = uid
                    setupRankingNovelSpinner(uid)
                    binding.rankingNovelRow.visibility = View.VISIBLE
                }
                viewModel.loadRankableFields(selectedUniverseId)
                selectedFieldIndex = -1
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.rankingUniverseSpinner.adapter = adapter
    }

    private fun setupRankingNovelSpinner(universeId: Long) {
        val ctx = context ?: return
        val novels = viewModel.getNovelListForUniverse(universeId)
        val items = mutableListOf(getString(R.string.stats_ranking_all_novels))
        items.addAll(novels.map { it.second })

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.rankingNovelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedNovelIdForRanking = if (pos == 0) null else novels.getOrNull(pos - 1)?.first
                if (selectedFieldIndex >= 0) executeRanking()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.rankingNovelSpinner.adapter = adapter
    }

    private fun populateFieldSpinner(fields: List<RankableField>) {
        val ctx = context ?: return
        val items = mutableListOf(getString(R.string.stats_ranking_select_field))
        items.addAll(fields.map { "${it.fieldDef.name} (${getFieldTypeLabel(it)})" })

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.rankingFieldSpinner.adapter = adapter
    }

    private fun getFieldTypeLabel(field: RankableField): String {
        return when (field.fieldDef.type) {
            "NUMBER" -> "숫자"
            "CALCULATED" -> "계산"
            "GRADE" -> "등급"
            "BODY_SIZE" -> "신체"
            "SELECT" -> "빈도"
            "TEXT" -> "빈도"
            "MULTI_TEXT" -> "빈도"
            else -> field.fieldDef.type
        }
    }

    private fun executeRanking() {
        val field = currentRankableFields.getOrNull(selectedFieldIndex) ?: return
        val bodyPartIdx = if (field.fieldDef.type == "BODY_SIZE") selectedBodySizePartIndex else null
        viewModel.loadRanking(
            fieldDefId = field.fieldDef.id,
            ascending = currentAscending,
            bodySizePartIndex = bodyPartIdx,
            novelId = selectedNovelIdForRanking
        )
    }

    override fun onResume() {
        super.onResume()
        // 다른 탭에서 데이터 변경 후 돌아올 때 캐시 무효화하여 최신 데이터 반영
        viewModel.refreshStats()
        if (rankingInitialized) {
            rankingNeedsRefresh = true
        }
    }

    private var rankingNeedsRefresh = false

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            updateTabVisibility()

            // 로딩 완료 후 순위 관련 갱신
            if (!isLoading && rankingInitialized) {
                // 세계관 스피너가 아직 세팅 안 된 경우 (최초 진입)
                if (binding.rankingUniverseSpinner.adapter == null) {
                    setupRankingUniverseSpinner()
                    viewModel.loadRankableFields(null)
                }
                // onResume 후 데이터 갱신: 스피너 선택은 보존하고 필드 목록만 재로딩
                else if (rankingNeedsRefresh) {
                    rankingNeedsRefresh = false
                    viewModel.loadRankableFields(selectedUniverseId)
                    if (selectedFieldIndex >= 0) executeRanking()
                }
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                val ctx = context ?: return@observe
                Toast.makeText(ctx, R.string.stats_load_error, Toast.LENGTH_SHORT).show()
            }
        }

        // 작품 필터 스피너
        viewModel.novelList.observe(viewLifecycleOwner) { novels ->
            val ctx = context ?: return@observe
            val items = mutableListOf(getString(R.string.stats_filter_all))
            items.addAll(novels.map { it.second })
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            // 리스너를 adapter/setSelection 전에 설정하여 콜백 누락 방지
            binding.spinnerNovelFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val novelId = if (pos == 0) null else novels[pos - 1].first
                    if (viewModel.selectedNovelId.value != novelId) {
                        viewModel.setNovelFilter(novelId)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            binding.spinnerNovelFilter.adapter = adapter

            // ViewModel 상태에서 스피너 위치 복원 (Fragment 재생성 시)
            val currentNovelId = viewModel.selectedNovelId.value
            val restoredPos = if (currentNovelId == null) 0
                else novels.indexOfFirst { it.first == currentNovelId }.let { if (it >= 0) it + 1 else 0 }
            binding.spinnerNovelFilter.setSelection(restoredPos, false)
        }

        viewModel.summary.observe(viewLifecycleOwner) { summary ->
            binding.summaryCharCount.text = summary.totalCharacters.toString()
            binding.summaryEventCount.text = summary.totalEvents.toString()
            binding.summaryRelCount.text = summary.totalRelationships.toString()
            binding.summaryNovelCount.text = summary.totalNovels.toString()
            binding.summaryUniverseCount.text = summary.totalUniverses.toString()
            binding.summaryNameCount.text = summary.totalNames.toString()

            // 인사이트
            binding.insightMostActive.text = summary.mostActiveNovel?.let {
                getString(R.string.stats_insight_most_active, it)
            } ?: ""
            binding.insightMostActive.visibility = if (summary.mostActiveNovel != null) View.VISIBLE else View.GONE

            binding.insightMostConnected.text = summary.mostConnectedChar?.let {
                getString(R.string.stats_insight_most_connected, it)
            } ?: ""
            binding.insightMostConnected.visibility = if (summary.mostConnectedChar != null) View.VISIBLE else View.GONE

            // 캐릭터 특화 유형 분포
            val specText = summary.specializationDist
                .filter { it.value > 0 }
                .entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} ${it.value}명" }
            binding.insightSpecialization.text = if (specText.isNotEmpty()) {
                getString(R.string.stats_insight_specialization, specText)
            } else ""
            binding.insightSpecialization.visibility =
                if (specText.isNotEmpty()) View.VISIBLE else View.GONE

            // 가장 많이 사용된 필드 값 TOP 3
            val topFieldText = summary.topFieldValues
                .take(3)
                .joinToString(", ") { "${it.first}:${it.second}(${it.third})" }
            binding.insightTopFieldValues.text = if (topFieldText.isNotEmpty()) {
                getString(R.string.stats_insight_top_field_values, topFieldText)
            } else ""
            binding.insightTopFieldValues.visibility =
                if (topFieldText.isNotEmpty()) View.VISIBLE else View.GONE

            // 사건 밀도 피크
            binding.insightDensityPeak.text = summary.eventDensityPeak?.let {
                getString(R.string.stats_insight_density_peak, it)
            } ?: ""
            binding.insightDensityPeak.visibility =
                if (summary.eventDensityPeak != null) View.VISIBLE else View.GONE

            binding.insightRecentActivity.text =
                getString(R.string.stats_insight_recent_activity, summary.recentActivityCount)
            binding.insightRecentActivity.visibility =
                if (summary.recentActivityCount > 0) View.VISIBLE else View.GONE

            binding.insightContainer.visibility =
                if (summary.totalCharacters > 0) View.VISIBLE else View.GONE
        }

        // 패턴 인사이트 (개선 3)
        viewModel.patternInsights.observe(viewLifecycleOwner) { patterns ->
            populatePatternInsights(patterns)
        }

        // 필드 인사이트 미리보기
        viewModel.fieldInsights.observe(viewLifecycleOwner) { insights ->
            populateInsightPreview(insights)
        }

        viewModel.characterStats.observe(viewLifecycleOwner) { charStats ->
            val topTag = charStats.tagDistribution.entries.firstOrNull()
            binding.charPreview.text = buildString {
                append(getString(R.string.stats_tag_preview, charStats.tagDistribution.size))
                if (topTag != null) append(getString(R.string.stats_tag_top_format, topTag.key, topTag.value))
                if (charStats.complexityScores.isNotEmpty()) {
                    val top = charStats.complexityScores.first()
                    val specInfo = if (top.specialization != CharacterComplexity.Specialization.NONE)
                        " ${top.specialization.icon}${top.specialization.label}" else ""
                    append(" | ${getString(R.string.stats_insight_complex_char, top.name)} (${top.overallPotential.label}$specInfo)")
                }
            }
        }

        viewModel.eventStats.observe(viewLifecycleOwner) { eventStats ->
            binding.eventPreview.text = buildString {
                append(getString(R.string.stats_event_density_preview, eventStats.yearDensity.size))
                append(getString(R.string.stats_orphan_event_preview, eventStats.orphanEventCount))
                if (eventStats.calendarTypeDistribution.size > 1) {
                    append(" | ${getString(R.string.stats_calendar_types, eventStats.calendarTypeDistribution.size)}")
                }
            }
        }

        viewModel.relationshipStats.observe(viewLifecycleOwner) { relStats ->
            binding.relPreview.text = buildString {
                append(getString(R.string.stats_rel_type_preview, relStats.typeDistribution.size))
                append(getString(R.string.stats_isolated_preview, relStats.isolatedCharacters.size))
                append(" | ${getString(R.string.stats_network_density, relStats.networkDensity * 100)}")
            }
        }

        viewModel.nameBankStats.observe(viewLifecycleOwner) { nameStats ->
            binding.namePreview.text = buildString {
                append(getString(R.string.stats_usage_rate_preview, nameStats.usageRate, nameStats.usedNames, nameStats.totalNames))
                // 성별 분포 인사이트
                val topGender = nameStats.genderDistribution.maxByOrNull { it.value }
                if (topGender != null) {
                    append(" | ${topGender.key} ${topGender.value}명")
                }
                // 평균 이름 길이
                if (nameStats.avgNameLength > 0) {
                    append(" | 평균 ${String.format("%.1f", nameStats.avgNameLength)}자")
                }
            }
        }

        viewModel.dataHealthStats.observe(viewLifecycleOwner) { healthStats ->
            val totalIssues = healthStats.noImageChars.size +
                healthStats.incompleteFieldChars.size +
                healthStats.isolatedChars.size +
                healthStats.unlinkedChars.size +
                healthStats.noMemoChars.size +
                healthStats.emptyDescRelationships
            binding.healthPreview.text = buildString {
                if (totalIssues == 0) append(getString(R.string.stats_health_no_issues))
                else {
                    append(getString(R.string.stats_health_issues_found, totalIssues))
                    if (healthStats.noImageChars.isNotEmpty()) append(getString(R.string.stats_health_no_image_preview, healthStats.noImageChars.size))
                    if (healthStats.lowPrecisionEvents > 0) append(" | ${getString(R.string.stats_low_precision_events, healthStats.lowPrecisionEvents)}")
                }
            }
        }

        // 필드 분석 프리뷰
        viewModel.fieldAnalysisStats.observe(viewLifecycleOwner) { fieldStats ->
            binding.fieldAnalysisPreview.text = buildString {
                append(getString(R.string.stats_field_analysis_preview,
                    fieldStats.fieldValueDistributions.size,
                    fieldStats.numberFieldSummaries.size))
            }
        }

        // 세력 통계
        viewModel.factionStats.observe(viewLifecycleOwner) { factionStats ->
            if (factionStats.totalFactions == 0) {
                binding.cardFactionStats.visibility = View.GONE
                return@observe
            }
            binding.cardFactionStats.visibility = View.VISIBLE
            binding.factionPreview.text = buildString {
                append("세력 ${factionStats.totalFactions}개")
                val totalMembers = factionStats.factionMemberCounts.values.sum()
                append(" | 소속 멤버 ${totalMembers}명")
                if (factionStats.multiMemberCharacters > 0) {
                    append(" | 다중 소속 ${factionStats.multiMemberCharacters}명")
                }
                if (factionStats.departureCount > 0) {
                    append(" | 탈퇴 ${factionStats.departureCount}건")
                }
                if (factionStats.factionlessCharacterCount > 0) {
                    append(" | 미소속 ${factionStats.factionlessCharacterCount}명")
                }
                if (factionStats.autoRelationshipCount > 0) {
                    append(" | 자동 관계 ${factionStats.autoRelationshipCount}건")
                }
            }
        }
    }

    // ===== 패턴 인사이트 (개선 3) =====

    private fun populatePatternInsights(patterns: List<PatternInsight>) {
        val container = binding.patternInsightContainer
        container.removeAllViews()

        if (patterns.isEmpty()) {
            binding.cardPatternInsights.visibility = View.GONE
            return
        }
        binding.cardPatternInsights.visibility = View.VISIBLE

        val ctx = context ?: return
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)

        patterns.forEach { pattern ->
            val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = marginSm }
                radius = resources.getDimension(R.dimen.stats_card_corner_radius)
                cardElevation = 2f
                // severity별 배경색 힌트
                val bgColor = when (pattern.severity) {
                    PatternSeverity.HIGH -> 0x18FF0000.toInt()   // 반투명 빨강
                    PatternSeverity.MEDIUM -> 0x18FF8800.toInt() // 반투명 주황
                    PatternSeverity.LOW -> 0x182196F3.toInt()    // 반투명 파랑
                }
                setCardBackgroundColor(ColorUtils.compositeColors(bgColor, ContextCompat.getColor(ctx, R.color.surface)))
            }

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, (8 * resources.displayMetrics.density).toInt())
            }

            // severity 배지 + 타이틀
            val severityLabel = when (pattern.severity) {
                PatternSeverity.HIGH -> getString(R.string.stats_pattern_severity_high)
                PatternSeverity.MEDIUM -> getString(R.string.stats_pattern_severity_medium)
                PatternSeverity.LOW -> getString(R.string.stats_pattern_severity_low)
            }

            val titleText = TextView(ctx).apply {
                text = "$severityLabel  ${pattern.title}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            content.addView(titleText)

            // 설명
            val descText = TextView(ctx).apply {
                text = pattern.description
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
                layoutParams = lp
            }
            content.addView(descText)

            // 제안 (있는 경우)
            if (pattern.suggestion.isNotBlank()) {
                val sugText = TextView(ctx).apply {
                    text = "💡 ${pattern.suggestion}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
                    layoutParams = lp
                }
                content.addView(sugText)
            }

            // fieldDefId가 있으면 탭 시 필드 인사이트로 이동
            if (pattern.fieldDefId != null) {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener {
                    findNavController().navigateSafe(
                        R.id.statsMainFragment, R.id.statsFieldInsightFragment, null
                    )
                }
            }

            card.addView(content)
            container.addView(card)
        }
    }

    // ===== 인사이트 미리보기 (가로 스크롤 미니 차트) =====

    private fun populateInsightPreview(insights: List<FieldInsightResult>) {
        val container = binding.insightPreviewContainer
        container.removeAllViews()

        if (insights.isEmpty()) {
            binding.cardInsightPreview.visibility = View.GONE
            return
        }
        binding.cardInsightPreview.visibility = View.VISIBLE

        val ctx = requireContext()
        val cardWidth = resources.getDimensionPixelSize(R.dimen.stats_chart_height) / 2  // 150dp
        val chartSize = cardWidth - resources.getDimensionPixelSize(R.dimen.stats_margin_md) * 2
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)

        // 동일 필드명이 여러 세계관에 존재하는지 확인 (세계관명 표시 여부 결정)
        val nameCountMap = insights.groupBy { it.fieldDefinition.name }.mapValues { it.value.size }

        insights.take(10).forEach { insight ->
            // 분포 데이터가 있는 첫 번째 분석만 미리보기
            val distResult = insight.analysisResults.firstOrNull { it.distributionData != null }
            val distData = distResult?.distributionData ?: return@forEach

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = marginSm
                layoutParams = lp
                setOnClickListener {
                    findNavController().navigateSafe(
                        R.id.statsMainFragment, R.id.statsFieldInsightFragment, null
                    )
                }
            }

            // 필드 이름 (동일 이름이 여러 세계관에 있으면 세계관명 표시)
            val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
            val captionSp = resources.getDimension(R.dimen.stats_text_chart_value_sm) / resources.displayMetrics.scaledDensity
            val needDisambiguation = (nameCountMap[insight.fieldDefinition.name] ?: 0) > 1
            val displayName = if (needDisambiguation && insight.universeName.isNotBlank()) {
                "${insight.fieldDefinition.name} (${insight.universeName})"
            } else {
                insight.fieldDefinition.name
            }
            card.addView(TextView(ctx).apply {
                text = displayName
                textSize = textSizeSp
                setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                maxLines = 1
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm / 2
                layoutParams = lp
            })

            // 미니 파이차트
            val miniChart = PieChart(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(chartSize, chartSize)
            }
            val entries = distData.entries.sortedByDescending { it.value }.take(5)
                .map { PieEntry(it.value.toFloat(), it.key) }
            val dataSet = PieDataSet(entries, "").apply {
                colors = chartColors()
                setDrawValues(false)
            }
            miniChart.apply {
                data = PieData(dataSet)
                description.isEnabled = false
                isDrawHoleEnabled = true
                holeRadius = 25f
                setHoleColor(ContextCompat.getColor(ctx, R.color.surface))
                setTransparentCircleColor(ContextCompat.getColor(ctx, R.color.surface))
                legend.isEnabled = false
                setDrawEntryLabels(false)
                setTouchEnabled(false)
                invalidate()
            }
            card.addView(miniChart)

            // TOP 1 값 표시
            val topEntry = distData.entries.maxByOrNull { it.value }
            if (topEntry != null) {
                val total = distData.values.sum().toFloat()
                val pct = if (total > 0) topEntry.value / total * 100 else 0f
                card.addView(TextView(ctx).apply {
                    text = "${topEntry.key} ${String.format("%.0f", pct)}%"
                    textSize = captionSp
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    maxLines = 1
                })
            }

            container.addView(card)
        }
    }

    private fun setupClickListeners() {
        // 신규 네비게이션 카드
        binding.cardFieldInsight.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsFieldInsightFragment, null
            )
        }
        binding.cardRelationNetwork.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsRelationshipDetailFragment, null
            )
        }
        binding.cardDataOverview.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsDataOverviewFragment, null
            )
        }
        binding.cardCrossNovel.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsCrossNovelFragment, null
            )
        }
        binding.btnInsightMore.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsFieldInsightFragment, null
            )
        }

        // 레거시 카드
        binding.cardCharacters.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsCharacterDetailFragment, null
            )
        }
        binding.cardEvents.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsEventDetailFragment, null
            )
        }
        binding.cardRelationships.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsRelationshipDetailFragment, null
            )
        }
        binding.cardFieldAnalysis.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsFieldAnalysisDetailFragment, null
            )
        }
        binding.cardNameBank.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsNameBankDetailFragment, null
            )
        }
        binding.cardDataHealth.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsDataHealthDetailFragment, null
            )
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
        rankingAdapter = null
        rankingInitialized = false
        _binding = null
    }
}
