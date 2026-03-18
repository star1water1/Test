package com.novelcharacter.app.ui.graph

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.novelcharacter.app.databinding.FragmentRelationshipGraphBinding
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.launch

class RelationshipGraphViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val app = application as NovelCharacterApp
    private val characterRepository = app.characterRepository

    private val _characters = MutableLiveData<List<Character>>()
    val characters: LiveData<List<Character>> = _characters

    private val _relationships = MutableLiveData<List<CharacterRelationship>>()
    val relationships: LiveData<List<CharacterRelationship>> = _relationships

    fun loadData() {
        viewModelScope.launch {
            _characters.value = characterRepository.getAllCharactersList()
            _relationships.value = characterRepository.getAllRelationships()
        }
    }
}

class RelationshipGraphFragment : Fragment() {

    companion object {
        private const val SUMMARY_MODE_THRESHOLD = 200
    }

    private var _binding: FragmentRelationshipGraphBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RelationshipGraphViewModel by viewModels()
    private var currentFilter: String? = null

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

        observeData()
        viewModel.loadData()
    }

    private fun observeData() {
        val combinedData = MediatorLiveData<Pair<List<Character>, List<CharacterRelationship>>>()
        var chars: List<Character> = emptyList()
        var rels: List<CharacterRelationship> = emptyList()

        combinedData.addSource(viewModel.characters) {
            chars = it
            combinedData.value = Pair(chars, rels)
        }
        combinedData.addSource(viewModel.relationships) {
            rels = it
            combinedData.value = Pair(chars, rels)
        }

        combinedData.observe(viewLifecycleOwner) { (characters, relationships) ->
            setupFilterChips(relationships)
            updateGraph(characters, relationships)
        }
    }

    private fun setupFilterChips(relationships: List<CharacterRelationship>) {
        val chipGroup = binding.filterChipGroup
        chipGroup.removeAllViews()

        // "All" chip
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

        // Unique relationship types
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

    private fun updateGraph(allCharacters: List<Character>, allRelationships: List<CharacterRelationship>) {
        // Filter by type if a filter is active
        val filteredRelationships = if (currentFilter != null) {
            allRelationships.filter { it.relationshipType == currentFilter }
        } else {
            allRelationships
        }

        if (filteredRelationships.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.graphView.visibility = View.GONE
            binding.emptyMessage.text = if (allRelationships.isEmpty()) {
                getString(R.string.graph_no_relationships)
            } else {
                getString(R.string.graph_no_relationships)
            }
            binding.nodeCountText.text = getString(R.string.graph_node_count, 0)
            binding.edgeCountText.text = getString(R.string.graph_edge_count, 0)
            binding.summaryModeText.visibility = View.GONE
            return
        }

        // Get only characters that are part of relationships
        val involvedIds = mutableSetOf<Long>()
        filteredRelationships.forEach {
            involvedIds.add(it.characterId1)
            involvedIds.add(it.characterId2)
        }

        val characterMap = allCharacters.associateBy { it.id }
        val involvedCharacters = involvedIds.mapNotNull { characterMap[it] }

        // Summary mode check
        val isSummaryMode = involvedCharacters.size > SUMMARY_MODE_THRESHOLD
        if (isSummaryMode) {
            binding.summaryModeText.visibility = View.VISIBLE
            binding.summaryModeText.text = getString(R.string.graph_summary_mode, SUMMARY_MODE_THRESHOLD)
            Toast.makeText(requireContext(), getString(R.string.graph_too_many_nodes), Toast.LENGTH_LONG).show()

            // In summary mode, only show nodes with 3+ connections
            val connectionCount = mutableMapOf<Long, Int>()
            filteredRelationships.forEach {
                connectionCount[it.characterId1] = (connectionCount[it.characterId1] ?: 0) + 1
                connectionCount[it.characterId2] = (connectionCount[it.characterId2] ?: 0) + 1
            }
            val importantIds = connectionCount.filter { it.value >= 3 }.keys
            val summaryChars = involvedCharacters.filter { it.id in importantIds }
            val summaryRels = filteredRelationships.filter { it.characterId1 in importantIds && it.characterId2 in importantIds }

            showGraph(summaryChars, summaryRels)
        } else {
            binding.summaryModeText.visibility = View.GONE
            showGraph(involvedCharacters, filteredRelationships)
        }

        binding.emptyState.visibility = View.GONE
        binding.graphView.visibility = View.VISIBLE
        binding.nodeCountText.text = getString(R.string.graph_node_count, involvedCharacters.size)
        binding.edgeCountText.text = getString(R.string.graph_edge_count, filteredRelationships.size)
    }

    private fun showGraph(characters: List<Character>, relationships: List<CharacterRelationship>) {
        val nodes = characters.map { GraphNode(it.id, it.name) }
        val edges = relationships.map { GraphEdge(it.characterId1, it.characterId2, it.relationshipType) }
        binding.graphView.setGraphData(nodes, edges)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
