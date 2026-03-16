package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentCharacterListBinding
import com.novelcharacter.app.ui.adapter.CharacterAdapter
import com.novelcharacter.app.util.navigateSafe

class CharacterListFragment : Fragment() {

    private var _binding: FragmentCharacterListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private lateinit var adapter: CharacterAdapter
    private var novelId: Long = -1L

    // Comparison mode
    private var isCompareMode = false
    private val selectedForCompare = mutableSetOf<Long>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        novelId = arguments?.getLong("novelId", -1L) ?: -1L
        viewModel.setNovelFilter(novelId)

        setupRecyclerView()
        setupSearch()
        setupFab()
        setupCompareButton()
        observeData()

        if (novelId != -1L) {
            binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        }
    }

    private fun setupRecyclerView() {
        adapter = CharacterAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onClick = { character ->
                if (isCompareMode) {
                    toggleCompareSelection(character.id)
                } else {
                    val bundle = Bundle().apply { putLong("characterId", character.id) }
                    findNavController().navigateSafe(R.id.characterListFragment, R.id.characterDetailFragment, bundle)
                }
            },
            onLongClick = { character ->
                if (isCompareMode) {
                    toggleCompareSelection(character.id)
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(character.name)
                        .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                            when (which) {
                                0 -> {
                                    val bundle = Bundle().apply { putLong("characterId", character.id) }
                                    findNavController().navigateSafe(R.id.characterListFragment, R.id.characterEditFragment, bundle)
                                }
                                1 -> {
                                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                        .setIcon(android.R.drawable.ic_dialog_alert)
                                        .setTitle(R.string.delete_warning_title)
                                        .setMessage(R.string.confirm_delete)
                                        .setPositiveButton(R.string.yes) { _, _ ->
                                            viewModel.deleteCharacter(character)
                                        }
                                        .setNegativeButton(R.string.no, null)
                                        .show()
                                }
                            }
                        }
                        .show()
                }
            }
        )
        binding.characterRecyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.characterRecyclerView.adapter = adapter
    }

    private fun toggleCompareSelection(characterId: Long) {
        if (selectedForCompare.contains(characterId)) {
            selectedForCompare.remove(characterId)
        } else {
            if (selectedForCompare.size >= 3) {
                Toast.makeText(requireContext(), "최대 3명까지 비교할 수 있습니다", Toast.LENGTH_SHORT).show()
                return
            }
            selectedForCompare.add(characterId)
        }
        // Update max-reached first (no-op if unchanged), then setSelectedIds triggers single notify
        adapter.setMaxReached(selectedForCompare.size >= 3)
        adapter.setSelectedIds(selectedForCompare)
        updateCompareButtonText()
    }

    private fun setupCompareButton() {
        binding.btnCompare.setOnClickListener {
            if (!isCompareMode) {
                // Enter compare mode
                isCompareMode = true
                selectedForCompare.clear()
                adapter.setSelectionMode(true)
                binding.btnCompare.text = "비교하기"
                binding.btnCancelCompare.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "비교할 캐릭터를 선택하세요 (2~3명)", Toast.LENGTH_SHORT).show()
            } else {
                // Execute comparison
                if (selectedForCompare.size < 2) {
                    Toast.makeText(requireContext(), "2명 이상 선택하세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val idsStr = selectedForCompare.joinToString(",")
                val bundle = Bundle().apply { putString("characterIds", idsStr) }
                exitCompareMode()
                findNavController().navigateSafe(R.id.characterListFragment, R.id.characterCompareFragment, bundle)
            }
        }

        binding.btnCancelCompare.setOnClickListener {
            exitCompareMode()
        }
    }

    private fun exitCompareMode() {
        isCompareMode = false
        selectedForCompare.clear()
        adapter.resetState()  // batch reset: clears selection + mode in single notify
        binding.btnCompare.text = "비교"
        binding.btnCancelCompare.visibility = View.GONE
    }

    private fun updateCompareButtonText() {
        if (selectedForCompare.isEmpty()) {
            binding.btnCompare.text = "비교하기"
        } else {
            binding.btnCompare.text = getString(R.string.compare_button_text, selectedForCompare.size)
        }
    }

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFab() {
        binding.fabAddCharacter.setOnClickListener {
            val bundle = Bundle().apply {
                putLong("characterId", -1L)
                putLong("novelId", novelId)
            }
            findNavController().navigateSafe(R.id.characterListFragment, R.id.characterEditFragment, bundle)
        }
    }

    private fun observeData() {
        viewModel.searchResults.observe(viewLifecycleOwner) { characters ->
            adapter.submitList(characters)
            val isEmpty = characters.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.characterRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        binding.characterRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
