package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.databinding.FragmentCharacterListBinding
import com.novelcharacter.app.ui.adapter.BirthdayBannerAdapter
import com.novelcharacter.app.ui.adapter.CharacterAdapter
import com.novelcharacter.app.ui.character.batch.BatchEditViewModel
import com.novelcharacter.app.ui.character.batch.BatchOperationBottomSheet
import com.novelcharacter.app.ui.character.batch.BatchOperationResult
import com.novelcharacter.app.ui.character.batch.BatchSelectByFilterBottomSheet
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.OpResult

class CharacterListFragment : Fragment() {

    private var _binding: FragmentCharacterListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private lateinit var adapter: CharacterAdapter
    private val batchViewModel: BatchEditViewModel by activityViewModels()
    private var itemTouchHelper: ItemTouchHelper? = null
    private var novelId: Long = -1L

    // Comparison mode
    private var isCompareMode = false
    private val selectedForCompare = mutableSetOf<Long>()

    // Batch edit mode
    private var isBatchEditMode = false

    // 배치/비교 모드에서 뒤로가기 = 화면 이탈이 아니라 모드 종료(선택 해제). 롱프레스 진입이 잦아진 만큼
    // 뒤로가기로 취소하려다 화면을 뜨며 선택을 흘리는 걸 막는다. isEnabled는 모드 전환 시 토글.
    private val batchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                isBatchEditMode -> exitBatchEditMode()
                isCompareMode -> exitCompareMode()
            }
        }
    }

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
        setupBirthdayBanner()
        setupFilterSort()
        setupToolbarMenu()
        observeData()
        observeBatchEdit()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, batchBackCallback)

        // 회전 후 모드 복원 — 배치/비교는 상호배타라 else-if. 비교 선택도 함께 복원(P2-14).
        if (savedInstanceState?.getBoolean("isBatchEditMode") == true) {
            enterBatchEditMode()
        } else if (savedInstanceState?.getBoolean("isCompareMode") == true) {
            savedInstanceState.getLongArray("selectedForCompare")?.let { selectedForCompare.addAll(it.toList()) }
            enterCompareMode()
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
                // 연쇄 삭제 범위 사전 고지 + 휴지통 복원 안내 (B-7 이후 삭제는 30일 스냅샷 보관 — 고지를 실제 동작과 일치시킨다)
                viewLifecycleOwner.lifecycleScope.launch {
                    val impact = viewModel.getCharacterDeleteImpact(character)
                    if (!isAdded) return@launch
                    val details = buildList {
                        if (impact.relationships > 0) add(getString(R.string.delete_impact_relationships, impact.relationships))
                        if (impact.stateChanges > 0) add(getString(R.string.delete_impact_state_changes, impact.stateChanges))
                        if (impact.factionMemberships > 0) add(getString(R.string.delete_impact_memberships, impact.factionMemberships))
                        if (impact.images > 0) add(getString(R.string.delete_impact_images, impact.images))
                    }
                    val message = if (details.isEmpty()) {
                        getString(R.string.confirm_delete) + "\n\n" + getString(R.string.delete_trash_notice)
                    } else {
                        getString(R.string.confirm_delete) + "\n\n" +
                            getString(R.string.delete_impact_header) + "\n" + details.joinToString("\n") +
                            "\n\n" + getString(R.string.delete_trash_notice)
                    }
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.delete_warning_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            viewModel.deleteCharacter(character)
                            com.novelcharacter.app.util.ImageIndexPrefs.clear(requireContext(), "character", character.id)
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
            },
            onPinClick = { character ->
                viewModel.togglePin(character)
            },
            // 롱프레스 = 일괄편집 진입(카드 전체·이미지 포함). 주 진입은 툴바 아이콘, 롱프레스는 사용자가 요청한 가속기.
            // 일반 탐색 모드일 때만 동작 — 진입 후 롱프레스한 캐릭터를 바로 선택한다. (이미지 뷰어는 상세화면에서)
            onLongClick = { character ->
                if (!isBatchEditMode && !isCompareMode && !adapter.isReorderMode()) {
                    enterBatchEditMode()
                    batchViewModel.toggleSelection(character.id)
                    true
                } else {
                    false
                }
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
                    // 재정렬은 일괄편집·비교와 상호배타 — 두 모드 모두 먼저 종료해야 chrome(검색·필터바·비교바)이
                    // 엉키지 않는다. 기존엔 배치만 종료하고 비교는 안 해서 두 모드가 겹치는 손상 상태가 났다.
                    if (isBatchEditMode) exitBatchEditMode()
                    if (isCompareMode) exitCompareMode()
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
            setFilterSortBarVisible(true)
            updateBirthdayBannerVisibility(true)
            Toast.makeText(requireContext(), R.string.reorder_saved, Toast.LENGTH_SHORT).show()
        } else {
            // 재정렬은 기본(수동) 정렬 + 필터 없음일 때만 — 정렬/필터된 부분집합에 displayOrder를 찍으면 순서가 손상됨
            val sortManual = (viewModel.sortSpec.value?.kind ?: CharacterListPreset.SORT_MANUAL) == CharacterListPreset.SORT_MANUAL
            if (!sortManual || viewModel.hasActiveFilters()) {
                Toast.makeText(requireContext(), R.string.reorder_needs_manual_sort, Toast.LENGTH_SHORT).show()
                return
            }
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
            setFilterSortBarVisible(false)
            updateBirthdayBannerVisibility(false)
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
                // Enter compare mode — 재정렬 모드와 상호배타(방어적)
                if (adapter.isReorderMode()) toggleReorderMode()
                selectedForCompare.clear()
                enterCompareMode()
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

    /** 비교 모드 진입/복원 공용. 선택 초기화는 호출부가 담당(진입은 초기화, 회전 복원은 선택 유지). */
    private fun enterCompareMode() {
        isCompareMode = true
        batchBackCallback.isEnabled = true
        adapter.setSelectionMode(true)
        // 회전 복원 시에도 '최대 선택' 딤 상태를 재구성(진입 시엔 선택 0이라 무해). 누락 시 회전 후 3명 선택
        // 상태의 딤이 사라져 있었다.
        adapter.setMaxReached(selectedForCompare.size >= 3)
        adapter.setSelectedIds(selectedForCompare)
        binding.btnCancelCompare.visibility = View.VISIBLE
        setFilterSortBarVisible(false)
        updateBirthdayBannerVisibility(false)
        updateCompareButtonText()
    }

    private fun exitCompareMode() {
        isCompareMode = false
        batchBackCallback.isEnabled = false
        selectedForCompare.clear()
        adapter.resetState()  // batch reset: clears selection + mode in single notify
        binding.btnCompare.text = getString(R.string.compare_button)
        binding.btnCancelCompare.visibility = View.GONE
        setFilterSortBarVisible(true)
        updateBirthdayBannerVisibility(true)
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
        // 전체/해제/필터 = 보조 조작이라 ⋮ 오버플로로 접어 '작업' 버튼이 좁은 화면에서도 잘리지 않게 한다.
        binding.btnBatchOverflow.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menu.add(0, MENU_SELECT_ALL, 0, R.string.batch_select_all)
            popup.menu.add(0, MENU_DESELECT_ALL, 1, R.string.batch_deselect_all)
            popup.menu.add(0, MENU_FILTER_SELECT, 2, R.string.batch_filter_title)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SELECT_ALL -> {
                        batchViewModel.selectAll(adapter.currentList.map { it.id })
                        true
                    }
                    MENU_DESELECT_ALL -> {
                        batchViewModel.deselectAll()
                        true
                    }
                    MENU_FILTER_SELECT -> {
                        BatchSelectByFilterBottomSheet.newInstance()
                            .show(childFragmentManager, BatchSelectByFilterBottomSheet.TAG)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
        binding.btnBatchAction.setOnClickListener {
            val count = batchViewModel.selectedCount
            if (count > 0) {
                val sheet = BatchOperationBottomSheet.newInstance(count)
                sheet.show(childFragmentManager, BatchOperationBottomSheet.TAG)
            }
        }
    }

    // ===== 생일 배너 =====
    private var birthdayBannerAdapter: BirthdayBannerAdapter? = null
    private var hasBirthdayData = false

    private fun setupBirthdayBanner() {
        val adapter = BirthdayBannerAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onClick = { item ->
                viewModel.recordRecentActivity(item.character.id, item.character.name)
                val bundle = Bundle().apply { putLong("characterId", item.character.id) }
                findNavController().navigateSafe(R.id.characterListFragment, R.id.characterDetailFragment, bundle)
            }
        )
        birthdayBannerAdapter = adapter
        binding.birthdayRecyclerView.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        binding.birthdayRecyclerView.adapter = adapter

        // 접기 상태 복원
        val prefs = requireContext().getSharedPreferences("character_list_ui", android.content.Context.MODE_PRIVATE)
        val collapsed = prefs.getBoolean("birthday_banner_collapsed", false)
        binding.birthdayRecyclerView.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.btnToggleBirthday.rotation = if (collapsed) 180f else 0f

        binding.btnToggleBirthday.setOnClickListener {
            val isVisible = binding.birthdayRecyclerView.visibility == View.VISIBLE
            binding.birthdayRecyclerView.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnToggleBirthday.rotation = if (isVisible) 180f else 0f
            prefs.edit().putBoolean("birthday_banner_collapsed", isVisible).apply()
        }

        viewModel.upcomingBirthdays.observe(viewLifecycleOwner) { items ->
            hasBirthdayData = !items.isNullOrEmpty()
            if (items.isNullOrEmpty()) {
                binding.birthdayBanner.visibility = View.GONE
            } else {
                // 배치편집/비교/재정렬 모드가 아닐 때만 표시
                val showBanner = !isBatchEditMode && !isCompareMode && !this.adapter.isReorderMode()
                binding.birthdayBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
                this.birthdayBannerAdapter?.submitList(items)
                binding.birthdayTitle.text = getString(R.string.birthday_banner_title_count, items.size)
            }
        }
    }

    private fun updateBirthdayBannerVisibility(show: Boolean) {
        if (_binding == null) return
        binding.birthdayBanner.visibility = if (show && hasBirthdayData) View.VISIBLE else View.GONE
    }

    private fun enterBatchEditMode() {
        isBatchEditMode = true
        adapter.setSelectionMode(true)
        binding.batchEditBar.visibility = View.VISIBLE
        binding.compareLayout.visibility = View.GONE
        binding.fabAddCharacter.visibility = View.GONE
        setFilterSortBarVisible(false)
        updateBirthdayBannerVisibility(false)
        // 진입 즉시 현재 선택 상태를 반영. observeBatchEdit의 즉시 전달은 isBatchEditMode=false 시점에
        // 걸러지므로(onViewCreated 순서), 메뉴 진입(선택 0 → 상시 안내)과 회전 복원(살아남은 선택
        // 하이라이트·수·'작업' 활성)을 여기서 초기화한다.
        val surviving = batchViewModel.selectedIds.value ?: emptySet()
        adapter.setSelectedIds(surviving)
        renderBatchSelection(surviving.size)
        batchBackCallback.isEnabled = true
    }

    /** 배치바 선택 수 표기 + '작업' 활성. 0이면 상시 행동 안내(사라지는 Toast 대체)를 보여준다. */
    private fun renderBatchSelection(count: Int) {
        if (_binding == null) return
        binding.batchSelectedCount.text = if (count == 0) {
            getString(R.string.batch_enter_hint)
        } else {
            getString(R.string.batch_selected_count, count)
        }
        binding.btnBatchAction.isEnabled = count > 0 && batchViewModel.isProcessing.value != true
    }

    private fun exitBatchEditMode() {
        isBatchEditMode = false
        batchBackCallback.isEnabled = false
        batchViewModel.deselectAll()
        adapter.resetState()
        binding.batchEditBar.visibility = View.GONE
        binding.compareLayout.visibility = View.VISIBLE
        binding.fabAddCharacter.visibility = View.VISIBLE
        setFilterSortBarVisible(true)
        updateBirthdayBannerVisibility(true)
    }

    private fun observeBatchEdit() {
        batchViewModel.selectedIds.observe(viewLifecycleOwner) { ids ->
            if (isBatchEditMode) {
                adapter.setSelectedIds(ids)
                renderBatchSelection(ids.size)
            }
        }

        batchViewModel.isProcessing.observe(viewLifecycleOwner) { processing ->
            if (isBatchEditMode) {
                binding.btnBatchAction.isEnabled = !processing && batchViewModel.selectedCount > 0
            }
        }

        batchViewModel.operationResult.observe(viewLifecycleOwner) { result ->
            if (result == null || _binding == null) return@observe

            when (result) {
                is BatchOperationResult.Success -> {
                    val opLabel = getOperationLabel(result.operation)
                    // 시맨틱 동기화 부분 실패는 조용히 넘기지 않고 경고로 승격 (변수 제어)
                    if (result.syncFailures > 0) {
                        val message = getString(
                            R.string.batch_success_with_warning,
                            result.affectedCount, opLabel, result.syncFailures
                        )
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                        logOperation(OpResult.failure(
                            OpResult.CAT_BATCH,
                            getString(R.string.result_batch_summary, opLabel, result.affectedCount),
                            getString(R.string.result_batch_sync_warning, result.syncFailures)
                        ))
                    } else if (result.move.hasRemoval) {
                        // 세계관 이동으로 대응 없는 필드값·세력 소속이 제거된 경우 — 유실을 고지(변수 제어).
                        // 같은 이름 필드는 이관되고, 제거분은 휴지통에 백업되어 복원 가능함을 알린다.
                        val message = getString(
                            R.string.batch_move_removed_warning,
                            result.affectedCount, result.move.removedValues, result.move.removedMemberships
                        )
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                        logOperation(OpResult.success(
                            OpResult.CAT_BATCH,
                            getString(R.string.result_batch_summary, opLabel, result.affectedCount),
                            getString(R.string.result_batch_move_detail, result.move.remappedValues, result.move.removedValues, result.move.removedMemberships)
                        ))
                    } else {
                        val remapNote = if (result.move.remappedValues > 0)
                            getString(R.string.result_batch_move_remapped, result.move.remappedValues) else null
                        val message = getString(R.string.batch_success_format, result.affectedCount, opLabel)
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                        logOperation(OpResult.success(
                            OpResult.CAT_BATCH,
                            getString(R.string.result_batch_summary, opLabel, result.affectedCount),
                            remapNote
                        ))
                    }

                    // 배치 작업 후 리스트 강제 갱신 (한 번만)
                    viewModel.refreshList()
                }
                is BatchOperationResult.Error -> {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.batch_error_format, result.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                    logOperation(OpResult.failure(OpResult.CAT_BATCH, result.message))
                }
            }

            batchViewModel.clearResult()
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
        // 비교 모드·선택도 회전에 보존(P2-14) — 배치와 동일하게, 조용한 선택 유실 방지.
        outState.putBoolean("isCompareMode", isCompareMode)
        outState.putLongArray("selectedForCompare", selectedForCompare.toLongArray())
    }

    private var lastListEmpty = false

    private fun observeData() {
        viewModel.searchResults.observe(viewLifecycleOwner) { characters ->
            // 리스트 갱신마다 이미지 인덱스를 초기화하지 않는다(P2-1). 매 emission(핀·필터·정렬·검색)마다
            // clearAll하면 영속 인덱스가 무력화되고 전 캐릭터 썸네일이 재랜덤·O(N) 메인스레드 쓰기가 발생했다.
            // 인덱스는 bind에서 없을 때만 배정·저장되며, 표시는 `idx % paths.size`로 항상 안전하다.
            adapter.submitList(characters)
            lastListEmpty = characters.isEmpty()
            updateEmptyState()
        }

        // 정렬/필터/프리셋 상태 관측
        viewModel.sortSpec.observe(viewLifecycleOwner) { updateSortChip(it) }
        viewModel.fieldFilters.observe(viewLifecycleOwner) { renderFilterChips(); updateEmptyState() }
        viewModel.tagFilters.observe(viewLifecycleOwner) { renderFilterChips(); updateEmptyState() }
        viewModel.presets.observe(viewLifecycleOwner) { renderPresetChips(it ?: emptyList()) }

        // 데이터 처리 결과 알림 (캐릭터 삭제 등 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }
    }

    /** 빈 상태: 필터로 비면 필터 안내 + 지우기, 아니면 일반 안내. */
    private fun updateEmptyState() {
        if (_binding == null) return
        binding.characterRecyclerView.visibility = if (lastListEmpty) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (lastListEmpty) View.VISIBLE else View.GONE
        if (lastListEmpty) {
            val filtered = viewModel.hasActiveFilters()
            binding.emptyTitle.setText(if (filtered) R.string.empty_filtered_title else R.string.no_characters)
            binding.emptyHint.setText(if (filtered) R.string.empty_filtered_hint else R.string.empty_character_hint)
            binding.btnClearFilters.visibility = if (filtered) View.VISIBLE else View.GONE
        }
    }

    // ===== 필터 / 정렬 UI =====

    private fun setupFilterSort() {
        binding.btnSort.setOnClickListener { openSortSheet() }
        binding.btnSortDir.setOnClickListener {
            val cur = viewModel.sortSpec.value ?: CharacterSort()
            // 기본(수동) 정렬은 방향 개념이 없어 기준 선택 시트를 연다
            if (cur.kind == CharacterListPreset.SORT_MANUAL) openSortSheet()
            else viewModel.setSortSpec(cur.copy(ascending = !cur.ascending))
        }
        binding.btnFilter.setOnClickListener { openFilterSheet() }
        binding.btnClearFilters.setOnClickListener { viewModel.clearAllFilters() }
    }

    private fun openSortSheet() {
        val sheet = CharacterSortBottomSheet()
        sheet.currentSort = viewModel.sortSpec.value ?: CharacterSort()
        sheet.loadSortableFields = { viewModel.getSortableFields() }
        sheet.onSortSelected = { viewModel.setSortSpec(it) }
        sheet.show(childFragmentManager, CharacterSortBottomSheet.TAG)
    }

    private fun openFilterSheet() {
        val sheet = CharacterFilterBottomSheet()
        sheet.currentTags = viewModel.tagFilters.value ?: emptySet()
        sheet.loadAllTags = { viewModel.getAllDistinctTags() }
        sheet.loadUniverses = { viewModel.getScopedUniverses() }
        sheet.loadFields = { uid -> viewModel.getFilterableFields(uid) }
        sheet.loadFieldValues = { fid -> viewModel.getFieldValues(fid) }
        sheet.onApply = { tags, filter ->
            viewModel.setTagFilters(tags)
            if (filter != null) viewModel.addFieldFilter(filter)
        }
        sheet.show(childFragmentManager, CharacterFilterBottomSheet.TAG)
    }

    private fun updateSortChip(sort: CharacterSort) {
        if (_binding == null) return
        val isManual = sort.kind == CharacterListPreset.SORT_MANUAL
        binding.btnSortDir.visibility = if (isManual) View.INVISIBLE else View.VISIBLE
        binding.btnSortDir.setImageResource(
            if (sort.ascending) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
        )
        val staticLabel = when (sort.kind) {
            CharacterListPreset.SORT_NAME -> getString(R.string.sort_label_name)
            CharacterListPreset.SORT_CREATED -> getString(R.string.sort_label_created)
            CharacterListPreset.SORT_RECENT -> getString(R.string.sort_label_recent)
            CharacterListPreset.SORT_FIELD -> null  // 필드명은 비동기 조회
            else -> getString(R.string.sort_label_manual)
        }
        if (staticLabel != null) {
            binding.btnSort.text = getString(R.string.sort_chip_format, staticLabel)
        } else {
            // 필드 정렬: 표시 이름을 조회
            viewLifecycleOwner.lifecycleScope.launch {
                val name = viewModel.getSortableFields().firstOrNull { it.key == sort.fieldKey }?.name
                    ?: sort.fieldKey ?: getString(R.string.sort_label_manual)
                if (_binding != null) binding.btnSort.text = getString(R.string.sort_chip_format, name)
            }
        }
    }

    private fun renderFilterChips() {
        if (_binding == null) return
        binding.filterChipGroup.removeAllViews()
        val ctx = context ?: return
        for (tag in viewModel.tagFilters.value ?: emptySet()) {
            binding.filterChipGroup.addView(Chip(ctx).apply {
                text = getString(R.string.filter_chip_tag_format, tag)
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    viewModel.setTagFilters((viewModel.tagFilters.value ?: emptySet()) - tag)
                }
            })
        }
        for (f in viewModel.fieldFilters.value ?: emptyList()) {
            binding.filterChipGroup.addView(Chip(ctx).apply {
                text = getString(R.string.filter_chip_field_format, f.fieldName, f.values.joinToString(", "))
                isCloseIconVisible = true
                setOnCloseIconClickListener { viewModel.removeFieldFilter(f.fieldId) }
            })
        }
    }

    private fun renderPresetChips(presets: List<CharacterListPreset>) {
        if (_binding == null) return
        binding.presetChipGroup.removeAllViews()
        val ctx = context ?: return
        for (p in presets) {
            binding.presetChipGroup.addView(Chip(ctx).apply {
                text = p.name
                setOnClickListener { viewModel.applyPreset(p) }
                setOnLongClickListener { showPresetOptionsDialog(p); true }
            })
        }
        binding.presetChipGroup.addView(Chip(ctx).apply {
            text = getString(R.string.character_preset_save_chip)
            setOnClickListener { showSavePresetDialog() }
        })
    }

    private fun showSavePresetDialog() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = getString(R.string.character_preset_name_hint) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.character_preset_save)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.saveAsPreset(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPresetOptionsDialog(preset: CharacterListPreset) {
        val ctx = requireContext()
        val options = arrayOf(
            getString(R.string.character_preset_apply),
            getString(R.string.preset_edit_name),
            getString(R.string.character_preset_save),   // 현재 상태로 갱신
            getString(R.string.delete)
        )
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(preset.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.applyPreset(preset)
                    1 -> showRenamePresetDialog(preset)
                    2 -> viewModel.overwritePreset(preset)
                    3 -> androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.delete)
                        .setMessage(preset.name)
                        .setPositiveButton(R.string.delete) { _, _ -> viewModel.deletePreset(preset.id, preset.name) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun showRenamePresetDialog(preset: CharacterListPreset) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.character_preset_name_hint)
            setText(preset.name)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.preset_edit_name)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.renamePreset(preset, name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 필터/정렬 바는 일반 탐색 모드에서만 노출(배치/비교/재정렬 중 숨김). */
    private fun setFilterSortBarVisible(visible: Boolean) {
        if (_binding == null) return
        binding.filterSortBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        maybeShowMultiSelectHint()
    }

    /**
     * 롱프레스=다중선택 진입 1회성 힌트 (B-8). 롱프레스는 화면만 봐선 알 수 없어 첫 방문 시 한 번만 안내.
     * onViewCreated가 아닌 onResume에서 호출한다 — 뷰가 윈도우에 부착되기 전
     * Snackbar.make는 "No suitable parent found"로 크래시할 수 있다 (TimelineFragment 동일 패턴 참조).
     */
    private fun maybeShowMultiSelectHint() {
        val ctx = context ?: return
        if (com.novelcharacter.app.util.OnboardingPrefs.isShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_CHARACTER_MULTISELECT_HINT_SHOWN)) return
        val root = _binding?.root ?: return
        if (!root.isAttachedToWindow) return
        com.novelcharacter.app.util.OnboardingPrefs.markShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_CHARACTER_MULTISELECT_HINT_SHOWN)
        com.google.android.material.snackbar.Snackbar
            .make(root, R.string.hint_character_longpress_select, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    override fun onDestroyView() {
        // 배치 선택은 activity 스코프라 화면 이탈 후에도 남는다 → 회전이 아닌 실제 이탈이면 비워
        // 다른(다른 작품) 목록에서 안 보이는 캐릭터가 선택된 채 일괄 작업/삭제되는 것을 막는다.
        if (isBatchEditMode && activity?.isChangingConfigurations != true) {
            batchViewModel.deselectAll()
        }
        searchJob?.cancel()
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        binding.characterRecyclerView.adapter = null
        binding.birthdayRecyclerView.adapter = null
        birthdayBannerAdapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        // 일괄편집 ⋮ 오버플로 메뉴 항목 id
        private const val MENU_SELECT_ALL = 1
        private const val MENU_DESELECT_ALL = 2
        private const val MENU_FILTER_SELECT = 3
    }
}
