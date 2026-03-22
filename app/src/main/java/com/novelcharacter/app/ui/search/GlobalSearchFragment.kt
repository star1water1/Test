package com.novelcharacter.app.ui.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.databinding.FragmentGlobalSearchBinding
import com.novelcharacter.app.ui.adapter.GlobalSearchAdapter
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        setupPresets()
        setupFilters()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = GlobalSearchAdapter(
            onCharacterClick = { character ->
                val bundle = Bundle().apply { putLong("characterId", character.id) }
                findNavController().navigateSafe(R.id.globalSearchFragment, R.id.characterDetailFragment, bundle)
            },
            onEventClick = { /* Events don't have a detail screen */ },
            onNovelClick = { novel ->
                val bundle = Bundle().apply { putLong("novelId", novel.id) }
                findNavController().navigateSafe(R.id.globalSearchFragment, R.id.characterListFragment, bundle)
            }
        )
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.searchResultsRecyclerView.adapter = adapter
    }

    private var searchJob: Job? = null

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    viewModel.setSearchQuery(query)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.searchEdit.requestFocus()
    }

    private fun setupPresets() {
        binding.btnSavePreset.setOnClickListener {
            showSavePresetDialog()
        }

        viewModel.presets.observe(viewLifecycleOwner) { presets ->
            updatePresetChips(presets)
        }

        viewModel.presetAppliedEvent.observe(viewLifecycleOwner) { event ->
            event?.getContentIfNotHandled()?.let { name ->
                val ctx = context ?: return@observe
                Toast.makeText(ctx, getString(R.string.search_preset_applied, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePresetChips(presets: List<SearchPreset>) {
        val container = binding.presetContainer
        val ctx = context ?: return
        // Remove all chips except the save button (first child)
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }

        for (preset in presets) {
            val chip = Chip(ctx).apply {
                text = preset.name
                isCheckable = false
                isClickable = true
                textSize = 12f
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (4 * resources.displayMetrics.density).toInt() }
                layoutParams = params

                setOnClickListener {
                    viewModel.applyPreset(preset)
                    if (preset.query.isNotBlank()) {
                        binding.searchEdit.setText(preset.query)
                        binding.searchEdit.setSelection(preset.query.length)
                    }
                }

                setOnLongClickListener {
                    showPresetOptionsDialog(preset)
                    true
                }
            }
            container.addView(chip)
        }
    }

    private fun showSavePresetDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = viewModel.getPresetCount()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (count >= SearchPreset.MAX_PRESETS) {
                Toast.makeText(ctx, getString(R.string.search_preset_limit_reached, SearchPreset.MAX_PRESETS), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val editText = EditText(ctx).apply {
                hint = getString(R.string.search_preset_name_hint)
                setPadding(48, 32, 48, 16)
            }

            AlertDialog.Builder(ctx)
                .setTitle(R.string.search_preset_save_title)
                .setView(editText)
                .setPositiveButton(R.string.save) { _, _ ->
                    val name = editText.text.toString().trim()
                    if (name.isBlank()) {
                        Toast.makeText(ctx, R.string.search_preset_name_required, Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.saveCurrentAsPreset(name)
                        Toast.makeText(ctx, R.string.search_preset_saved, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showPresetOptionsDialog(preset: SearchPreset) {
        val options = if (preset.isDefault) {
            arrayOf(getString(R.string.search_preset_apply))
        } else {
            arrayOf(getString(R.string.search_preset_apply), getString(R.string.edit), getString(R.string.delete))
        }

        AlertDialog.Builder(requireContext())
            .setTitle(preset.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        viewModel.applyPreset(preset)
                        if (preset.query.isNotBlank()) {
                            binding.searchEdit.setText(preset.query)
                            binding.searchEdit.setSelection(preset.query.length)
                        }
                    }
                    1 -> if (!preset.isDefault) showEditPresetDialog(preset)
                    2 -> if (!preset.isDefault) showDeletePresetConfirm(preset)
                }
            }
            .show()
    }

    private fun showEditPresetDialog(preset: SearchPreset) {
        val ctx = context ?: return
        val editText = EditText(ctx).apply {
            setText(preset.name)
            hint = getString(R.string.search_preset_name_hint)
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.search_preset_edit_title)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    viewModel.updatePreset(preset.copy(
                        name = newName,
                        query = binding.searchEdit.text.toString(),
                        sortMode = viewModel.sortMode.value ?: SearchPreset.SORT_RELEVANCE,
                        filtersJson = viewModel.getFiltersJson()
                    ))
                    Toast.makeText(ctx, R.string.search_preset_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeletePresetConfirm(preset: SearchPreset) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.delete_warning_title)
            .setMessage(getString(R.string.confirm_delete_search_preset, preset.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deletePreset(preset.id)
                Toast.makeText(ctx, R.string.search_preset_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupFilters() {
        val openFilter = View.OnClickListener { showFilterBottomSheet() }
        binding.btnAddFilter.setOnClickListener(openFilter)
        binding.btnAddFilterCompact.setOnClickListener(openFilter)

        viewModel.fieldFilters.observe(viewLifecycleOwner) { filters ->
            updateFilterChips(filters)
        }
    }

    private fun updateFilterChips(filters: List<FieldFilter>) {
        val container = binding.filterContainer
        // Remove all except the "add filter" button (first child)
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }

        if (filters.isEmpty()) {
            binding.filterScrollView.visibility = View.GONE
            binding.btnAddFilterCompact.visibility = View.VISIBLE
            return
        }

        binding.filterScrollView.visibility = View.VISIBLE
        binding.btnAddFilterCompact.visibility = View.GONE

        for (filter in filters) {
            val label = getString(R.string.search_filter_chip_format, filter.fieldName, filter.values.joinToString(", "))
            val chip = Chip(requireContext()).apply {
                text = label
                isCloseIconVisible = true
                textSize = 12f
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (4 * resources.displayMetrics.density).toInt() }
                layoutParams = params
                setOnCloseIconClickListener {
                    viewModel.removeFieldFilter(filter.fieldId)
                }
            }
            container.addView(chip)
        }

        // Add "clear all" chip
        if (filters.size > 1) {
            val clearChip = Chip(requireContext()).apply {
                text = getString(R.string.search_filter_clear_all)
                textSize = 11f
                isCloseIconVisible = false
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (4 * resources.displayMetrics.density).toInt() }
                layoutParams = params
                setOnClickListener { viewModel.clearFieldFilters() }
            }
            container.addView(clearChip)
        }
    }

    private fun showFilterBottomSheet() {
        val sheet = SearchFilterBottomSheet()
        sheet.loadUniverses = { viewModel.getAllUniverses() }
        sheet.loadFields = { universeId -> viewModel.getFieldDefinitions(universeId) }
        sheet.loadFieldValues = { fieldDefId -> viewModel.getFieldValues(fieldDefId) }
        sheet.onFilterApplied = { filter ->
            viewModel.addFieldFilter(filter)
        }
        sheet.show(childFragmentManager, SearchFilterBottomSheet.TAG)
    }

    private fun observeData() {
        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            adapter.submitList(results)
            val query = binding.searchEdit.text.toString()
            val hasFilters = (viewModel.fieldFilters.value?.size ?: 0) > 0
            val isEmpty = results.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.searchResultsRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.emptyMessage.text = when {
                query.isBlank() && !hasFilters -> getString(R.string.search_empty_hint)
                else -> getString(R.string.search_empty_result)
            }
        }
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        binding.searchResultsRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
