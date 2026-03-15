package com.novelcharacter.app.ui.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentGlobalSearchBinding
import com.novelcharacter.app.ui.adapter.GlobalSearchAdapter

class GlobalSearchFragment : Fragment() {

    private var _binding: FragmentGlobalSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GlobalSearchViewModel by viewModels()
    private lateinit var adapter: GlobalSearchAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGlobalSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        setupRecyclerView()
        setupSearch()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = GlobalSearchAdapter(
            onCharacterClick = { character ->
                val bundle = Bundle().apply { putLong("characterId", character.id) }
                findNavController().navigate(R.id.characterDetailFragment, bundle)
            },
            onEventClick = { /* Events don't have a detail screen */ },
            onNovelClick = { novel ->
                val bundle = Bundle().apply { putLong("novelId", novel.id) }
                findNavController().navigate(R.id.characterListFragment, bundle)
            }
        )
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.searchResultsRecyclerView.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // Auto-focus search field
        binding.searchEdit.requestFocus()
    }

    private fun observeData() {
        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            adapter.submitList(results)
            val query = binding.searchEdit.text.toString()
            val isEmpty = results.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.searchResultsRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.emptyMessage.text = if (query.isBlank()) "검색어를 입력하세요" else "검색 결과가 없습니다"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
