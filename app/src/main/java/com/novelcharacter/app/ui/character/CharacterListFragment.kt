package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentCharacterListBinding
import com.novelcharacter.app.ui.adapter.CharacterAdapter

class CharacterListFragment : Fragment() {

    private var _binding: FragmentCharacterListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private lateinit var adapter: CharacterAdapter
    private var novelId: Long = -1L

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
        observeData()

        if (novelId != -1L) {
            binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        }
    }

    private fun setupRecyclerView() {
        adapter = CharacterAdapter(
            onClick = { character ->
                val bundle = Bundle().apply { putLong("characterId", character.id) }
                findNavController().navigate(R.id.characterDetailFragment, bundle)
            },
            onLongClick = { character ->
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(character.name)
                    .setItems(arrayOf("편집", "삭제")) { _, which ->
                        when (which) {
                            0 -> {
                                val bundle = Bundle().apply { putLong("characterId", character.id) }
                                findNavController().navigate(R.id.characterEditFragment, bundle)
                            }
                            1 -> {
                                androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
        )
        binding.characterRecyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.characterRecyclerView.adapter = adapter
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
            findNavController().navigate(R.id.characterEditFragment, bundle)
        }
    }

    private fun observeData() {
        viewModel.filteredCharacters.observe(viewLifecycleOwner) { characters ->
            adapter.submitList(characters)
            binding.emptyText.visibility = if (characters.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.searchResults.observe(viewLifecycleOwner) { characters ->
            adapter.submitList(characters)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
