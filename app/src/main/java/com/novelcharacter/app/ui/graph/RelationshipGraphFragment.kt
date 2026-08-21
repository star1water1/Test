package com.novelcharacter.app.ui.graph

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentRelationshipGraphBinding
import com.novelcharacter.app.util.DisplayCap
import com.novelcharacter.app.util.navigateSafe
import android.graphics.Color
import kotlinx.coroutines.launch

class RelationshipGraphViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val app = application as NovelCharacterApp
    private val characterRepository = app.characterRepository
    private val universeRepository = app.universeRepository
    private val novelRepository = app.novelRepository
    private val factionRepository = app.factionRepository

    private val _characters = MutableLiveData<List<Character>>()
    val characters: LiveData<List<Character>> = _characters

    private val _relationships = MutableLiveData<List<CharacterRelationship>>()
    val relationships: LiveData<List<CharacterRelationship>> = _relationships

    private val _relationshipChanges = MutableLiveData<List<CharacterRelationshipChange>>()
    val relationshipChanges: LiveData<List<CharacterRelationshipChange>> = _relationshipChanges

    private val _relationshipColorMap = MutableLiveData<Map<String, String>>()
    val relationshipColorMap: LiveData<Map<String, String>> = _relationshipColorMap

    private val _universes = MutableLiveData<List<Universe>>()
    val universes: LiveData<List<Universe>> = _universes

    private val _novels = MutableLiveData<List<Novel>>()
    val novels: LiveData<List<Novel>> = _novels

    private val _factions = MutableLiveData<List<Faction>>()
    val factions: LiveData<List<Faction>> = _factions

    private val _factionMemberships = MutableLiveData<List<FactionMembership>>()
    val factionMemberships: LiveData<List<FactionMembership>> = _factionMemberships

    // 세력 간 관계 (B-3) — 세력 영역 중심점 사이 엣지로 표시
    private val _factionRelationships = MutableLiveData<List<com.novelcharacter.app.data.model.FactionRelationship>>()
    val factionRelationships: LiveData<List<com.novelcharacter.app.data.model.FactionRelationship>> = _factionRelationships

    /**
     * characterId -> List<Pair<factionId, factionColor(parsed Int)>>
     */
    private val _characterFactionMap = MutableLiveData<Map<Long, List<Pair<Long, Int>>>>()
    val characterFactionMap: LiveData<Map<Long, List<Pair<Long, Int>>>> = _characterFactionMap

    // 시간뷰의 생사 판정용 상태변화 전체 (birth/death/alive 키만 사용)
    private val _stateChanges = MutableLiveData<List<com.novelcharacter.app.data.model.CharacterStateChange>>()
    val stateChanges: LiveData<List<com.novelcharacter.app.data.model.CharacterStateChange>> = _stateChanges

    fun loadData() {
        viewModelScope.launch {
            _characters.value = characterRepository.getAllCharactersList()
            _relationships.value = characterRepository.getAllRelationships()
            _relationshipChanges.value = characterRepository.getAllRelationshipChanges()
            _stateChanges.value = characterRepository.getAllStateChangesList()

            val universeList = universeRepository.getAllUniversesList()
            _universes.value = universeList
            _novels.value = novelRepository.getAllNovelsList()

            val mergedColors = mutableMapOf<String, String>()
            for (universe in universeList) {
                mergedColors.putAll(universe.getRelationshipColorMap())
            }
            _relationshipColorMap.value = mergedColors

            // Load faction data
            val allFactions = factionRepository.getAllFactionsList()
            _factions.value = allFactions

            val allMemberships = factionRepository.getAllMembershipsList()
            _factionMemberships.value = allMemberships

            _factionRelationships.value = factionRepository.getAllFactionRelationshipsList()

            buildCharacterFactionMap(allFactions, allMemberships, year = null)
        }
    }

    /**
     * Build characterId -> List<Pair<factionId, colorInt>> mapping.
     * If year is non-null, only include memberships active at that year.
     */
    fun buildCharacterFactionMap(
        allFactions: List<Faction>,
        allMemberships: List<FactionMembership>,
        year: Int?
    ) {
        val factionMap = allFactions.associateBy { it.id }
        val result = mutableMapOf<Long, MutableList<Pair<Long, Int>>>()

        for (membership in allMemberships) {
            // If year filter is specified, only include active memberships
            if (year != null && !membership.isActiveAtYear(year)) continue

            val faction = factionMap[membership.factionId] ?: continue
            val colorInt = try {
                Color.parseColor(faction.color)
            } catch (e: Exception) {
                Color.parseColor("#2196F3")
            }
            result.getOrPut(membership.characterId) { mutableListOf() }
                .add(membership.factionId to colorInt)
        }
        _characterFactionMap.value = result
    }

    /**
     * Update faction memberships for a given year (time slider).
     */
    fun updateFactionMembershipsForYear(year: Int?) {
        val allFactions = _factions.value ?: return
        val allMemberships = _factionMemberships.value ?: return
        buildCharacterFactionMap(allFactions, allMemberships, year)
    }

    /**
     * 해당 연도에 사망 상태인 캐릭터 ID 집합.
     * TimeStateResolver와 동일한 우선순위: 명시적 __alive 상태변화(연도 이하 최신)가 있으면 그 값을,
     * 없으면 __death 연도(newValue 우선, 없으면 기록 연도) 기준으로 판정한다.
     */
    fun deceasedCharacterIdsAtYear(year: Int): Set<Long> {
        val changes = _stateChanges.value ?: return emptySet()
        val deceased = mutableSetOf<Long>()
        for ((charId, charChanges) in changes.groupBy { it.characterId }) {
            val aliveChange = charChanges
                .filter { it.fieldKey == com.novelcharacter.app.data.model.CharacterStateChange.KEY_ALIVE && it.year <= year }
                .maxWithOrNull(compareBy({ it.year }, { it.month ?: 0 }, { it.day ?: 0 }, { it.id }))
            if (aliveChange != null) {
                if (aliveChange.newValue.equals("false", ignoreCase = true)) deceased.add(charId)
                continue
            }
            val deathChange = charChanges.firstOrNull {
                it.fieldKey == com.novelcharacter.app.data.model.CharacterStateChange.KEY_DEATH
            } ?: continue
            val deathYear = deathChange.newValue.toIntOrNull() ?: deathChange.year
            if (year >= deathYear) deceased.add(charId)
        }
        return deceased
    }

    /**
     * 세력 자동 관계 엣지가 해당 연도에 유효한지 — 두 캐릭터 모두 그 세력에 재적 중이어야 한다.
     * (세력 링은 연도에 반응하는데 세력 엣지는 남던 자기모순 해소; 일반 관계는 형성 시점 정보가 없어 항상 유효로 둠)
     */
    fun isFactionEdgeActiveAtYear(rel: CharacterRelationship, year: Int): Boolean {
        val factionId = rel.factionId ?: return true
        val memberships = _factionMemberships.value ?: return true
        val active1 = memberships.any {
            it.factionId == factionId && it.characterId == rel.characterId1 && it.isActiveAtYear(year)
        }
        val active2 = memberships.any {
            it.factionId == factionId && it.characterId == rel.characterId2 && it.isActiveAtYear(year)
        }
        return active1 && active2
    }

    /**
     * Get factions for a specific universe.
     */
    fun getFactionsForUniverse(universeId: Long?): List<Faction> {
        val allFactions = _factions.value ?: emptyList()
        return if (universeId != null) {
            allFactions.filter { it.universeId == universeId }
        } else {
            allFactions
        }
    }

    fun resolveRelationshipAtYear(
        relationship: CharacterRelationship,
        year: Int,
        allChanges: List<CharacterRelationshipChange>
    ): ResolvedRelationship {
        val changes = allChanges
            .filter { it.relationshipId == relationship.id && it.year <= year }
            .sortedWith(compareByDescending<CharacterRelationshipChange> {
                it.year * 10000 + (it.month ?: 0) * 100 + (it.day ?: 0)
            }.thenByDescending { it.id })

        val latestChange = changes.firstOrNull()
        return ResolvedRelationship(
            relationship = relationship,
            resolvedType = latestChange?.relationshipType ?: relationship.relationshipType,
            resolvedIntensity = latestChange?.intensity ?: relationship.intensity,
            resolvedBidirectional = latestChange?.isBidirectional ?: relationship.isBidirectional
        )
    }
}

data class ResolvedRelationship(
    val relationship: CharacterRelationship,
    val resolvedType: String,
    val resolvedIntensity: Int,
    val resolvedBidirectional: Boolean
)

class RelationshipGraphFragment : Fragment() {

    private var _binding: FragmentRelationshipGraphBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RelationshipGraphViewModel by viewModels()
    private val prefs by lazy {
        requireContext().getSharedPreferences("graph_ui_state", android.content.Context.MODE_PRIVATE)
    }
    private var selectedRelTypes = mutableSetOf<String>()  // 빈 셋 = 전체
    private var selectedFactions = mutableSetOf<Long>()   // 빈 셋 = 전체 세력

    /**
     * 세력 칩이 **좁힘 축인가**(`true`) **강조 축인가**(`false`).
     *
     * ## 왜 축을 두 개 두는가 (사용자 판정 2026.08.21 — *"설계 원칙과 최고 퍼포먼스를 기준으로"*)
     *
     * 종전에는 세력이 **강조 전용**이었다 — 칩을 눌러도 노드가 한 명도 줄지 않고 나머지가
     * 흐려질 뿐이다. 그 자체는 쓸모가 있다(**누가 어느 세력인지 한 화면에서 견준다**).
     * 그러나 그것 **하나뿐인 것**은 두 가지를 못 준다.
     *
     * ⓐ **접힘을 못 푼다.** 배치 비용이 노드 수의 제곱이라 이 화면의 유일한 규모 방어선은
     *    [DisplayCap.GRAPH_NODE_LIMIT]인데, 강조는 상한에 걸리는 인물 수를 **한 명도**
     *    줄이지 않는다. 그래서 *"한 세력만 보고 싶다"*는 사용자가 접힘 고지를 본 채
     *    **정작 그 세력의 인물이 접혀 안 보이는** 자리가 났다. 접힘 고지가 *"작품·관계 유형
     *    필터로 좁히면 접힌 인물도 볼 수 있습니다"*라고 안내하는데, 세력으로 좁히는 길은
     *    없었다.
     * ⓑ **성능을 못 준다.** 좁히면 O(n²) 배치가 실제로 싸진다 — 강조는 전량을 그대로 그린다.
     *
     * **그렇다고 강조를 좁힘으로 바꾸지는 않는다**(개발 의도 3 — 기능의 쓸모는 사용자가
     * 가린다). 둘 다 열고 **기본은 종전 그대로**이며, 고른 것은 [prefs]에 남는다.
     */
    private var factionNarrows = false
    private var isTimeViewEnabled = false
    private var currentYear: Int? = null

    /**
     * 지금 그려져 있는 노드([DisplayCap.rankedCap]이 남긴 인물). `null`이면 상한에 걸리지 않아
     * **상한 때문에** 거를 것이 없다는 뜻이다.
     *
     * ⚠️ **`null`이 '아무도 안 줄었다'는 뜻은 아니다**(2026.08.21 좁힘 축 신설). 세력 좁힘은
     * 상한과 무관하게 인물을 줄이므로, 빠른 경로도 [applyFactionNarrow]를 **따로** 지나야
     * 좁혀서 없앤 인물로 뻗는 엣지가 되살아나지 않는다.
     *
     * **두 자리가 갈리는 것을 막으려고 둔다** — 시점 슬라이더는 노드를 다시 세우지 않고
     * 엣지만 갈아 끼우는데(`refreshGraphEdgesOnly`), 그 자리가 상한을 모르면 **접힌 인물로
     * 뻗는 선을 다시 만든다.** 그리기에서는 끝이 없는 엣지를 건너뛰므로 화면이 깨지지는 않지만,
     * 슬라이더는 *빠른 반응*이 목적인 경로라 만들자마자 버릴 엣지를 관계 수만큼 짓는 것이
     * 정확히 그 목적에 어긋난다(같은 파일의 `isEdgeSecondary`가 같은 이유로 공용 정의다).
     */
    private var shownNodeIds: Set<Long>? = null

    private var currentUniverseId: Long? = null
    private var currentNovelId: Long? = null
    private var primaryCharacterIds: Set<Long> = emptySet()

    // 세계관/작품 목록 캐시
    private var cachedUniverses: List<Universe> = emptyList()
    private var cachedNovels: List<Novel> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRelationshipGraphBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // SharedPreferences에서 상태 복원
        restoreState()

        binding.graphView.setOnNodeClickListener { characterId ->
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigateSafe(R.id.analysisFragment, R.id.characterDetailFragment, bundle)
        }

        binding.graphView.setOnNodeLongClickListener { characterId ->
            showNodeContextMenu(characterId)
        }

        setupTimeSlider()
        setupFactionDisplayModeToggle()
        observeColors()
        observeUniversesAndNovels()
        observeFactionData()
        observeData()
        viewModel.loadData()
    }

    private fun restoreState() {
        binding.graphView.showFactionArea = prefs.getBoolean("show_faction_area", true)
        binding.graphView.showFactionBorder = prefs.getBoolean("show_faction_border", true)
        binding.graphView.showFactionEdges = prefs.getBoolean("show_faction_edges", true)
        isTimeViewEnabled = prefs.getBoolean("time_view_enabled", false)
        currentUniverseId = if (prefs.contains("universe_id")) prefs.getLong("universe_id", -1L) else null
        currentNovelId = if (prefs.contains("novel_id")) prefs.getLong("novel_id", -1L) else null
        // 관계 유형·세력 필터 복원 — 세계관/작품 필터와 동일하게 재방문 시 유지
        selectedRelTypes = loadStringSet("rel_types_json").toMutableSet()
        selectedFactions = loadLongSet("faction_ids_json").toMutableSet()
        factionNarrows = prefs.getBoolean(PREF_FACTION_NARROWS, false)
    }

    private val filterGson by lazy { com.google.gson.Gson() }
    private val stringListType by lazy { object : com.google.gson.reflect.TypeToken<List<String?>>() {}.type }
    private val longListType by lazy { object : com.google.gson.reflect.TypeToken<List<Long?>>() {}.type }

    private fun loadStringSet(key: String): Set<String> = runCatching {
        filterGson.fromJson<List<String?>>(prefs.getString(key, null) ?: "[]", stringListType)
    }.getOrNull()?.filterNotNull()?.toSet() ?: emptySet()

    private fun loadLongSet(key: String): Set<Long> = runCatching {
        filterGson.fromJson<List<Long?>>(prefs.getString(key, null) ?: "[]", longListType)
    }.getOrNull()?.filterNotNull()?.toSet() ?: emptySet()

    private fun persistRelTypes() {
        prefs.edit().putString("rel_types_json", filterGson.toJson(selectedRelTypes.toList())).apply()
    }

    private fun persistFactions() {
        prefs.edit().putString("faction_ids_json", filterGson.toJson(selectedFactions.toList())).apply()
    }

    private fun observeUniversesAndNovels() {
        viewModel.universes.observe(viewLifecycleOwner) { universes ->
            cachedUniverses = universes
            setupUniverseSpinner(universes)
        }
        viewModel.novels.observe(viewLifecycleOwner) { novels ->
            cachedNovels = novels
            updateNovelSpinner()
        }
    }

    private fun observeFactionData() {
        viewModel.factions.observe(viewLifecycleOwner) {
            setupFactionChips()
        }
        viewModel.characterFactionMap.observe(viewLifecycleOwner) {
            refreshGraph()
        }
        // 세력 간 관계 엣지 (B-3)
        viewModel.factionRelationships.observe(viewLifecycleOwner) { relationships ->
            binding.graphView.setFactionRelationEdges(
                relationships.map { rel ->
                    FactionRelationEdge(
                        factionId1 = rel.factionId1,
                        factionId2 = rel.factionId2,
                        label = rel.relationType,
                        intensity = rel.intensity,
                        isBidirectional = rel.isBidirectional
                    )
                }
            )
        }
    }

    private fun setupFactionDisplayModeToggle() {
        val chipGroup = binding.factionDisplayModeChipGroup
        chipGroup.removeAllViews()

        data class ToggleOption(
            val labelRes: Int,
            val prefsKey: String,
            val getter: () -> Boolean,
            val setter: (Boolean) -> Unit,
            /** 켜고 끌 때 무엇이 달라지는지 말한다 — 없으면 조용히 바뀐다. */
            val onRes: Int? = null,
            val offRes: Int? = null,
        )
        val options = listOf(
            ToggleOption(R.string.faction_display_mode_area, "show_faction_area",
                { binding.graphView.showFactionArea }, { binding.graphView.showFactionArea = it }),
            ToggleOption(R.string.faction_display_mode_border, "show_faction_border",
                { binding.graphView.showFactionBorder }, { binding.graphView.showFactionBorder = it }),
            ToggleOption(R.string.faction_display_mode_edges, "show_faction_edges",
                { binding.graphView.showFactionEdges }, { binding.graphView.showFactionEdges = it }),
            // **세력 칩의 축을 고르는 자리**(강조 ↔ 좁힘). 앞의 셋과 성질이 다르지만 —
            // 저것들은 *그리는 법*이고 이것은 *고르는 법*이다 — 사용자가 세력 칩을 누르는
            // 바로 그 줄에 있어야 손이 짧다(원칙 04). 무엇이 달라지는지는 토스트가 말한다.
            ToggleOption(
                R.string.faction_display_mode_narrow, PREF_FACTION_NARROWS,
                { factionNarrows }, { factionNarrows = it },
                onRes = R.string.graph_faction_narrow_on,
                offRes = R.string.graph_faction_narrow_off,
            )
        )

        for (option in options) {
            val chip = Chip(requireContext()).apply {
                text = getString(option.labelRes)
                isCheckable = true
                isChecked = option.getter()
                setOnClickListener {
                    val newValue = !option.getter()
                    option.setter(newValue)
                    isChecked = newValue
                    prefs.edit().putBoolean(option.prefsKey, newValue).apply()
                    val noticeRes = if (newValue) option.onRes else option.offRes
                    if (noticeRes != null) {
                        Toast.makeText(requireContext(), getString(noticeRes), Toast.LENGTH_SHORT).show()
                    }
                    refreshGraph()
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun setupFactionChips() {
        val factions = viewModel.getFactionsForUniverse(currentUniverseId)
        val chipGroup = binding.factionChipGroup
        chipGroup.removeAllViews()

        if (factions.isEmpty()) {
            binding.factionFilterScrollView.visibility = View.GONE
            binding.factionDisplayModeScrollView.visibility = View.GONE
            return
        }

        binding.factionFilterScrollView.visibility = View.VISIBLE
        binding.factionDisplayModeScrollView.visibility = View.VISIBLE

        // 무효 선택 제거 (세계관 전환 등으로 세력 목록이 변경된 경우) — 정리 결과도 영속.
        // "미소속" sentinel은 실제 세력이 아니므로 정리 대상에서 보존한다.
        val validFactionIds = factions.mapTo(HashSet()) { it.id }
            .plus(com.novelcharacter.app.util.UnassignedFilter.NO_FACTION_ID)
        if (selectedFactions.retainAll(validFactionIds)) persistFactions()

        // "All factions" chip
        val allChip = Chip(requireContext()).apply {
            text = getString(R.string.graph_faction_filter_all)
            isCheckable = true
            isChecked = selectedFactions.isEmpty()
        }
        chipGroup.addView(allChip)

        // One chip per faction
        val factionChips = mutableListOf<Chip>()

        // "미소속" 칩 — 어떤 세력에도 속하지 않은 캐릭터 선택 (세력 색 미적용, 기본 칩 스타일)
        val noneId = com.novelcharacter.app.util.UnassignedFilter.NO_FACTION_ID
        val noneChip = Chip(requireContext()).apply {
            text = getString(R.string.graph_faction_filter_none)
            isCheckable = true
            isChecked = noneId in selectedFactions
            setOnClickListener {
                if (noneId in selectedFactions) {
                    selectedFactions.remove(noneId)
                } else {
                    selectedFactions.add(noneId)
                }
                isChecked = noneId in selectedFactions
                allChip.isChecked = selectedFactions.isEmpty()
                persistFactions()
                refreshGraph()
            }
        }
        factionChips.add(noneChip)
        chipGroup.addView(noneChip)
        for (faction in factions) {
            val factionColor = try {
                Color.parseColor(faction.color)
            } catch (e: Exception) {
                Color.parseColor("#2196F3")
            }
            val chip = Chip(requireContext()).apply {
                text = faction.name
                isCheckable = true
                isChecked = faction.id in selectedFactions
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    Color.argb(60, Color.red(factionColor), Color.green(factionColor), Color.blue(factionColor))
                )
                setTextColor(factionColor)
                setOnClickListener {
                    if (faction.id in selectedFactions) {
                        selectedFactions.remove(faction.id)
                    } else {
                        selectedFactions.add(faction.id)
                    }
                    // 개별 칩 상태 동기화 (전체 재생성 없이)
                    isChecked = faction.id in selectedFactions
                    allChip.isChecked = selectedFactions.isEmpty()
                    persistFactions()
                    refreshGraph()
                }
            }
            factionChips.add(chip)
            chipGroup.addView(chip)
        }

        // "전체" 칩 클릭: 선택 초기화
        allChip.setOnClickListener {
            selectedFactions.clear()
            allChip.isChecked = true
            factionChips.forEach { it.isChecked = false }
            persistFactions()
            refreshGraph()
        }
    }

    private fun setupUniverseSpinner(universes: List<Universe>) {
        // 삭제된 세계관 참조 정리
        if (currentUniverseId != null && universes.none { it.id == currentUniverseId }) {
            currentUniverseId = null
            currentNovelId = null
            prefs.edit().remove("universe_id").remove("novel_id").apply()
        }

        val names = mutableListOf(getString(R.string.graph_filter_all_universes))
        names.addAll(universes.map { it.name })

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.universeFilterSpinner.adapter = adapter

        // 저장된 세계관 필터 복원
        val restoredPos = if (currentUniverseId != null) {
            val idx = universes.indexOfFirst { it.id == currentUniverseId }
            if (idx >= 0) idx + 1 else 0
        } else 0
        binding.universeFilterSpinner.setSelection(restoredPos)

        binding.universeFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newUniverseId = if (position == 0) null else universes[position - 1].id
                if (newUniverseId != currentUniverseId) {
                    currentUniverseId = newUniverseId
                    currentNovelId = null
                    selectedFactions.clear()
                    prefs.edit().apply {
                        if (newUniverseId != null) putLong("universe_id", newUniverseId) else remove("universe_id")
                        remove("novel_id")
                        putString("faction_ids_json", filterGson.toJson(emptyList<Long>()))
                    }.apply()
                    updateNovelSpinner()
                    setupFactionChips()
                    refreshGraph()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateNovelSpinner() {
        val universeId = currentUniverseId
        val filteredNovels = if (universeId != null) {
            cachedNovels.filter { it.universeId == universeId }
        } else {
            emptyList()
        }

        if (universeId == null) {
            binding.novelFilterSpinner.visibility = View.GONE
            return
        }

        // 삭제된 작품 참조 정리
        if (currentNovelId != null && filteredNovels.none { it.id == currentNovelId }) {
            currentNovelId = null
            prefs.edit().remove("novel_id").apply()
        }

        binding.novelFilterSpinner.visibility = View.VISIBLE
        val names = mutableListOf(getString(R.string.graph_filter_all_novels))
        names.addAll(filteredNovels.map { it.title })

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.novelFilterSpinner.adapter = adapter

        // 저장된 작품 필터 복원
        val restoredPos = if (currentNovelId != null) {
            val idx = filteredNovels.indexOfFirst { it.id == currentNovelId }
            if (idx >= 0) idx + 1 else 0
        } else 0
        binding.novelFilterSpinner.setSelection(restoredPos)

        binding.novelFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newNovelId = if (position == 0) null else filteredNovels[position - 1].id
                if (newNovelId != currentNovelId) {
                    currentNovelId = newNovelId
                    prefs.edit().apply {
                        if (newNovelId != null) putLong("novel_id", newNovelId) else remove("novel_id")
                    }.apply()
                    refreshGraph()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTimeSlider() {
        // 저장된 시간뷰 상태 복원
        binding.switchTimeView.isChecked = isTimeViewEnabled
        binding.graphYearSlider.visibility = if (isTimeViewEnabled) View.VISIBLE else View.GONE
        binding.yearLabel.visibility = if (isTimeViewEnabled) View.VISIBLE else View.GONE

        binding.switchTimeView.setOnCheckedChangeListener { _, isChecked ->
            isTimeViewEnabled = isChecked
            prefs.edit().putBoolean("time_view_enabled", isChecked).apply()
            binding.graphYearSlider.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.yearLabel.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                currentYear = null
                viewModel.updateFactionMembershipsForYear(null)
            } else {
                currentYear = binding.graphYearSlider.value.toInt()
                binding.yearLabel.text = getString(R.string.year_label_format, currentYear!!)
                viewModel.updateFactionMembershipsForYear(currentYear)
            }
            refreshGraph()
        }

        binding.graphYearSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && isTimeViewEnabled) {
                currentYear = value.toInt()
                binding.yearLabel.text = getString(R.string.year_label_format, value.toInt())
                viewModel.updateFactionMembershipsForYear(value.toInt())
                refreshGraphEdgesOnly()
            }
        }
    }

    private fun observeColors() {
        viewModel.relationshipColorMap.observe(viewLifecycleOwner) { colorMap ->
            binding.graphView.setRelationshipColors(colorMap)
        }
    }

    private fun observeData() {
        val combinedData = MediatorLiveData<Triple<List<Character>, List<CharacterRelationship>, List<CharacterRelationshipChange>>>()
        var chars: List<Character> = emptyList()
        var rels: List<CharacterRelationship> = emptyList()
        var changes: List<CharacterRelationshipChange> = emptyList()

        combinedData.addSource(viewModel.characters) {
            chars = it
            combinedData.value = Triple(chars, rels, changes)
        }
        combinedData.addSource(viewModel.relationships) {
            rels = it
            combinedData.value = Triple(chars, rels, changes)
        }
        combinedData.addSource(viewModel.relationshipChanges) {
            changes = it
            combinedData.value = Triple(chars, rels, changes)
        }

        combinedData.observe(viewLifecycleOwner) { (characters, relationships, _) ->
            setupFilterChips(relationships)
            updateTimeControls()
            updateGraph(characters, relationships)
        }

        // 상태변화(생몰년)·세력 멤버십(가입/탈퇴 연도)도 시간축 데이터이므로 슬라이더 범위/가시성에 반영
        viewModel.stateChanges.observe(viewLifecycleOwner) { updateTimeControls() }
        viewModel.factionMemberships.observe(viewLifecycleOwner) { updateTimeControls() }
    }

    /** 시간축 데이터(관계변화 + 멤버십 가입/탈퇴 + 상태변화)의 연도를 모두 모은다. */
    private fun collectTimelineYears(): List<Int> {
        val years = mutableListOf<Int>()
        viewModel.relationshipChanges.value?.forEach { years.add(it.year) }
        viewModel.factionMemberships.value?.forEach { m ->
            m.joinYear?.let { years.add(it) }
            m.leaveYear?.let { years.add(it) }
        }
        // year=0은 시간축과 무관한 시스템 상태변경(__alive 등)일 수 있어 제외
        viewModel.stateChanges.value?.forEach { if (it.year != 0) years.add(it.year) }
        return years
    }

    /**
     * 시간뷰 컨트롤 갱신. 관계변화가 없어도 생몰년·멤버십 연도만 있으면 슬라이더를 쓸 수 있게 한다
     * (기존에는 관계변화 0건이면 시간뷰 자체가 숨겨졌음).
     */
    private fun updateTimeControls() {
        val years = collectTimelineYears()
        binding.yearSliderLayout.visibility = if (years.isNotEmpty()) View.VISIBLE else View.GONE
        if (years.isEmpty()) return

        val minYear = years.min()
        val maxYear = years.max()
        val padding = ((maxYear - minYear) * 0.1f).toInt().coerceAtLeast(10)

        binding.graphYearSlider.stepSize = 0f
        binding.graphYearSlider.valueFrom = (minYear - padding).toFloat()
        binding.graphYearSlider.valueTo = (maxYear + padding).toFloat()
        binding.graphYearSlider.stepSize = 1f
        binding.graphYearSlider.value = (currentYear ?: maxYear).toFloat()
            .coerceIn((minYear - padding).toFloat(), (maxYear + padding).toFloat())

        // 시간뷰 복원 시 currentYear 초기화 (슬라이더 범위 설정 후에야 가능)
        if (isTimeViewEnabled && currentYear == null) {
            currentYear = maxYear
            binding.yearLabel.text = getString(R.string.year_label_format, maxYear)
            viewModel.updateFactionMembershipsForYear(maxYear)
        }
    }

    private fun setupFilterChips(relationships: List<CharacterRelationship>) {
        val chipGroup = binding.filterChipGroup
        chipGroup.removeAllViews()

        val types = relationships.map { it.relationshipType }.distinct().sorted()
        // 무효 선택 제거 (관계 데이터 변경으로 타입이 사라진 경우) — 정리 결과도 영속
        if (selectedRelTypes.retainAll(types.toSet())) persistRelTypes()

        val allChip = Chip(requireContext()).apply {
            text = getString(R.string.graph_filter_all)
            isCheckable = true
            isChecked = selectedRelTypes.isEmpty()
        }
        chipGroup.addView(allChip)

        val typeChips = mutableListOf<Chip>()
        for (type in types) {
            val chip = Chip(requireContext()).apply {
                text = type
                isCheckable = true
                isChecked = type in selectedRelTypes
                setOnClickListener {
                    if (type in selectedRelTypes) {
                        selectedRelTypes.remove(type)
                    } else {
                        selectedRelTypes.add(type)
                    }
                    // 개별 칩 상태 동기화 (전체 재생성 없이)
                    isChecked = type in selectedRelTypes
                    allChip.isChecked = selectedRelTypes.isEmpty()
                    persistRelTypes()
                    refreshGraph()
                }
            }
            typeChips.add(chip)
            chipGroup.addView(chip)
        }

        // "전체" 칩 클릭: 선택 초기화
        allChip.setOnClickListener {
            selectedRelTypes.clear()
            allChip.isChecked = true
            typeChips.forEach { it.isChecked = false }
            persistRelTypes()
            refreshGraph()
        }
    }

    private fun refreshGraph() {
        val chars = viewModel.characters.value ?: return
        val rels = viewModel.relationships.value ?: return
        updateGraph(chars, rels)
    }

    /**
     * 세계관/작품 필터를 적용하여 관계를 필터링한다.
     * @return Pair(필터링된 관계 목록, primary 캐릭터 ID 집합 — null이면 모두 primary)
     */
    private fun applyUniverseNovelFilter(
        allCharacters: List<Character>,
        allRelationships: List<CharacterRelationship>
    ): Pair<List<CharacterRelationship>, Set<Long>?> {
        val universeId = currentUniverseId ?: return allRelationships to null
        val novelId = currentNovelId

        // 세계관 내 소설 ID 및 캐릭터 ID 집합
        val universeNovelIds = cachedNovels.filter { it.universeId == universeId }.map { it.id }.toSet()
        val universeCharIds = allCharacters.filter { it.novelId in universeNovelIds }.map { it.id }.toSet()

        if (novelId != null) {
            // 특정 작품 선택
            val pIds = allCharacters.filter { it.novelId == novelId }.map { it.id }.toSet()
            primaryCharacterIds = pIds
            val filtered = allRelationships.filter { rel ->
                val hasPrimary = rel.characterId1 in pIds || rel.characterId2 in pIds
                val bothInUniverse = rel.characterId1 in universeCharIds && rel.characterId2 in universeCharIds
                hasPrimary && bothInUniverse
            }
            return filtered to pIds
        } else {
            // 세계관 전체
            primaryCharacterIds = universeCharIds
            val filtered = allRelationships.filter { rel ->
                rel.characterId1 in universeCharIds && rel.characterId2 in universeCharIds
            }
            return filtered to null // 세계관 전체일 때는 모두 primary
        }
    }

    /**
     * 세력 좁힘이 살아 있을 때 **남는 인물의 id** — 아니면 `null`(거를 것이 없다).
     *
     * 판정 재료는 [RelationshipGraphViewModel.characterFactionMap]이고, 그것은 시간뷰에서
     * 해당 시점 기준으로 재구성된다 — 그래서 좁힘도 강조와 **같은 시점**을 본다
     * (두 축이 다른 시점을 보면 같은 화면에서 서로 다른 사실을 말한다).
     */
    private fun factionNarrowedIds(): Set<Long>? =
        com.novelcharacter.app.util.GraphFactionNarrow.narrowedIds(
            narrows = factionNarrows,
            selected = selectedFactions,
            membership = (viewModel.characterFactionMap.value ?: emptyMap())
                .mapValues { (_, pairs) -> pairs.map { it.first } },
            allCharacterIds = viewModel.characters.value.orEmpty().map { it.id },
        )

    /** 세력 좁힘을 관계 목록에 적용한다 — 판정은 순수 계층이 든다. */
    private fun applyFactionNarrow(
        relationships: List<CharacterRelationship>
    ): List<CharacterRelationship> =
        com.novelcharacter.app.util.GraphFactionNarrow.apply(relationships, factionNarrowedIds()) {
            it.characterId1 to it.characterId2
        }

    /**
     * 세력 선택 필터의 엣지 2차(흐림) 판정 — showGraph·refreshGraphEdgesOnly가 공용하는
     * 단일 정의 (한쪽만 갱신되는 산탄 방지). "미소속" sentinel 선택 시에는
     * 세력 무관(수동) 관계선이 1차로 보인다.
     *
     * **좁힘 모드에서는 흐리지 않는다** — 남아 있는 것이 전부 고른 세력의 것이라 견줄 상대가
     * 없다. 그 상태에서 흐리면 *"고른 것만 보겠다"*고 해 놓고 그 고른 것을 흐리는 셈이다.
     */
    private fun isEdgeSecondary(rel: CharacterRelationship): Boolean =
        !factionNarrows && selectedFactions.isNotEmpty() && !(
            (rel.factionId != null && rel.factionId in selectedFactions) ||
                (com.novelcharacter.app.util.UnassignedFilter.NO_FACTION_ID in selectedFactions &&
                    rel.factionId == null)
            )

    /**
     * 엣지만 갱신 (슬라이더 드래그 시 빠른 반응). 노드 레이아웃은 유지.
     */
    private fun refreshGraphEdgesOnly() {
        val chars = viewModel.characters.value ?: return
        val rels = viewModel.relationships.value ?: return
        val allChanges = viewModel.relationshipChanges.value ?: emptyList()
        val year = currentYear ?: return

        val (universeFiltered, _) = applyUniverseNovelFilter(chars, rels)

        val typeFiltered = applyFactionNarrow(
            if (selectedRelTypes.isNotEmpty()) {
                universeFiltered.filter { it.relationshipType in selectedRelTypes }
            } else {
                universeFiltered
            }
        )
        // 노드 상한을 여기서도 지킨다 — 이 경로는 노드를 다시 세우지 않으므로,
        // 거르지 않으면 **접힌 인물로 뻗는 엣지**를 관계 수만큼 만들었다가 그리기에서 버린다.
        val filteredRelationships = shownNodeIds?.let { ids ->
            typeFiltered.filter { it.characterId1 in ids && it.characterId2 in ids }
        } ?: typeFiltered

        val hideFactionEdges = !binding.graphView.showFactionEdges

        val edges = filteredRelationships.mapNotNull { rel ->
            // 세력 관계 토글 OFF → 세력 자동 관계 엣지 숨김
            if (hideFactionEdges && rel.factionId != null) return@mapNotNull null
            val isEdgeSecondary = isEdgeSecondary(rel)

            val resolved = viewModel.resolveRelationshipAtYear(rel, year, allChanges)
            GraphEdge(
                fromId = rel.characterId1,
                toId = rel.characterId2,
                label = resolved.resolvedType,
                intensity = resolved.resolvedIntensity,
                isBidirectional = resolved.resolvedBidirectional,
                // 세력 자동 관계는 두 캐릭터가 모두 재적 중일 때만 유효 — 아니면 점선 표시
                isActive = viewModel.isFactionEdgeActiveAtYear(rel, year),
                factionId = rel.factionId,
                isSecondary = isEdgeSecondary
            )
        }

        binding.graphView.updateEdges(edges)
        // 해당 시점 사망 캐릭터 표시 갱신 (노드 레이아웃은 유지)
        binding.graphView.updateDeceased(viewModel.deceasedCharacterIdsAtYear(year))
    }

    private fun updateGraph(allCharacters: List<Character>, allRelationships: List<CharacterRelationship>) {
        val allChanges = viewModel.relationshipChanges.value ?: emptyList()

        // 세계관/작품 필터 적용
        val (universeFiltered, pIds) = applyUniverseNovelFilter(allCharacters, allRelationships)

        // 관계 유형 필터 적용 (멀티셀렉트: 빈 셋 = 전체)
        val typeFiltered = if (selectedRelTypes.isNotEmpty()) {
            universeFiltered.filter { it.relationshipType in selectedRelTypes }
        } else {
            universeFiltered
        }
        // **세력 좁힘은 상한보다 앞이다** — 뒤에 두면 상한이 고른 세력 밖 인물로 먼저 차서
        // 정작 좁혀 보려던 인물이 접힌다(이 모드를 연 이유가 그것이다). 아래 연결 수 집계도
        // 좁힌 뒤 관계로 세야 '무엇을 남길지'의 랭킹이 화면과 같은 모집단을 본다.
        val narrowedIds = factionNarrowedIds()
        val filteredRelationships = com.novelcharacter.app.util.GraphFactionNarrow
            .apply(typeFiltered, narrowedIds) { it.characterId1 to it.characterId2 }

        if (filteredRelationships.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.graphView.visibility = View.GONE
            // **원인을 잘못 지목하지 않는다** — 세력으로 좁혀서 비었는데 "선택한 유형의 관계가
            // 없습니다"라고 말하면 사용자는 유형 칩을 만지러 가고 거기서는 아무 일도 없다.
            val cause = com.novelcharacter.app.util.GraphFactionNarrow.emptyCause(
                anyRelationships = allRelationships.isNotEmpty(),
                afterTypeFilter = typeFiltered.size,
                narrowing = narrowedIds != null,
                typeFiltering = selectedRelTypes.isNotEmpty(),
            )
            binding.emptyMessage.text = getString(
                when (cause) {
                    com.novelcharacter.app.util.GraphFactionNarrow.EmptyCause.NO_DATA ->
                        R.string.graph_no_relationships
                    com.novelcharacter.app.util.GraphFactionNarrow.EmptyCause.FACTION ->
                        R.string.graph_no_faction_relationships
                    com.novelcharacter.app.util.GraphFactionNarrow.EmptyCause.REL_TYPE ->
                        R.string.graph_no_filtered_relationships
                    com.novelcharacter.app.util.GraphFactionNarrow.EmptyCause.OTHER ->
                        R.string.graph_no_scope_relationships
                }
            )
            binding.nodeCountText.text = getString(R.string.graph_node_count, 0)
            binding.edgeCountText.text = getString(R.string.graph_edge_count, 0)
            binding.summaryModeText.visibility = View.GONE
            // 그린 노드가 없으므로 남겨 두면 다음 슬라이더 조작이 **지난 필터의 인물 집합**으로 거른다.
            shownNodeIds = null
            return
        }

        val involvedIds = mutableSetOf<Long>()
        filteredRelationships.forEach {
            involvedIds.add(it.characterId1)
            involvedIds.add(it.characterId2)
        }

        val characterMap = allCharacters.associateBy { it.id }
        val involvedCharacters = involvedIds.mapNotNull { characterMap[it] }

        // 연결 수 — 상한에 걸릴 때 **무엇을 남길지**를 정하는 잣대다(관계가 많은 쪽이 남는다).
        val connectionCount = mutableMapOf<Long, Int>()
        filteredRelationships.forEach {
            connectionCount[it.characterId1] = (connectionCount[it.characterId1] ?: 0) + 1
            connectionCount[it.characterId2] = (connectionCount[it.characterId2] ?: 0) + 1
        }

        // 배치 비용이 노드 수의 제곱이라 여기가 이 화면의 유일한 규모 방어선이다(R-19 · [DisplayCap]).
        // 종전에는 *연결 3개 이상*이라는 **술어**였고, 그것은 남는 수를 가두지 못했다 —
        // 관계가 늘면 함께 늘어(×30에서 841명) 상한을 넘어서 켜진 장치가 상한을 못 지켰다.
        val capped = DisplayCap.rankedCap(
            items = involvedCharacters,
            limit = DisplayCap.GRAPH_NODE_LIMIT,
            scoreOf = { connectionCount[it.id] ?: 0 },
            tieBreakOf = { it.id }
        )
        if (capped.hasHidden) {
            // R-14 — 접었으면 **몇 명을 접었는지** 말한다. 숫자는 상수가 단일 소스라 문구에 박지 않는다.
            binding.summaryModeText.visibility = View.VISIBLE
            binding.summaryModeText.text =
                getString(R.string.graph_summary_mode, capped.shown.size, capped.hiddenCount)
            // 고지가 말하는 '좁힐 수단'은 **지금 켜져 있는 것**이어야 한다 — 세력이 강조 축인
            // 상태에서 "세력으로 좁히라"고 하면 눌러도 아무 일이 없다(R-24가 금지한 자리).
            val hint = if (factionNarrows) {
                R.string.graph_too_many_nodes_narrowing
            } else {
                R.string.graph_too_many_nodes
            }
            Toast.makeText(requireContext(), getString(hint), Toast.LENGTH_LONG).show()
        } else {
            binding.summaryModeText.visibility = View.GONE
        }
        // 양 끝이 모두 남은 관계만 그린다 — 한쪽이 접힌 관계를 그리면 없는 노드로 선이 뻗는다.
        shownNodeIds = if (capped.hasHidden) capped.shown.mapTo(HashSet()) { it.id } else null
        val shownRels = shownNodeIds?.let { ids ->
            filteredRelationships.filter { it.characterId1 in ids && it.characterId2 in ids }
        } ?: filteredRelationships
        showGraph(capped.shown, shownRels, allChanges, pIds)

        binding.emptyState.visibility = View.GONE
        binding.graphView.visibility = View.VISIBLE
        // 세는 것은 **그린 것**이다 — 접힌 몫은 바로 위 고지가 따로 말한다.
        binding.nodeCountText.text = getString(R.string.graph_node_count, capped.shown.size)
        binding.edgeCountText.text = getString(R.string.graph_edge_count, shownRels.size)
    }

    private fun showGraph(
        characters: List<Character>,
        relationships: List<CharacterRelationship>,
        allChanges: List<CharacterRelationshipChange>,
        primaryIds: Set<Long>? = null
    ) {
        val charFactionMap = viewModel.characterFactionMap.value ?: emptyMap()

        // Determine faction-based secondary highlighting (멀티셀렉트: OR 조건).
        // "미소속" sentinel은 charFactionMap 미등재(그래프가 표시하는 소속 없음)로 판정 —
        // 시간뷰에서는 맵이 해당 시점 기준으로 재구성되므로 미소속 판정도 시점을 따라간다.
        // 좁힘 모드에서는 남은 것이 전부 고른 세력이라 2차가 없다(위 [isEdgeSecondary]와 같은 결).
        val factionFilteredIds: Set<Long>? = if (!factionNarrows && selectedFactions.isNotEmpty()) {
            characters.filter { char ->
                com.novelcharacter.app.util.UnassignedFilter.matchesFaction(
                    charFactionMap[char.id]?.map { it.first }, selectedFactions
                )
            }.mapTo(HashSet()) { it.id }
        } else null

        // 시간뷰 활성 시 해당 시점 사망 캐릭터 집합 (회색 + † 표시)
        val deceasedIds = if (isTimeViewEnabled && currentYear != null) {
            viewModel.deceasedCharacterIdsAtYear(currentYear!!)
        } else emptySet()

        val nodes = characters.map { char ->
            val factionPairs = charFactionMap[char.id] ?: emptyList()
            val isSecondaryByNovel = primaryIds != null && char.id !in primaryIds
            val isSecondaryByFaction = factionFilteredIds != null && char.id !in factionFilteredIds
            GraphNode(
                id = char.id,
                label = char.name,
                isSecondary = isSecondaryByNovel || isSecondaryByFaction,
                factionIds = factionPairs.map { it.first },
                factionColors = factionPairs.map { it.second },
                isDeceased = char.id in deceasedIds
            )
        }
        val hideFactionEdges = !binding.graphView.showFactionEdges

        val allEdges = relationships.mapNotNull { rel ->
            // 세력 관계 토글 OFF → 세력 자동 관계 엣지 숨김
            if (hideFactionEdges && rel.factionId != null) return@mapNotNull null
            val isEdgeSecondary = isEdgeSecondary(rel)
            if (isTimeViewEnabled && currentYear != null) {
                val resolved = viewModel.resolveRelationshipAtYear(rel, currentYear!!, allChanges)
                GraphEdge(
                    fromId = rel.characterId1,
                    toId = rel.characterId2,
                    label = resolved.resolvedType,
                    intensity = resolved.resolvedIntensity,
                    isBidirectional = resolved.resolvedBidirectional,
                    // 세력 자동 관계는 두 캐릭터가 모두 재적 중일 때만 유효 — 아니면 점선
                    isActive = viewModel.isFactionEdgeActiveAtYear(rel, currentYear!!),
                    factionId = rel.factionId,
                    isSecondary = isEdgeSecondary
                )
            } else {
                GraphEdge(
                    fromId = rel.characterId1,
                    toId = rel.characterId2,
                    label = rel.relationshipType,
                    intensity = rel.intensity,
                    isBidirectional = rel.isBidirectional,
                    factionId = rel.factionId,
                    isSecondary = isEdgeSecondary
                )
            }
        }
        binding.graphView.setGraphData(nodes, allEdges)
    }

    private fun showNodeContextMenu(characterId: Long) {
        val ctx = context ?: return
        val chars = viewModel.characters.value ?: return
        val rels = viewModel.relationships.value ?: return
        val charName = chars.find { it.id == characterId }?.name ?: return

        val charRels = rels.filter { it.characterId1 == characterId || it.characterId2 == characterId }
        val relLabels = charRels.map { rel ->
            val otherId = if (rel.characterId1 == characterId) rel.characterId2 else rel.characterId1
            val otherName = chars.find { it.id == otherId }?.name ?: "?"
            "$otherName (${rel.relationshipType})"
        }

        val options = mutableListOf(getString(R.string.graph_context_view_detail))
        relLabels.forEach { options.add(it) }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(charName)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    val bundle = Bundle().apply { putLong("characterId", characterId) }
                    findNavController().navigateSafe(R.id.analysisFragment, R.id.characterDetailFragment, bundle)
                } else {
                    val rel = charRels[which - 1]
                    val otherId = if (rel.characterId1 == characterId) rel.characterId2 else rel.characterId1
                    val bundle = Bundle().apply { putLong("characterId", otherId) }
                    findNavController().navigateSafe(R.id.analysisFragment, R.id.characterDetailFragment, bundle)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /**
         * 세력 칩의 축(강조 ↔ 좁힘)을 담는 `graph_ui_state` 키.
         *
         * 저장하는 자리와 읽는 자리가 갈리면 **한쪽만 낡는다** — 이 화면은 이미 필터 키를
         * 문자열로 여럿 들고 있어서, 새로 느는 것만이라도 상수 한 벌로 둔다.
         */
        const val PREF_FACTION_NARROWS = "faction_narrows"
    }
}
