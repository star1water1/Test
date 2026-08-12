package com.novelcharacter.app.ui.stats

import com.novelcharacter.app.ui.theme.ChartTheme
import android.graphics.Color
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsMainBinding
import com.novelcharacter.app.ui.adapter.RankingAdapter
import com.novelcharacter.app.util.ValueDistributions
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError

class StatsMainFragment : Fragment() {

    private var _binding: FragmentStatsMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    // 순위 탭
    private var rankingAdapter: RankingAdapter? = null
    private var rankingInitialized = false
    private var currentRankingSources: List<RankingSource> = emptyList()
    private var currentAscending = false
    private var selectedUniverseId: Long? = null
    private var selectedNovelIdForRanking: Long? = null
    private var selectedFieldIndex = -1
    private var selectedBodySizePartIndex = 0

    // 랭킹 탭 정렬·필터 상태 영속(stats_prefs) — 개요 탭 필터만 저장되고 랭킹은 전부 휘발되던 갭 해소.
    // 필드는 인덱스가 아니라 안정 키(fieldDef.key)로 저장·복원한다(필드 목록이 세계관별로 달라지므로).
    private val rankingPrefs by lazy {
        requireContext().getSharedPreferences("stats_prefs", android.content.Context.MODE_PRIVATE)
    }
    private data class RankingRestore(
        val universeId: Long?, val novelId: Long?, val fieldKey: String?, val bodyPart: Int
    )
    private var pendingRankingRestore: RankingRestore? = null
    // 필드 복원 후 BODY_SIZE 파트 스피너가 준비되면 소비되는 잔여 복원값
    private var pendingBodyPartRestore: Int? = null

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
        loadFieldLibraryPreview()
    }

    private fun loadFieldLibraryPreview() {
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as com.novelcharacter.app.NovelCharacterApp
            val counts = runCatching { app.fieldValueLibraryRepository.entryCounts() }.getOrNull() ?: return@launch
            if (_binding == null) return@launch
            val fieldCount = counts.size
            val valueCount = counts.values.sumOf { it.entryCount }
            val uncategorized = counts.values.sumOf { it.uncategorizedCount }
            binding.fieldLibraryPreview.text =
                getString(R.string.field_library_field_summary, valueCount, uncategorized,
                    counts.values.sumOf { it.unusedCount }) + " | 필드 ${fieldCount}개"
        }
    }

    private fun setupTabs() {
        // 개요/랭킹은 세그먼트 컨트롤로 전환 — 분석 호스트 TabLayout과의 탭 2줄 적층 방지.
        // 선택 상태는 뷰 상태 저장으로 회전·재생성 시 복원된다.
        binding.statsSegmentGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateTabVisibility()
            if (checkedId == binding.btnTabRanking.id && !rankingInitialized) {
                initRankingUI()
            }
        }

        // Fragment 재생성 시 선택이 랭킹일 수 있으므로 현재 상태 반영
        if (binding.statsSegmentGroup.checkedButtonId == binding.btnTabRanking.id) {
            updateTabVisibility()
            if (!rankingInitialized) initRankingUI()
        }
    }

    /** 현재 탭 선택 + 로딩 상태에 따라 visibility를 일원적으로 관리 */
    private fun updateTabVisibility() {
        val isLoading = viewModel.loading.value == true
        val isOverviewTab = binding.statsSegmentGroup.checkedButtonId != binding.btnTabRanking.id
        binding.contentLayout.visibility = if (!isLoading && isOverviewTab) View.VISIBLE else View.GONE
        binding.rankingLayout.visibility = if (!isLoading && !isOverviewTab) View.VISIBLE else View.GONE
    }

    private fun initRankingUI() {
        rankingInitialized = true
        val ctx = context ?: return

        // 저장된 랭킹 정렬·필터 복원 — 스피너 설정 전에 필드에 실어두고, 비동기 스피너 준비 시점에 반영
        currentAscending = rankingPrefs.getBoolean("ranking_ascending", false)
        pendingRankingRestore = RankingRestore(
            universeId = if (rankingPrefs.contains("ranking_universe_id")) rankingPrefs.getLong("ranking_universe_id", -1L) else null,
            novelId = if (rankingPrefs.contains("ranking_novel_id")) rankingPrefs.getLong("ranking_novel_id", -1L) else null,
            fieldKey = rankingPrefs.getString("ranking_field_key", null),
            bodyPart = rankingPrefs.getInt("ranking_body_part", 0)
        )
        binding.rankingSortToggle.setImageResource(
            if (currentAscending) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
        )

        // RecyclerView
        rankingAdapter = RankingAdapter()
        binding.rankingRecyclerView.layoutManager = LinearLayoutManager(ctx)
        binding.rankingRecyclerView.adapter = rankingAdapter

        // BODY_SIZE 파트 스피너 리스너를 한 번만 등록
        binding.rankingBodySizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, partPos: Int, partId: Long) {
                selectedBodySizePartIndex = partPos
                rankingPrefs.edit().putInt("ranking_body_part", partPos).apply()
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
                    rankingPrefs.edit().remove("ranking_field_key").apply()
                    binding.rankingBodySizeRow.visibility = View.GONE
                    binding.rankingEmpty.visibility = View.VISIBLE
                    binding.rankingRecyclerView.visibility = View.GONE
                    binding.rankingSummary.text = ""
                    return
                }
                selectedFieldIndex = pos - 1
                val source = currentRankingSources.getOrNull(selectedFieldIndex) ?: return
                rankingPrefs.edit().putString("ranking_field_key", source.storageKey).apply()

                val field = source.rankableField
                if (field == null) {  // 대결 축 — 파트 스피너가 없다
                    binding.rankingBodySizeRow.visibility = View.GONE
                    pendingBodyPartRestore = null
                    executeRanking()
                    return
                }

                // BODY_SIZE일 때만 파트 스피너 표시 (adapter만 교체, 리스너는 재등록하지 않음)
                if (field.fieldDef.type == "BODY_SIZE" && field.bodySizeParts != null) {
                    binding.rankingBodySizeRow.visibility = View.VISIBLE
                    val partAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, field.bodySizeParts)
                    partAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.rankingBodySizeSpinner.adapter = partAdapter
                    // 저장된 파트 복원 — 파트 스피너 준비 직후 1회 소비. 범위 밖이면 기본(0) 유지.
                    pendingBodyPartRestore?.let { savedPart ->
                        pendingBodyPartRestore = null
                        if (savedPart in field.bodySizeParts.indices) {
                            binding.rankingBodySizeSpinner.setSelection(savedPart)
                        }
                    }
                    // adapter 변경 시 onItemSelected(pos=0) 자동 발생 → 거기서 executeRanking() 호출됨
                    return
                } else {
                    binding.rankingBodySizeRow.visibility = View.GONE
                    pendingBodyPartRestore = null  // 비-BODY_SIZE 필드면 파트 복원 폐기
                }

                executeRanking()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 정렬 토글
        binding.rankingSortToggle.setOnClickListener {
            currentAscending = !currentAscending
            rankingPrefs.edit().putBoolean("ranking_ascending", currentAscending).apply()
            binding.rankingSortToggle.setImageResource(
                if (currentAscending) R.drawable.ic_arrow_up
                else R.drawable.ic_arrow_down
            )
            if (selectedFieldIndex >= 0) executeRanking()
        }

        // 순위 결과 옵저버
        setupRankingObservers()

        // 초기 로딩: cachedSnapshot이 있으면 즉시, 없으면 loading 완료 후 자동 세팅.
        // setupRankingUniverseSpinner가 저장된 세계관 선택을 복원하면 selectedUniverseId가 그 값이 되므로
        // 뒤이은 필드 로드도 복원된 스코프로 맞춘다(취소 가능 로드라 마지막 것이 확정).
        if (viewModel.getUniverseList().isNotEmpty()) {
            setupRankingUniverseSpinner()
            viewModel.loadRankingSources(selectedUniverseId)
        }
    }

    private var suppressFieldSpinnerCallback = false

    private fun setupRankingObservers() {
        viewModel.rankingSources.observe(viewLifecycleOwner) { fields ->
            currentRankingSources = fields
            // adapter 교체 시 자동 콜백 방지
            suppressFieldSpinnerCallback = true
            populateFieldSpinner(fields)
            _binding?.rankingFieldSpinner?.post {
                suppressFieldSpinnerCallback = false
                // 저장된 필드 복원 — 복원된 세계관 스코프의 필드 목록이 도착했을 때만 시도.
                // (loadRankingSources는 취소 가능하므로 마지막 로드 = 복원 세계관 스코프)
                val restore = pendingRankingRestore
                if (restore != null && selectedUniverseId == restore.universeId) {
                    if (restore.fieldKey != null) {
                        val idx = currentRankingSources.indexOfFirst { it.matches(restore.fieldKey) }
                        if (idx >= 0) {
                            pendingBodyPartRestore = restore.bodyPart  // BODY_SIZE 파트 스피너 준비 후 소비
                            binding.rankingFieldSpinner.setSelection(idx + 1)
                        }
                        // 저장된 필드가 현재 목록에 없으면 조용히 미선택 유지
                    }
                    pendingRankingRestore = null  // 복원 1회로 종료 — 이후 데이터 변경 재발화 시 재복원 방지
                }
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
            // 점수 분포 (B-117) — 나눌 폭이 없으면(전원 동점·둘 미만) 빈 목록이 오고, 그때는
            // 줄을 통째로 감춘다. **빈 줄을 남겨 두면 "0명"처럼 읽힌다.**
            if (result.scoreDistribution.isEmpty()) {
                b.rankingDistribution.visibility = View.GONE
            } else {
                b.rankingDistribution.visibility = View.VISIBLE
                b.rankingDistribution.text = getString(
                    R.string.stats_ranking_distribution,
                    result.scoreDistribution.joinToString(" · ") { (label, count) ->
                        getString(R.string.stats_ranking_distribution_bin, label, count)
                    }
                )
            }
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
                rankingPrefs.edit().apply {
                    if (selectedUniverseId != null) putLong("ranking_universe_id", selectedUniverseId!!) else remove("ranking_universe_id")
                }.apply()
                viewModel.loadRankingSources(selectedUniverseId)
                selectedFieldIndex = -1
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.rankingUniverseSpinner.adapter = adapter

        // 저장된 세계관 필터 복원 — setSelection이 위 리스너를 재발화해 올바른 필드 목록을 로드한다.
        // 삭제된 세계관이면 위치를 못 찾아 전체(0) 유지.
        pendingRankingRestore?.universeId?.let { savedUid ->
            val idx = universes.indexOfFirst { it.first == savedUid }
            if (idx >= 0) binding.rankingUniverseSpinner.setSelection(idx + 1)
        }
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
                rankingPrefs.edit().apply {
                    if (selectedNovelIdForRanking != null) putLong("ranking_novel_id", selectedNovelIdForRanking!!) else remove("ranking_novel_id")
                }.apply()
                if (selectedFieldIndex >= 0) executeRanking()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.rankingNovelSpinner.adapter = adapter

        // 저장된 작품 필터 복원 (삭제된 작품이면 전체 유지)
        pendingRankingRestore?.novelId?.let { savedNid ->
            val idx = novels.indexOfFirst { it.first == savedNid }
            if (idx >= 0) binding.rankingNovelSpinner.setSelection(idx + 1)
        }
    }

    private fun populateFieldSpinner(sources: List<RankingSource>) {
        val ctx = context ?: return
        val items = mutableListOf(getString(R.string.stats_ranking_select_field))
        // 종류 표시는 계산과 **같은 표**를 본다 — 화면에 리터럴을 두면 타입이 늘 때 여기만 뒤처진다.
        items.addAll(sources.map { "${it.label} (${it.typeLabel})" })

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.rankingFieldSpinner.adapter = adapter
    }

    private fun executeRanking() {
        val source = currentRankingSources.getOrNull(selectedFieldIndex) ?: return
        if (source.isDuel) {
            viewModel.loadDuelRanking(
                axisCode = source.duelAxisCode ?: return,
                ascending = currentAscending,
                novelId = selectedNovelIdForRanking
            )
            return
        }
        val field = source.rankableField ?: return
        val bodyPartIdx = if (field.fieldDef.type == "BODY_SIZE") selectedBodySizePartIndex else null
        viewModel.loadRanking(
            fieldDefIds = field.mergedFieldDefIds,
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
                    viewModel.loadRankingSources(selectedUniverseId)
                }
                // onResume 후 데이터 갱신: 스피너 선택은 보존하고 필드 목록만 재로딩
                else if (rankingNeedsRefresh) {
                    rankingNeedsRefresh = false
                    viewModel.loadRankingSources(selectedUniverseId)
                    if (selectedFieldIndex >= 0) executeRanking()
                }
            }
        }

        // 사유가 있으면 사유를, 없으면 통짜 문구를 — 어느 쪽이든 반드시 띄운다(B-32).
        viewModel.error.observe(viewLifecycleOwner) { error -> showStatsError(viewModel, error) }

        // 작품 필터 스피너 — [전체, 작품 미배정, …작품들] (미배정은 sentinel, 위치 시프트 +2)
        viewModel.novelList.observe(viewLifecycleOwner) { novels ->
            val ctx = context ?: return@observe
            val noneId = com.novelcharacter.app.util.UnassignedFilter.NO_NOVEL_ID
            val items = mutableListOf(
                getString(R.string.stats_filter_all),
                getString(R.string.stats_no_novel_assigned)
            )
            items.addAll(novels.map { it.second })
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            // 리스너를 adapter/setSelection 전에 설정하여 콜백 누락 방지
            binding.spinnerNovelFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val novelId = when (pos) {
                        0 -> null
                        1 -> noneId
                        else -> novels[pos - 2].first
                    }
                    if (viewModel.selectedNovelId.value != novelId) {
                        viewModel.setNovelFilter(novelId)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            binding.spinnerNovelFilter.adapter = adapter

            // ViewModel 상태에서 스피너 위치 복원 (Fragment 재생성 시)
            val currentNovelId = viewModel.selectedNovelId.value
            val restoredPos = when (currentNovelId) {
                null -> 0
                noneId -> 1
                else -> novels.indexOfFirst { it.first == currentNovelId }.let { if (it >= 0) it + 2 else 0 }
            }
            binding.spinnerNovelFilter.setSelection(restoredPos, false)
        }

        // 계산 필드 산출 불가 고지 (B-30) — 값을 지어내지 않는 대신 **왜 없는지**를 말한다.
        // 0이면 감춘다: 이 스코프에 계산 필드가 애초에 없으면 할 말이 없다.
        viewModel.calculatedUnavailable.observe(viewLifecycleOwner) { count ->
            binding.calculatedUnavailableNotice.apply {
                if (count > 0) {
                    text = getString(R.string.stats_calculated_unavailable, count)
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
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

        // 패턴 인사이트 설정 버튼
        binding.patternSettingsButton.setOnClickListener {
            showPatternSettingsDialog()
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

    /**
     * 패턴 유형 설정 — 유형 on/off와 **유형별 민감도**를 한 창에서 다룬다 (B-70, 확정 11번).
     *
     * 한 창인 이유: 둘은 한 질문의 두 부분이다 — *이 유형을 볼 것인가*와 *무엇부터를 그 유형이라
     * 부를 것인가*. 민감도만 다른 화면에 두면 "편중이 너무 많이 뜬다"는 사용자가 끄는 쪽밖에
     * 못 찾는다(원칙 04 — 접근은 최대한 짧게).
     *
     * 종전의 `setMultiChoiceItems`는 목록 항목에 입력칸을 넣을 수 없어 본문을 직접 짠다.
     * 높이는 [cappedScrollView]가 묶는다(R-31) — 유형이 늘면 창이 화면을 넘는다.
     */
    private fun showPatternSettingsDialog() {
        val ctx = context ?: return
        val allTypes = PatternType.values()
        // 저장·해석은 단일 소스([PatternTypePrefs])를 탄다 — 다이얼로그가 자기 파싱을 갖고 있으면
        // 기본값 규칙이 갈린다.
        val enabledSet = PatternTypePrefs.enabled(ctx).toMutableSet()
        val current = viewModel.patternThresholds()

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val gap = (8 * density).toInt()

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, gap, pad, gap)
        }

        // 목적문 — 이 창이 무엇을 어디에 어떻게 하는가 한 줄(텍스트 가이드 · 체크리스트 7번).
        body.addView(TextView(ctx).apply {
            setText(R.string.stats_pattern_settings_purpose)
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        })

        // 유형 하나당 한 줄: [체크박스 유형명] [기준 입력칸] [단위]
        val inputs = HashMap<PatternType, android.widget.EditText>()
        for (type in allTypes) {
            val spec = PatternSensitivitySpec.of(type)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
            }
            row.addView(android.widget.CheckBox(ctx).apply {
                text = type.label
                isChecked = type in enabledSet
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) enabledSet.add(type) else enabledSet.remove(type)
                }
            })
            // **끈 유형의 칸도 편집할 수 있게 둔다.** 비활성으로 잠그면 잘못 적은 값을 남긴 채
            // 유형을 껐을 때 그 칸을 고칠 길이 없어져 저장이 막힌다 — 되돌릴 방법이 창을
            // 취소하는 것뿐인 막다른 골목이 된다. 켜고 끄는 것은 *감지 여부*이고 기준은 그대로 남는다.
            val input = android.widget.EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    if (spec.allowsDecimal) android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                setText(spec.format(current))
                minEms = 4
                gravity = android.view.Gravity.END
                contentDescription = getString(R.string.stats_pattern_sensitivity_desc, type.label)
            }
            inputs[type] = input
            row.addView(input)
            row.addView(TextView(ctx).apply {
                text = spec.unit
                textSize = 13f
                setPadding(gap / 2, 0, 0, 0)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            })
            body.addView(row)
            body.addView(TextView(ctx).apply {
                text = getString(spec.hintRes)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            })
        }

        val scroll = cappedScrollView(ctx).apply { addView(body) }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.stats_pattern_settings))
            .setView(scroll)
            // 리스너를 null로 두고 아래에서 직접 검증한다 — 트레일링 람다는 무조건 닫히므로
            // 잘못 적은 칸 하나에 나머지 입력이 통째로 사라진다(R-27).
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.stats_pattern_sensitivity_reset, null)
            .create()

        // 중립 버튼 배선을 이 함수에 맡긴다 — 밖에서 `setOnShowListener`를 한 번 더 달면
        // 양성 버튼 검증을 덮어 버린다(그쪽 주석 참조).
        dialog.setValidatedPositiveButton(onShow = { shown ->
            // 기본값 복원은 창을 닫지 않고 **칸만 되돌린다** — 결과를 보고 확인·취소를 고를 수
            // 있어야 한다(기본 리스너를 달면 무조건 닫힌다).
            shown.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                for (type in allTypes) {
                    inputs[type]?.setText(PatternSensitivitySpec.of(type).format(PatternThresholds.DEFAULT))
                }
            }
        }) {
            var parsed = current
            var firstBad: android.widget.EditText? = null
            for (type in allTypes) {
                val field = inputs[type] ?: continue
                val spec = PatternSensitivitySpec.of(type)
                val next = spec.parse(parsed, field.text.toString().trim())
                if (next == null) {
                    // 실패는 고칠 칸에 붙인다 — 창은 열린 채로 남아 나머지 입력이 살아 있다.
                    field.showInlineError(getString(R.string.stats_pattern_sensitivity_invalid, spec.min, spec.max))
                    if (firstBad == null) firstBad = field
                } else {
                    parsed = next
                }
            }
            if (firstBad != null) {
                firstBad.requestFocus()
                false
            } else {
                viewModel.saveEnabledPatternTypes(enabledSet)
                viewModel.savePatternThresholds(parsed)
                viewModel.loadPatternInsights()
                true
            }
        }
        dialog.show()
    }

    private fun populatePatternInsights(patterns: List<PatternInsight>) {
        val container = binding.patternInsightContainer
        container.removeAllViews()

        // 카드를 통째로 숨기면 **그 안에 있는 설정 버튼까지** 사라진다. 설정에서 모든 유형을
        // 해제한 사용자는 카드가 사라지는 동시에 그것을 되돌릴 유일한 경로를 잃었다 —
        // 안내도 없는 일방통행 함정이었다(B-31). 비어 있어도 카드는 남기고 **사유**를 적는다(R-17).
        binding.cardPatternInsights.visibility = View.VISIBLE

        val ctx = context ?: return

        if (patterns.isEmpty()) {
            container.addView(TextView(ctx).apply {
                // 세 상태를 구분한다: 전부 꺼짐 / 일부 꺼짐 + 감지 없음 / 전부 켜짐 + 감지 없음.
                // 일부만 끈 사용자에게 "데이터가 쌓이면 알려 드립니다"라고만 하면, 방금 자기가
                // 끈 유형을 영원히 기다리게 된다.
                val enabled = viewModel.enabledPatternTypes()
                val offCount = PatternType.values().size - enabled.size
                text = when {
                    enabled.isEmpty() -> getString(R.string.stats_pattern_all_types_off)
                    offCount > 0 -> getString(R.string.stats_pattern_none_detected_some_off, offCount)
                    else -> getString(R.string.stats_pattern_none_detected)
                }
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            })
            return
        }
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
                        R.id.analysisFragment, R.id.statsFieldInsightFragment, null
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
                        R.id.analysisFragment, R.id.statsFieldInsightFragment, null
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
            // 미리보기도 '기타' 조각을 포함한다 — 조각 비율과 아래 TOP1 백분율의 분모가
            // 어긋나면 그림과 숫자가 다른 말을 한다(R-14).
            val previewView = ValueDistributions.view(distData, PREVIEW_SLICE_LIMIT)
            val entries = previewView.shown.map { PieEntry(it.count.toFloat(), it.label) } +
                if (previewView.hasHidden) {
                    listOf(PieEntry(
                        previewView.hiddenCount.toFloat(),
                        getString(R.string.stats_distribution_others, previewView.hiddenKinds)
                    ))
                } else emptyList()
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
                R.id.analysisFragment, R.id.statsFieldInsightFragment, null
            )
        }
        binding.cardRelationNetwork.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsRelationshipDetailFragment, null
            )
        }
        binding.cardDataOverview.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsDataOverviewFragment, null
            )
        }
        binding.cardCrossNovel.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsCrossNovelFragment, null
            )
        }
        binding.btnInsightMore.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsFieldInsightFragment, null
            )
        }

        // 레거시 카드
        binding.cardCharacters.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsCharacterDetailFragment, null
            )
        }
        binding.cardEvents.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsEventDetailFragment, null
            )
        }
        binding.cardFieldAnalysis.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsFieldAnalysisDetailFragment, null
            )
        }
        binding.cardNameBank.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsNameBankDetailFragment, null
            )
        }
        binding.cardFieldLibrary.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.fieldLibraryHomeFragment, null
            )
        }
        binding.cardDataHealth.setOnClickListener {
            findNavController().navigateSafe(
                R.id.analysisFragment, R.id.statsDataHealthDetailFragment, null
            )
        }
    }

    private fun chartColors(): List<Int> =
        ChartTheme.palette(requireContext())

    override fun onDestroyView() {
        super.onDestroyView()
        rankingAdapter = null
        rankingInitialized = false
        _binding = null
    }

    companion object {
        /** 미리보기 카드의 미니 차트가 그리는 조각 수 상한 — 문구·조각이 같은 상수를 본다(R-14). */
        private const val PREVIEW_SLICE_LIMIT = 5
    }

}
