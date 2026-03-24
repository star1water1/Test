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
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentCharacterListBinding
import com.novelcharacter.app.ui.adapter.CharacterAdapter
import com.novelcharacter.app.ui.character.batch.BatchEditViewModel
import com.novelcharacter.app.ui.character.batch.BatchOperationBottomSheet
import com.novelcharacter.app.ui.character.batch.BatchOperationResult
import com.novelcharacter.app.ui.character.batch.BatchSelectByFilterBottomSheet
import com.novelcharacter.app.util.navigateSafe

class CharacterListFragment : Fragment() {

    private var _binding: FragmentCharacterListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private lateinit var adapter: CharacterAdapter
    private val batchViewModel: BatchEditViewModel by viewModels()
    private var itemTouchHelper: ItemTouchHelper? = null
    private var novelId: Long = -1L

    // Comparison mode
    private var isCompareMode = false
    private val selectedForCompare = mutableSetOf<Long>()

    // Batch edit mode
    private var isBatchEditMode = false

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
        setupBatchEditBar()
        setupToolbarMenu()
        observeData()
        observeBatchEdit()

        // 회전 후 배치 편집 모드 복원
        if (savedInstanceState?.getBoolean("isBatchEditMode") == true) {
            enterBatchEditMode()
        }

        if (novelId != -1L) {
            binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        }
    }

    private fun setupRecyclerView() {
        adapter = CharacterAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onClick = { character ->
                when {
                    isBatchEditMode -> batchViewModel.toggleSelection(character.id)
                    isCompareMode -> toggleCompareSelection(character.id)
                    else -> {
                        viewModel.recordRecentActivity(character.id, character.name)
                        val bundle = Bundle().apply { putLong("characterId", character.id) }
                        findNavController().navigateSafe(R.id.characterListFragment, R.id.characterDetailFragment, bundle)
                    }
                }
            },
            onEditClick = { character ->
                val bundle = Bundle().apply { putLong("characterId", character.id) }
                findNavController().navigateSafe(R.id.characterListFragment, R.id.characterEditFragment, bundle)
            },
            onDeleteClick = { character ->
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(R.string.delete_warning_title)
                    .setMessage(R.string.confirm_delete)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        viewModel.deleteCharacter(character)
                        com.novelcharacter.app.util.ImageIndexPrefs.clear(requireContext(), "character", character.id)
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            },
            onPinClick = { character ->
                viewModel.togglePin(character)
            },
            onImageClick = { imagePaths, startIndex ->
                val bundle = Bundle().apply {
                    putString("imagePaths", imagePaths)
                    putInt("startPosition", startIndex)
                }
                findNavController().navigateSafe(R.id.characterListFragment, R.id.imageViewerFragment, bundle)
            }
        )
        binding.characterRecyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.characterRecyclerView.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (adapter.isReorderMode()) {
                    viewModel.updateCharacterDisplayOrders(adapter.getReorderedList())
                }
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.characterRecyclerView)
            adapter.itemTouchHelper = it
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.inflateMenu(R.menu.character_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reorder -> {
                    if (isBatchEditMode) exitBatchEditMode()
                    toggleReorderMode()
                    true
                }
                R.id.action_batch_edit -> {
                    if (isCompareMode) exitCompareMode()
                    if (adapter.isReorderMode()) toggleReorderMode()
                    enterBatchEditMode()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleReorderMode() {
        if (adapter.isReorderMode()) {
            // 드래그 완료 시 clearView()에서 이미 자동 저장됨
            adapter.setReorderMode(false)
            binding.searchEdit.isEnabled = true
            binding.searchEdit.alpha = 1f
            Toast.makeText(requireContext(), R.string.reorder_saved, Toast.LENGTH_SHORT).show()
        } else {
            // Block reorder when search is active
            val query = binding.searchEdit.text?.toString().orEmpty()
            if (query.isNotBlank()) {
                Toast.makeText(requireContext(), R.string.reorder_disabled_while_searching, Toast.LENGTH_SHORT).show()
                return
            }
            adapter.setReorderMode(true)
            // 순서변경 모드에서 검색 UI를 명시적으로 비활성화
            binding.searchEdit.isEnabled = false
            binding.searchEdit.alpha = 0.5f
            Toast.makeText(requireContext(), R.string.reorder_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleCompareSelection(characterId: Long) {
        if (selectedForCompare.contains(characterId)) {
            selectedForCompare.remove(characterId)
        } else {
            if (selectedForCompare.size >= 3) {
                Toast.makeText(requireContext(), R.string.max_compare_limit, Toast.LENGTH_SHORT).show()
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
                binding.btnCompare.text = getString(R.string.compare_mode_label)
                binding.btnCancelCompare.visibility = View.VISIBLE
                Toast.makeText(requireContext(), R.string.compare_select_hint, Toast.LENGTH_SHORT).show()
            } else {
                // Execute comparison
                if (selectedForCompare.size < 2) {
                    Toast.makeText(requireContext(), R.string.compare_min_select, Toast.LENGTH_SHORT).show()
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
        binding.btnCompare.text = getString(R.string.compare_button)
        binding.btnCancelCompare.visibility = View.GONE
    }

    private fun updateCompareButtonText() {
        if (selectedForCompare.isEmpty()) {
            binding.btnCompare.text = getString(R.string.compare_mode_label)
        } else {
            binding.btnCompare.text = getString(R.string.compare_button_text, selectedForCompare.size)
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Discard search input while reorder mode is active to prevent display order corruption
                if (adapter.isReorderMode()) return
                val query = s.toString()
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(300)
                    viewModel.setSearchQuery(query)
                }
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

    // ===== 일괄 편집 모드 =====

    private fun setupBatchEditBar() {
        binding.btnBatchClose.setOnClickListener { exitBatchEditMode() }
        binding.btnBatchSelectAll.setOnClickListener {
            val visibleIds = adapter.currentList.map { it.id }
            batchViewModel.selectAll(visibleIds)
        }
        binding.btnBatchDeselectAll.setOnClickListener {
            batchViewModel.deselectAll()
        }
        binding.btnBatchFilter.setOnClickListener {
            BatchSelectByFilterBottomSheet.newInstance()
                .show(childFragmentManager, BatchSelectByFilterBottomSheet.TAG)
        }
        binding.btnBatchAction.setOnClickListener {
            val count = batchViewModel.selectedCount.value ?: 0
            if (count > 0) {
                val sheet = BatchOperationBottomSheet.newInstance(count)
                sheet.show(childFragmentManager, BatchOperationBottomSheet.TAG)
            }
        }
    }

    private fun enterBatchEditMode() {
        isBatchEditMode = true
        adapter.setSelectionMode(true)
        binding.batchEditBar.visibility = View.VISIBLE
        binding.compareLayout.visibility = View.GONE
        binding.fabAddCharacter.visibility = View.GONE
        Toast.makeText(requireContext(), R.string.batch_enter_hint, Toast.LENGTH_SHORT).show()
    }

    private fun exitBatchEditMode() {
        isBatchEditMode = false
        batchViewModel.deselectAll()
        adapter.resetState()
        binding.batchEditBar.visibility = View.GONE
        binding.compareLayout.visibility = View.VISIBLE
        binding.fabAddCharacter.visibility = View.VISIBLE
    }

    private fun observeBatchEdit() {
        batchViewModel.selectedIds.observe(viewLifecycleOwner) { ids ->
            if (isBatchEditMode) {
                adapter.setSelectedIds(ids)
                val count = ids.size
                binding.batchSelectedCount.text = if (count == 0) {
                    getString(R.string.batch_selected_count_zero)
                } else {
                    getString(R.string.batch_selected_count, count)
                }
                binding.btnBatchAction.isEnabled = count > 0
            }
        }

        batchViewModel.operationResult.observe(viewLifecycleOwner) { result ->
            if (result == null || _binding == null) return@observe
            batchViewModel.clearResult()

            when (result) {
                is BatchOperationResult.Success -> {
                    val opLabel = getOperationLabel(result.operation)
                    val message = getString(R.string.batch_success_format, result.affectedCount, opLabel)
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

                    // 배치 작업 후 리스트 강제 갱신 (한 번만)
                    viewModel.refreshList()
                }
                is BatchOperationResult.Error -> {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.batch_error_format, result.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun getOperationLabel(operation: String): String = when (operation) {
        "setPinned" -> getString(R.string.batch_op_result_pin)
        "changeNovel" -> getString(R.string.batch_op_result_change_novel)
        "addTags" -> getString(R.string.batch_op_result_add_tags)
        "removeTags" -> getString(R.string.batch_op_result_remove_tags)
        "setFieldValue" -> getString(R.string.batch_op_result_set_field)
        "clearFieldValue" -> getString(R.string.batch_op_result_clear_field)
        "appendMemo" -> getString(R.string.batch_op_result_append_memo)
        "delete" -> getString(R.string.batch_op_result_delete)
        else -> operation
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isBatchEditMode", isBatchEditMode)
    }

    private fun observeData() {
        viewModel.searchResults.observe(viewLifecycleOwner) { characters ->
            adapter.refreshRandomImages(context)
            adapter.submitList(characters)
            val isEmpty = characters.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.characterRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        binding.characterRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
