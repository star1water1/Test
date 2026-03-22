package com.novelcharacter.app.ui.graph

import android.os.Bundle
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

    /**
     * characterId -> List<Pair<factionId, factionColor(parsed Int)>>
     */
    private val _characterFactionMap = MutableLiveData<Map<Long, List<Pair<Long, Int>>>>()
    val characterFactionMap: LiveData<Map<Long, List<Pair<Long, Int>>>> = _characterFactionMap

    fun loadData() {
        viewModelScope.launch {
            _characters.value = characterRepository.getAllCharactersList()
            _relationships.value = characterRepository.getAllRelationships()
            _relationshipChanges.value = characterRepository.getAllRelationshipChanges()

            val universeList = universeRepository.getAllUniversesList()
            _universes.value = universeList
            _novels.value = novelRepository.getAllNovelsList()

            val mergedColors = mutableMapOf<String, String>()
            for (universe in universeList) {
                mergedColors.putAll(universe.getRelationshipColorMap())
            }
            _relationshipColorMap.value = mergedColors
        }
    }

    fun resolveRelationshipAtYear(
        relationship: CharacterRelationship,
        year: Int,
        allChanges: List<CharacterRelationshipChange>
    ): ResolvedRelationship {
        val changes = allChanges
            .filter { it.relationshipId == relationship.id && it.year <= year }
            .sortedByDescending { it.year * 10000 + (it.month ?: 0) * 100 + (it.day ?: 0) }

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

    companion object {
        private const val SUMMARY_MODE_THRESHOLD = 200
    }

    private var _binding: FragmentRelationshipGraphBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RelationshipGraphViewModel by viewModels()
    private var currentFilter: String? = null
    private var isTimeViewEnabled = false
    private var currentYear: Int? = null

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

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.graphView.setOnNodeClickListener { characterId ->
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigateSafe(R.id.relationshipGraphFragment, R.id.characterDetailFragment, bundle)
        }

        binding.graphView.setOnNodeLongClickListener { characterId ->
            showNodeContextMenu(characterId)
        }

        setupTimeSlider()
        observeColors()
        observeUniversesAndNovels()
        observeData()
        viewModel.loadData()
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

    private fun setupUniverseSpinner(universes: List<Universe>) {
        val names = mutableListOf(getString(R.string.graph_filter_all_universes))
        names.addAll(universes.map { it.name })

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.universeFilterSpinner.adapter = adapter

        binding.universeFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newUniverseId = if (position == 0) null else universes[position - 1].id
                if (newUniverseId != currentUniverseId) {
                    currentUniverseId = newUniverseId
                    currentNovelId = null
                    updateNovelSpinner()
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

        binding.novelFilterSpinner.visibility = View.VISIBLE
        val names = mutableListOf(getString(R.string.graph_filter_all_novels))
        names.addAll(filteredNovels.map { it.title })

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.novelFilterSpinner.adapter = adapter

        binding.novelFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newNovelId = if (position == 0) null else filteredNovels[position - 1].id
                if (newNovelId != currentNovelId) {
                    currentNovelId = newNovelId
                    refreshGraph()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTimeSlider() {
        binding.switchTimeView.setOnCheckedChangeListener { _, isChecked ->
            isTimeViewEnabled = isChecked
            binding.graphYearSlider.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.yearLabel.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                currentYear = null
            } else {
                currentYear = binding.graphYearSlider.value.toInt()
                binding.yearLabel.text = getString(R.string.year_label_format, currentYear!!)
            }
            refreshGraph()
        }

        binding.graphYearSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && isTimeViewEnabled) {
                currentYear = value.toInt()
                binding.yearLabel.text = getString(R.string.year_label_format, value.toInt())
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

        combinedData.observe(viewLifecycleOwner) { (characters, relationships, relChanges) ->
            setupFilterChips(relationships)
            updateYearSliderRange(relChanges)
            binding.yearSliderLayout.visibility =
                if (relChanges.isNotEmpty()) View.VISIBLE else View.GONE
            updateGraph(characters, relationships)
        }
    }

    private fun updateYearSliderRange(changes: List<CharacterRelationshipChange>) {
        if (changes.isEmpty()) return

        val minYear = changes.minOf { it.year }
        val maxYear = changes.maxOf { it.year }
        val padding = ((maxYear - minYear) * 0.1f).toInt().coerceAtLeast(10)

        binding.graphYearSlider.stepSize = 0f
        binding.graphYearSlider.valueFrom = (minYear - padding).toFloat()
        binding.graphYearSlider.valueTo = (maxYear + padding).toFloat()
        binding.graphYearSlider.stepSize = 1f
        binding.graphYearSlider.value = maxYear.toFloat()
    }

    private fun setupFilterChips(relationships: List<CharacterRelationship>) {
        val chipGroup = binding.filterChipGroup
        chipGroup.removeAllViews()

        val allChip = Chip(requireContext()).apply {
            text = getString(R.string.graph_filter_all)
            isCheckable = true
            isChecked = currentFilter == null
            setOnClickListener {
                currentFilter = null
                refreshGraph()
            }
        }
        chipGroup.addView(allChip)

        val types = relationships.map { it.relationshipType }.distinct().sorted()
        for (type in types) {
            val chip = Chip(requireContext()).apply {
                text = type
                isCheckable = true
                isChecked = currentFilter == type
                setOnClickListener {
                    currentFilter = type
                    refreshGraph()
                }
            }
            chipGroup.addView(chip)
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
     * 엣지만 갱신 (슬라이더 드래그 시 빠른 반응). 노드 레이아웃은 유지.
     */
    private fun refreshGraphEdgesOnly() {
        val chars = viewModel.characters.value ?: return
        val rels = viewModel.relationships.value ?: return
        val allChanges = viewModel.relationshipChanges.value ?: emptyList()
        val year = currentYear ?: return

        val (universeFiltered, _) = applyUniverseNovelFilter(chars, rels)

        val filteredRelationships = if (currentFilter != null) {
            universeFiltered.filter { it.relationshipType == currentFilter }
        } else {
            universeFiltered
        }

        val edges = filteredRelationships.map { rel ->
            val resolved = viewModel.resolveRelationshipAtYear(rel, year, allChanges)
            GraphEdge(
                fromId = rel.characterId1,
                toId = rel.characterId2,
                label = resolved.resolvedType,
                intensity = resolved.resolvedIntensity,
                isBidirectional = resolved.resolvedBidirectional
            )
        }

        binding.graphView.updateEdges(edges)
    }

    private fun updateGraph(allCharacters: List<Character>, allRelationships: List<CharacterRelationship>) {
        val allChanges = viewModel.relationshipChanges.value ?: emptyList()

        // 세계관/작품 필터 적용
        val (universeFiltered, pIds) = applyUniverseNovelFilter(allCharacters, allRelationships)

        // 관계 유형 필터 적용
        val filteredRelationships = if (currentFilter != null) {
            universeFiltered.filter { it.relationshipType == currentFilter }
        } else {
            universeFiltered
        }

        if (filteredRelationships.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.graphView.visibility = View.GONE
            binding.emptyMessage.text = if (allRelationships.isEmpty()) {
                getString(R.string.graph_no_relationships)
            } else {
                getString(R.string.graph_no_filtered_relationships)
            }
            binding.nodeCountText.text = getString(R.string.graph_node_count, 0)
            binding.edgeCountText.text = getString(R.string.graph_edge_count, 0)
            binding.summaryModeText.visibility = View.GONE
            return
        }

        val involvedIds = mutableSetOf<Long>()
        filteredRelationships.forEach {
            involvedIds.add(it.characterId1)
            involvedIds.add(it.characterId2)
        }

        val characterMap = allCharacters.associateBy { it.id }
        val involvedCharacters = involvedIds.mapNotNull { characterMap[it] }

        val isSummaryMode = involvedCharacters.size > SUMMARY_MODE_THRESHOLD
        if (isSummaryMode) {
            binding.summaryModeText.visibility = View.VISIBLE
            binding.summaryModeText.text = getString(R.string.graph_summary_mode, SUMMARY_MODE_THRESHOLD)
            Toast.makeText(requireContext(), getString(R.string.graph_too_many_nodes), Toast.LENGTH_LONG).show()

            val connectionCount = mutableMapOf<Long, Int>()
            filteredRelationships.forEach {
                connectionCount[it.characterId1] = (connectionCount[it.characterId1] ?: 0) + 1
                connectionCount[it.characterId2] = (connectionCount[it.characterId2] ?: 0) + 1
            }
            val importantIds = connectionCount.filter { it.value >= 3 }.keys
            val summaryChars = involvedCharacters.filter { it.id in importantIds }
            val summaryRels = filteredRelationships.filter { it.characterId1 in importantIds && it.characterId2 in importantIds }

            showGraph(summaryChars, summaryRels, allChanges, pIds)
        } else {
            binding.summaryModeText.visibility = View.GONE
            showGraph(involvedCharacters, filteredRelationships, allChanges, pIds)
        }

        binding.emptyState.visibility = View.GONE
        binding.graphView.visibility = View.VISIBLE
        binding.nodeCountText.text = getString(R.string.graph_node_count, involvedCharacters.size)
        binding.edgeCountText.text = getString(R.string.graph_edge_count, filteredRelationships.size)
    }

    private fun showGraph(
        characters: List<Character>,
        relationships: List<CharacterRelationship>,
        allChanges: List<CharacterRelationshipChange>,
        primaryIds: Set<Long>? = null
    ) {
        val nodes = characters.map { char ->
            GraphNode(
                id = char.id,
                label = char.name,
                isSecondary = primaryIds != null && char.id !in primaryIds
            )
        }
        val edges = relationships.map { rel ->
            if (isTimeViewEnabled && currentYear != null) {
                val resolved = viewModel.resolveRelationshipAtYear(rel, currentYear!!, allChanges)
                GraphEdge(
                    fromId = rel.characterId1,
                    toId = rel.characterId2,
                    label = resolved.resolvedType,
                    intensity = resolved.resolvedIntensity,
                    isBidirectional = resolved.resolvedBidirectional
                )
            } else {
                GraphEdge(
                    fromId = rel.characterId1,
                    toId = rel.characterId2,
                    label = rel.relationshipType,
                    intensity = rel.intensity,
                    isBidirectional = rel.isBidirectional
                )
            }
        }
        binding.graphView.setGraphData(nodes, edges)
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

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(charName)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    val bundle = Bundle().apply { putLong("characterId", characterId) }
                    findNavController().navigateSafe(R.id.relationshipGraphFragment, R.id.characterDetailFragment, bundle)
                } else {
                    val rel = charRels[which - 1]
                    val otherId = if (rel.characterId1 == characterId) rel.characterId2 else rel.characterId1
                    val bundle = Bundle().apply { putLong("characterId", otherId) }
                    findNavController().navigateSafe(R.id.relationshipGraphFragment, R.id.characterDetailFragment, bundle)
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
