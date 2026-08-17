package com.novelcharacter.app.ui.search

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.databinding.FragmentGlobalSearchBinding
import com.novelcharacter.app.ui.adapter.GlobalSearchAdapter
import com.novelcharacter.app.ui.common.PresetRef
import com.novelcharacter.app.ui.common.showPresetNameDialog
import com.novelcharacter.app.util.PresetLimit
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
            onEventClick = { event ->
                // 연표로 직행하여 해당 연도 표시 — center_year는 TimelineFragment.onResume이 소비
                val prefs = requireContext().getSharedPreferences("timeline_ui_state", android.content.Context.MODE_PRIVATE)
                prefs.edit().putInt("center_year", event.year).putBoolean("pending_navigate", true).commit()
                findNavController().navigateSafe(R.id.globalSearchFragment, R.id.timelineFragment)
            },
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
        // 저장·편집이 실패하면 말한다 — 종전에는 예외가 코루틴 밖으로 나가 앱이 죽었다(B-191).
        viewModel.presetSaveFailedEvent.observe(viewLifecycleOwner) { event ->
            event?.getContentIfNotHandled()?.let {
                val ctx = context ?: return@observe
                Toast.makeText(ctx, R.string.result_preset_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
        // 저장은 언제나 되고, 권고 개수를 넘었을 때만 그 사실과 정리 경로를 덧붙인다(B-75).
        viewModel.presetSavedEvent.observe(viewLifecycleOwner) { event ->
            event?.getContentIfNotHandled()?.let { overRecommended ->
                val ctx = context ?: return@observe
                val message =
                    if (overRecommended) getString(R.string.preset_limit_advisory, PresetLimit.RECOMMENDED_MAX)
                    else getString(R.string.search_preset_saved)
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
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

    /**
     * 저장 — 이름이 겹치면 묻는다(B-191, 확정 15장 1번). 창·판정·덮어쓰기 갈래는
     * [showPresetNameDialog] 한 벌이 든다(목록 프리셋과 같은 자리).
     * **검색어는 누른 순간의 값을 넘긴다** — 판정이 비동기라 기다린 뒤 칸을 다시 읽으면
     * 그사이 바뀐 값을 읽는다(R-27).
     */
    private fun showSavePresetDialog() {
        showPresetNameDialog(
            titleRes = R.string.search_preset_save_title,
            hintRes = R.string.search_preset_name_hint,
            allowOverwrite = true,
            lookup = { name -> viewModel.presetNamed(name)?.let { PresetRef(it.id, it.isDefault) } },
            suggest = { name -> viewModel.suggestPresetName(name) },
            onConfirm = { name, overwriteId ->
                val query = binding.searchEdit.text.toString()
                if (overwriteId == null) viewModel.saveCurrentAsPreset(name, query)
                else viewModel.overwritePresetById(overwriteId, name, query)
            }
        )
    }

    private fun showPresetOptionsDialog(preset: SearchPreset) {
        val options = if (preset.isDefault) {
            arrayOf(getString(R.string.search_preset_apply))
        } else {
            arrayOf(getString(R.string.search_preset_apply), getString(R.string.edit), getString(R.string.delete))
        }

        MaterialAlertDialogBuilder(requireContext())
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

    /**
     * 편집 — 이름을 남의 것으로 바꾸는 것은 **덮어쓰기를 제안하지 않는다**(개명과 같은 이유).
     * 성공 고지도 저장이 끝난 뒤에 온다 — 종전에는 창을 닫으며 *"저장했습니다"*를 먼저 띄우고,
     * 겹친 이름이면 그 뒤에 예외가 앱을 죽였다(B-191).
     */
    private fun showEditPresetDialog(preset: SearchPreset) {
        showPresetNameDialog(
            titleRes = R.string.search_preset_edit_title,
            hintRes = R.string.search_preset_name_hint,
            initialName = preset.name,
            selfId = preset.id,
            allowOverwrite = false,
            lookup = { name -> viewModel.presetNamed(name)?.let { PresetRef(it.id, it.isDefault) } },
            suggest = { name -> viewModel.suggestPresetName(name) },
            onConfirm = { name, _ ->
                viewModel.updatePreset(preset.copy(
                    name = name,
                    query = binding.searchEdit.text.toString(),
                    sortMode = viewModel.sortMode.value ?: SearchPreset.SORT_RELEVANCE,
                    filtersJson = viewModel.getFiltersJson()
                ))
            }
        )
    }

    private fun showDeletePresetConfirm(preset: SearchPreset) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
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

        // 정렬 노출 — 계산·저장·프리셋 연동은 이미 있었으나 화면 컨트롤이 없어
        // 프리셋으로 실린 비기본 정렬을 되돌릴 길이 없었다 (필터·정렬 짝 규칙).
        binding.btnSort.setOnClickListener { showSortMenu(it) }
        viewModel.sortMode.observe(viewLifecycleOwner) { mode ->
            binding.btnSort.text = getString(R.string.sort_chip_format, getString(sortModeLabelRes(mode)))
        }
    }

    /** SORT_TAG는 캐릭터에서만 이름순과 동일 동작하는 반쪽 구현이라 메뉴에 싣지 않는다(라벨은 이름순으로 표시). */
    private fun sortModeLabelRes(mode: String?): Int = when (mode) {
        SearchPreset.SORT_NAME, SearchPreset.SORT_TAG -> R.string.sort_name
        SearchPreset.SORT_RECENT -> R.string.sort_recent_edited
        else -> R.string.sort_relevance
    }

    private fun showSortMenu(anchor: View) {
        val modes = listOf(
            SearchPreset.SORT_RELEVANCE to R.string.sort_relevance,
            SearchPreset.SORT_NAME to R.string.sort_name,
            SearchPreset.SORT_RECENT to R.string.sort_recent_edited
        )
        android.widget.PopupMenu(requireContext(), anchor).apply {
            modes.forEachIndexed { i, pair -> menu.add(0, i, i, pair.second) }
            setOnMenuItemClickListener { item ->
                viewModel.setSortMode(modes[item.itemId].first)
                true
            }
            show()
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
                    viewModel.removeFieldFilter(filter)
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
