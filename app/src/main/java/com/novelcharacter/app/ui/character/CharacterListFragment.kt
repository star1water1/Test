package com.novelcharacter.app.ui.character

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.novelcharacter.app.data.repository.TrashRetentionPolicy
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
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError

class CharacterListFragment : Fragment() {

    private var _binding: FragmentCharacterListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private lateinit var adapter: CharacterAdapter
    private val batchViewModel: BatchEditViewModel by activityViewModels()
    private var itemTouchHelper: ItemTouchHelper? = null
    private var novelId: Long = -1L

    // 선택 모드 (B-83) — 비교와 일괄 편집이 공유한다. 목적지는 선택을 마친 뒤 바에서 고른다.
    // 선택 집합의 단일 소스는 batchViewModel.selectedIds다(비교 전용 집합을 따로 두지 않는다 —
    // 두 벌이면 어느 문으로 들어왔는지에 따라 선택이 갈린다).
    private var isSelectionMode = false

    // 탐색 origin 목적지 id — 하단 캐릭터 탭 루트는 characterTabFragment(novelId=-1),
    // 작품 목록·검색에서 novelId를 들고 푸시되면 characterListFragment다.
    // (모든 푸시 경로가 실제 novelId를 전달하므로 -1은 탭 루트에서만 나타난다)
    private val navOriginId: Int
        get() = if (novelId == -1L) R.id.characterTabFragment else R.id.characterListFragment

    // 선택 모드에서 뒤로가기 = 화면 이탈이 아니라 모드 종료(선택 해제). 롱프레스 진입이 잦아진 만큼
    // 뒤로가기로 취소하려다 화면을 뜨며 선택을 흘리는 걸 막는다. isEnabled는 모드 전환 시 토글.
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isSelectionMode) exitSelectionMode()
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
        setupSelectionBar()
        setupBirthdayBanner()
        setupFilterSort()
        setupOverflowMenu()
        observeData()
        observeBatchEdit()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        // 회전 후 모드 복원. 선택 자체는 activity 스코프 ViewModel에 살아 있으므로 여기서 되살릴 것은
        // 모드뿐이다 — enterSelectionMode가 살아남은 선택을 그대로 그린다(P2-14의 비교 선택 보존도
        // 같은 자리에서 함께 해결된다. 종전엔 비교만 Bundle로 따로 실어 날랐다).
        if (savedInstanceState?.getBoolean("isSelectionMode") == true) {
            enterSelectionMode()
        }

        // 툴바는 조건부다 (N-1 §7-2): 탭 루트에서는 하단 내비가 이미 "캐릭터"를 말하므로 감춰
        // 상단을 2행(액션 행 + 칩 행)으로 줄이고, 작품별 목록으로 푸시되면 뒤로가기+작품명을 위해
        // 살아 있어야 한다. 메뉴는 어느 상태에서도 갖지 않는다 — 호스트는 액션 행의 ⋮ 하나다.
        if (novelId != -1L) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        } else {
            binding.toolbar.visibility = View.GONE
        }
    }

    /**
     * 휴지통 보관 안내 — 보관 한도는 사용자가 정하므로(B-74) 문구에 박지 않고 현행 정책을 읽는다.
     * 이 화면의 두 갈래(연관 데이터 있음/없음)가 같은 문구를 써야 해서 한 곳에서 만든다.
     */
    private fun trashNoticeText(): String {
        val policy = TrashRetentionPolicy.currentOrDefault()
        return getString(R.string.delete_trash_notice, policy.retentionDays, policy.maxOperations)
    }

    private fun setupRecyclerView() {
        adapter = CharacterAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onClick = { character ->
                when {
                    isSelectionMode -> batchViewModel.toggleSelection(character.id)
                    else -> {
                        viewModel.recordRecentActivity(character.id, character.name)
                        val bundle = Bundle().apply { putLong("characterId", character.id) }
                        findNavController().navigateSafe(navOriginId, R.id.characterDetailFragment, bundle)
                    }
                }
            },
            onEditClick = { character ->
                val bundle = Bundle().apply { putLong("characterId", character.id) }
                findNavController().navigateSafe(navOriginId, R.id.characterEditFragment, bundle)
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
                        getString(R.string.confirm_delete) + "\n\n" + trashNoticeText()
                    } else {
                        getString(R.string.confirm_delete) + "\n\n" +
                            getString(R.string.delete_impact_header) + "\n" + details.joinToString("\n") +
                            "\n\n" + trashNoticeText()
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setIcon(R.drawable.ic_warning)
                        .setTitle(R.string.delete_warning_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            viewModel.deleteCharacter(character)
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
            },
            onPinClick = { character ->
                viewModel.togglePin(character)
            },
            // 롱프레스 = 선택 모드 진입(카드 전체·이미지 포함). ⋮의 두 항목이 이름을 붙인 주 진입이고,
            // 롱프레스는 그 둘을 함께 여는 가속기다 — B-83이 요구한 "대체 진입점"이 이것이다.
            // 일반 탐색 모드일 때만 동작 — 진입 후 롱프레스한 캐릭터를 바로 선택한다. (이미지 뷰어는 상세화면에서)
            onLongClick = { character ->
                if (!isSelectionMode && !adapter.isReorderMode()) {
                    enterSelectionMode()
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

    /**
     * ⋮ 통합 메뉴 (N-1 §7-2) — 보충(탭 루트만)·비교·일괄 편집·순서 변경.
     * 액션 행은 탭 루트·푸시 양쪽에서 보이므로 메뉴 호스트는 이 하나뿐이다 — 툴바에도 메뉴를
     * 두면 보충의 "탭 루트에서만" 게이팅이 두 벌이 되고, 두 벌이 된 게이팅은 반드시 갈린다.
     * 보충의 상시 진입점은 홈 도구 타일(N-2)이 맡는다 — 총 발견성은 옮기기 전보다 올라간다.
     *
     * 비교·일괄 편집은 **같은 선택 모드로 들어간다**(B-83). 항목을 둘로 남겨 둔 것은 메뉴가
     * 이 화면에서 무엇을 할 수 있는지 말하는 유일한 자리이기 때문이다 — 하나로 합치면
     * 목적지의 이름이 메뉴에서 사라져, 선택 모드에 들어가 보기 전까지 비교의 존재를 알 수 없다
     * (원칙 04). 두 항목의 차이는 비교가 인원 제한을 미리 일러 주는 것뿐이다.
     */
    private fun setupOverflowMenu() {
        binding.btnListOverflow.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            if (novelId == -1L) {
                // 보충은 전역 캐릭터 탭의 도구 — 작품 스코프로 푸시된 목록에서는 항목 자체가 없다
                popup.menu.add(0, MENU_SUPPLEMENT, 0, R.string.tab_supplement)
            }
            popup.menu.add(0, MENU_COMPARE, 1, R.string.compare_mode_label)
            popup.menu.add(0, MENU_BATCH_EDIT, 2, R.string.batch_edit_mode)
            popup.menu.add(0, MENU_REORDER, 3, R.string.reorder_mode)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SUPPLEMENT -> {
                        findNavController().navigateSafe(navOriginId, R.id.supplementFragment)
                        true
                    }
                    MENU_COMPARE -> {
                        if (adapter.isReorderMode()) toggleReorderMode()
                        if (!isSelectionMode) enterSelectionMode()
                        // 인원 제한(2~3명)은 이름을 걸고 들어온 이 경로에서만 미리 일러 준다.
                        // 롱프레스로 들어온 사용자는 4명 이상에서 '비교하기'를 누를 때 듣는다.
                        Toast.makeText(requireContext(), R.string.compare_select_hint, Toast.LENGTH_SHORT).show()
                        true
                    }
                    MENU_REORDER -> {
                        // 재정렬은 선택 모드와 상호배타 — 먼저 종료해야 chrome(검색·필터바·선택바)이 엉키지 않는다.
                        if (isSelectionMode) exitSelectionMode()
                        toggleReorderMode()
                        true
                    }
                    MENU_BATCH_EDIT -> {
                        if (adapter.isReorderMode()) toggleReorderMode()
                        if (!isSelectionMode) enterSelectionMode()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
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

    /**
     * 선택한 캐릭터로 비교 화면에 들어간다.
     *
     * 상한(3명)은 **거절이 아니라 설명으로** 건다. 종전 비교 모드는 4번째 선택 자체를 막고
     * 나머지 카드를 딤 처리했는데, 선택이 일괄 편집과 한 벌이 된 지금 그 딤은 성립하지 않는다
     * (일괄 편집은 상한이 없다). 그래서 넘긴 선택을 조용히 자르거나 버튼을 죽여 두는 대신,
     * 무엇이 문제이고 어떻게 고치는지를 말한다(개발 의도 2번 '변수 제어' — 막다른 길을 두지 않는다).
     */
    private fun runCompare() {
        val ids = batchViewModel.selectedIds.value.orEmpty()
        if (ids.size > MAX_COMPARE) {
            Toast.makeText(requireContext(), R.string.max_compare_limit, Toast.LENGTH_SHORT).show()
            return
        }
        if (ids.size < MIN_COMPARE) {
            Toast.makeText(requireContext(), R.string.compare_min_select, Toast.LENGTH_SHORT).show()
            return
        }
        val bundle = Bundle().apply { putString("characterIds", ids.joinToString(",")) }
        exitSelectionMode()
        findNavController().navigateSafe(navOriginId, R.id.characterCompareFragment, bundle)
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
            findNavController().navigateSafe(navOriginId, R.id.characterEditFragment, bundle)
        }
    }

    // ===== 선택 모드 =====

    private fun setupSelectionBar() {
        binding.btnSelectionClose.setOnClickListener { exitSelectionMode() }
        binding.btnSelectionCompare.setOnClickListener { runCompare() }
        // 전체/해제/필터 = 보조 조작이라 ⋮ 오버플로로 접어 실행 버튼이 좁은 화면에서도 잘리지 않게 한다.
        binding.btnSelectionOverflow.setOnClickListener { anchor ->
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
        binding.btnSelectionAction.setOnClickListener {
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
                findNavController().navigateSafe(navOriginId, R.id.characterDetailFragment, bundle)
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
                // 선택/재정렬 모드가 아닐 때만 표시
                val showBanner = !isSelectionMode && !this.adapter.isReorderMode()
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

    private fun enterSelectionMode() {
        isSelectionMode = true
        adapter.setSelectionMode(true)
        binding.selectionBar.visibility = View.VISIBLE
        binding.actionRow.visibility = View.GONE
        binding.fabAddCharacter.visibility = View.GONE
        setFilterSortBarVisible(false)
        updateBirthdayBannerVisibility(false)
        // 진입 즉시 현재 선택 상태를 반영. observeBatchEdit의 즉시 전달은 isSelectionMode=false 시점에
        // 걸러지므로(onViewCreated 순서), 메뉴 진입(선택 0 → 상시 안내)과 회전 복원(살아남은 선택
        // 하이라이트·수·실행 버튼 활성)을 여기서 초기화한다.
        val surviving = batchViewModel.selectedIds.value ?: emptySet()
        adapter.setSelectedIds(surviving)
        renderSelection(surviving.size)
        selectionBackCallback.isEnabled = true
    }

    /**
     * 선택 바 상태 — 선택 수 표기 + 실행 둘의 활성.
     * 0이면 상시 행동 안내(사라지는 Toast 대체)를 보여준다.
     *
     * 실행 둘 다 "선택이 있으면 활성"이다. 비교의 2~3명 조건으로 버튼을 죽여 두지 않는 것은
     * 죽은 버튼이 이유를 말하지 못하기 때문이다 — 조건은 눌렀을 때 [runCompare]가 설명한다.
     */
    private fun renderSelection(count: Int) {
        if (_binding == null) return
        binding.selectionCount.text = if (count == 0) {
            getString(R.string.batch_enter_hint)
        } else {
            getString(R.string.batch_selected_count, count)
        }
        // 일괄 작업이 도는 동안은 둘 다 막는다 — 선택이 한 벌이 된 뒤로 '비교하기'로 화면을 뜨면
        // 진행 중인 작업의 결과 고지(유실·스킵 경고)를 받을 자리가 사라진다.
        val idle = batchViewModel.isProcessing.value != true
        binding.btnSelectionCompare.isEnabled = count > 0 && idle
        binding.btnSelectionAction.isEnabled = count > 0 && idle
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectionBackCallback.isEnabled = false
        batchViewModel.deselectAll()
        adapter.resetState()
        binding.selectionBar.visibility = View.GONE
        binding.actionRow.visibility = View.VISIBLE
        binding.fabAddCharacter.visibility = View.VISIBLE
        setFilterSortBarVisible(true)
        updateBirthdayBannerVisibility(true)
    }

    private fun observeBatchEdit() {
        batchViewModel.selectedIds.observe(viewLifecycleOwner) { ids ->
            if (isSelectionMode) {
                adapter.setSelectedIds(ids)
                renderSelection(ids.size)
            }
        }

        batchViewModel.isProcessing.observe(viewLifecycleOwner) { processing ->
            if (isSelectionMode) {
                val enabled = !processing && batchViewModel.selectedCount > 0
                binding.btnSelectionCompare.isEnabled = enabled
                binding.btnSelectionAction.isEnabled = enabled
            }
        }

        batchViewModel.operationResult.observe(viewLifecycleOwner) { result ->
            if (result == null || _binding == null) return@observe

            when (result) {
                is BatchOperationResult.Success -> {
                    val opLabel = getOperationLabel(result.operation)
                    // 여러 고지가 동시에 날 수 있다(예: 작품 이동에서 필드 제거 + 동기화 실패, 필드값 일괄에서 제외).
                    // 상호배타 분기 대신 해당되는 모든 경고를 합쳐 고지한다 — 어떤 유실·제외도 다른 경고에 가려지지 않게(변수 제어).
                    val parts = mutableListOf(getString(R.string.batch_success_format, result.affectedCount, opLabel))
                    if (result.move.hasRemoval) {
                        parts.add(getString(R.string.batch_note_moved_removed, result.move.removedValues, result.move.removedMemberships))
                    }
                    // 보관분도 말한다 (B-128). 유실은 아니지만 **이 캐릭터는 이제 세계관 안에 있어**
                    // 그 값이 화면 어디에도 그려지지 않는다 — 말하지 않으면 '일일이 확인하지 않으면
                    // 존재를 알 수 없는 데이터'가 된다(원칙 04). 이 자리가 여러 고지를 합쳐 내도록
                    // 만들어져 있는 이유가 정확히 이것이다.
                    if (result.move.keptGlobalValues > 0) {
                        parts.add(getString(R.string.batch_note_moved_kept, result.move.keptGlobalValues))
                    }
                    if (result.syncFailures > 0) {
                        parts.add(getString(R.string.batch_note_sync_failed, result.syncFailures))
                    }
                    if (result.skipped > 0) {
                        parts.add(getString(R.string.batch_note_skipped, result.skipped))
                    }
                    val hasNotice = result.move.hasRemoval || result.move.keptGlobalValues > 0 ||
                        result.syncFailures > 0 || result.skipped > 0
                    Snackbar.make(
                        binding.root, parts.joinToString(" · "),
                        if (hasNotice) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
                    ).show()

                    // 작업 이력 로그도 동일하게 합산 — 동기화 실패가 있으면 실패(주의)로, 그 외 제거/제외는 성공으로 기록.
                    val detailParts = mutableListOf<String>()
                    if (result.move.hasRemoval) {
                        detailParts.add(getString(R.string.result_batch_move_detail, result.move.remappedValues, result.move.removedValues, result.move.removedMemberships))
                    } else if (result.move.remappedValues > 0) {
                        detailParts.add(getString(R.string.result_batch_move_remapped, result.move.remappedValues))
                    }
                    if (result.move.keptGlobalValues > 0) {
                        detailParts.add(getString(R.string.batch_note_moved_kept, result.move.keptGlobalValues))
                    }
                    if (result.syncFailures > 0) detailParts.add(getString(R.string.result_batch_sync_warning, result.syncFailures))
                    if (result.skipped > 0) detailParts.add(getString(R.string.batch_note_skipped, result.skipped))
                    val summary = getString(R.string.result_batch_summary, opLabel, result.affectedCount)
                    val detail = detailParts.joinToString(" / ").ifBlank { null }
                    if (result.syncFailures > 0) {
                        logOperation(OpResult.failure(OpResult.CAT_BATCH, summary, detail))
                    } else {
                        logOperation(OpResult.success(OpResult.CAT_BATCH, summary, detail))
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
        "addStateChange" -> getString(R.string.batch_op_result_state_change)
        "delete" -> getString(R.string.batch_op_result_delete)
        else -> operation
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 선택 집합 자체는 activity 스코프 ViewModel이 들고 있어 회전에 그대로 살아남는다 —
        // 여기 실을 것은 모드뿐이다(P2-14의 '조용한 선택 유실 방지'는 그대로 지켜진다).
        outState.putBoolean("isSelectionMode", isSelectionMode)
    }

    private var lastListEmpty = false

    /** 작품 id → 제목 캐시 (활성 작품 필터 칩 라벨용). allNovels 관측 시 갱신. */
    private var novelTitles: Map<Long, String> = emptyMap()

    private fun observeData() {
        // 화면 진입 시 1회만 이미지 인덱스를 재추첨한다. P2-1이 매 emission 초기화를 제거하면서
        // 재추첨 경로가 아예 사라져 '랜덤 표시'가 최초 1회 추첨 후 영구 고정으로 퇴화했다(실기기 보고).
        // 진입 1회 재추첨은 P2-1의 취지(방출마다 전 목록 재랜덤·O(N) 쓰기 금지)를 지키면서
        // 방문할 때마다 다른 이미지가 나오게 한다. 재배정·저장은 실제로 bind되는 행에서만 일어난다.
        adapter.refreshRandomImages()
        // 대표 추첨의 가중치(B-104 ⓑ)도 진입 1회다 — 무게가 목록 도중에 바뀌면 같은 시드가
        // 다른 그림을 골라 손대지 않았는데 썸네일이 흔들린다. 어댑터가 무게를 받을 때 시드도
        // 함께 새로 뽑아 **한 번의 갱신**으로 합친다.
        viewModel.loadRepresentativeWeights()
        viewModel.representativeWeights.observe(viewLifecycleOwner) { adapter.setRepresentativeWeights(it) }
        viewModel.searchResults.observe(viewLifecycleOwner) { characters ->
            // 리스트 갱신마다 이미지 인덱스를 초기화하지 않는다(P2-1). 매 emission(핀·필터·정렬·검색)마다
            // clearAll하면 영속 인덱스가 무력화되고 전 캐릭터 썸네일이 재랜덤·O(N) 메인스레드 쓰기가 발생했다.
            // 인덱스는 bind에서 없을 때만 배정·저장되며, 표시는 `idx % paths.size`로 항상 안전하다.
            // 재추첨은 화면 진입 1회(위)만 — 여기서 다시 부르면 P2-1 회귀다.
            adapter.submitList(characters)
            lastListEmpty = characters.isEmpty()
            updateEmptyState()
            // 대결 점수표는 정렬이 실제로 돌 때 만들어진다 — 결과가 나온 뒤에야 셀 것이 생긴다(B-117).
            updateDuelSortNotice()
        }

        // 정렬/필터/프리셋 상태 관측
        viewModel.sortSpec.observe(viewLifecycleOwner) { updateSortChip(it) }
        viewModel.fieldFilters.observe(viewLifecycleOwner) { renderFilterChips(); updateEmptyState() }
        viewModel.tagFilters.observe(viewLifecycleOwner) { renderFilterChips(); updateEmptyState() }
        viewModel.novelFilters.observe(viewLifecycleOwner) { renderFilterChips(); updateEmptyState() }
        // 작품 id→제목 해석용 + 삭제된 작품 id를 저장 필터에서 정리(스틱 빈 목록 방지).
        viewModel.allNovels.observe(viewLifecycleOwner) { novels ->
            novelTitles = novels.associate { it.id to it.title }
            // 푸시 상태 툴바는 뒤로가기 + **작품명**을 보여준다 (N-1 §7-2) — 어느 작품의
            // 목록인지 제목이 말하지 않으면 정적 "캐릭터"만 남아 스코프를 알 수 없다.
            if (novelId != -1L) {
                binding.toolbar.title = novelTitles[novelId] ?: getString(R.string.tab_characters)
            }
            val active = viewModel.novelFilters.value ?: emptySet()
            if (novels.isNotEmpty() && active.isNotEmpty()) {
                // "작품 미배정" sentinel은 실제 작품이 아니므로 정리에서 보존
                val existing = novels.mapTo(HashSet()) { it.id }
                    .plus(com.novelcharacter.app.util.UnassignedFilter.NO_NOVEL_ID)
                val pruned = active.filter { it in existing }.toSet()
                if (pruned.size != active.size) viewModel.setNovelFilters(pruned)
            }
            renderFilterChips()
        }
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

    // ===== 통합 목록 컨트롤 (정렬·필터·프리셋) =====

    private fun setupFilterSort() {
        binding.btnListControls.setOnClickListener { openControlsSheet() }
        binding.btnClearFilters.setOnClickListener { viewModel.clearAllFilters() }
    }

    /** 정렬·필터·프리셋을 한 시트에서 — 기존 두 시트의 콜백 합집합, ViewModel 무수정. */
    private fun openControlsSheet() {
        val sheet = CharacterListControlsBottomSheet()
        sheet.currentSort = viewModel.sortSpec.value ?: CharacterSort()
        sheet.loadSortableFields = { viewModel.getSortableFields() }
        sheet.loadSortableDuelAxes = { viewModel.getSortableDuelAxes() }
        sheet.currentTags = viewModel.tagFilters.value ?: emptySet()
        sheet.currentNovelIds = viewModel.novelFilters.value ?: emptySet()
        // 작품 필터는 전역 목록에서만 의미(이미 한 작품에 스코프된 화면에선 중복이라 숨김).
        sheet.showNovelSection = (novelId == -1L)
        sheet.loadAllTags = { viewModel.getAllDistinctTags() }
        sheet.loadNovels = { viewModel.getAllNovelsList() }
        sheet.loadUniverses = { viewModel.getScopedUniverses() }
        sheet.loadFields = { uid -> viewModel.getFilterableFields(uid) }
        sheet.loadFieldValues = { fid -> viewModel.getFieldValues(fid) }
        sheet.onApplyAll = { sort, tags, novelIds, filter ->
            viewModel.setSortSpec(sort)
            viewModel.setTagFilters(tags)
            viewModel.setNovelFilters(novelIds)
            if (filter != null) viewModel.addFieldFilter(filter)
        }
        sheet.onClearAllFilters = { viewModel.clearAllFilters() }
        sheet.presetsLive = viewModel.presets
        sheet.onApplyPreset = { viewModel.applyPreset(it) }
        sheet.onPresetLongPress = { showPresetOptionsDialog(it) }
        sheet.onSavePreset = { showSavePresetDialog() }
        sheet.show(childFragmentManager, CharacterListControlsBottomSheet.TAG)
    }

    /** 정렬 상태 칩: "이름 ↑" — 탭=방향 반전(기존 방향 버튼 승계), 수동 정렬은 숨김. */
    private fun updateSortChip(sort: CharacterSort) {
        if (_binding == null) return
        val isManual = sort.kind == CharacterListPreset.SORT_MANUAL
        binding.sortChip.visibility = if (isManual) View.GONE else View.VISIBLE
        binding.sortChip.setOnClickListener {
            val cur = viewModel.sortSpec.value ?: CharacterSort()
            if (cur.kind != CharacterListPreset.SORT_MANUAL) {
                viewModel.setSortSpec(cur.copy(ascending = !cur.ascending))
            }
        }
        updateControlsButtonLabel()
        updateDuelSortNotice()
        if (isManual) return
        val arrow = if (sort.ascending) "↑" else "↓"
        val staticLabel = when (sort.kind) {
            CharacterListPreset.SORT_NAME -> getString(R.string.sort_label_name)
            CharacterListPreset.SORT_CREATED -> getString(R.string.sort_label_created)
            CharacterListPreset.SORT_RECENT -> getString(R.string.sort_label_recent)
            else -> null  // 필드 정렬: 필드명은 비동기 조회
        }
        if (staticLabel != null) {
            binding.sortChip.text = "$staticLabel $arrow"
        } else if (sort.kind == CharacterListPreset.SORT_DUEL) {
            // 축 이름도 비동기 조회다. **못 찾으면 코드를 그대로 보인다** — 지워진 축을
            // 가리키는 프리셋에서 칩이 '기본'이라 말하면 정렬이 먹은 것처럼 읽힌다.
            viewLifecycleOwner.lifecycleScope.launch {
                val name = viewModel.getSortableDuelAxes().firstOrNull { it.code == sort.duelAxisCode }?.name
                    ?: sort.duelAxisCode ?: getString(R.string.sort_label_manual)
                if (_binding != null) {
                    binding.sortChip.text = getString(R.string.sort_label_duel, name) + " $arrow"
                }
            }
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val name = viewModel.getSortableFields().firstOrNull { it.key == sort.fieldKey }?.name
                    ?: sort.fieldKey ?: getString(R.string.sort_label_manual)
                if (_binding != null) binding.sortChip.text = "$name $arrow"
            }
        }
    }

    /**
     * 대결 점수 정렬의 고지 (B-117) — 점수가 없어 맨 뒤로 간 인원과 점수에서 빠진 판.
     *
     * **목록이 갱신된 뒤에 읽는다.** 점수표는 정렬이 실제로 돌 때 만들어지므로, 정렬을 고른
     * 직후가 아니라 결과가 나온 뒤에야 셀 것이 생긴다.
     */
    private fun updateDuelSortNotice() {
        val b = _binding ?: return
        val notice = viewModel.duelSortNotice()
        val text = when (notice) {
            null -> null
            // 축이 사라졌다 — 목록이 기본 순서인 이유를 말해야 한다. 조용히 두면 사용자는
            // 정렬이 먹은 줄 알고 목록이 왜 안 바뀌는지 알 길이 없다(개발 의도 2번).
            is CharacterViewModel.DuelSortNotice.AxisMissing ->
                getString(R.string.sort_duel_notice_axis_missing)
            is CharacterViewModel.DuelSortNotice.Scored -> {
                val s = notice.scores
                when {
                    s.unplayed == 0 && s.orphanMatches == 0 -> null
                    s.orphanMatches > 0 ->
                        getString(R.string.sort_duel_notice_missing, s.unplayed, s.orphanMatches)
                    else -> getString(R.string.sort_duel_notice, s.unplayed)
                }
            }
        }
        b.duelSortNotice.text = text.orEmpty()
        b.duelSortNotice.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** 컨트롤 버튼 라벨에 활성 상태 수 표기: "정렬·필터 · N" (필터 수 + 비수동 정렬 1). */
    private fun updateControlsButtonLabel() {
        if (_binding == null) return
        val filterCount = (if (novelId == -1L) (viewModel.novelFilters.value?.size ?: 0) else 0) +
            (viewModel.tagFilters.value?.size ?: 0) +
            (viewModel.fieldFilters.value?.size ?: 0)
        val sortActive = (viewModel.sortSpec.value?.kind ?: CharacterListPreset.SORT_MANUAL) != CharacterListPreset.SORT_MANUAL
        val n = filterCount + if (sortActive) 1 else 0
        binding.btnListControls.text =
            if (n > 0) getString(R.string.controls_button_count, n) else getString(R.string.controls_button)
    }

    private fun renderFilterChips() {
        if (_binding == null) return
        binding.filterChipGroup.removeAllViews()
        updateControlsButtonLabel()
        val ctx = context ?: return
        // 작품 필터는 전역 목록(novelId==-1)에서만 적용/표시(스코프 화면에선 무력화되므로 칩도 숨김).
        val novelFilterIds = if (novelId == -1L) (viewModel.novelFilters.value ?: emptySet()) else emptySet()
        for (id in novelFilterIds) {
            // "작품 미배정" sentinel은 novelTitles에 없으므로 라벨 분기 — ?: continue가 무음 드롭하지 않게
            val title = if (id == com.novelcharacter.app.util.UnassignedFilter.NO_NOVEL_ID) {
                getString(R.string.stats_no_novel_assigned)
            } else {
                novelTitles[id] ?: continue  // 아직 로드 전이거나 삭제된 작품 — 관측 후 재렌더/정리됨
            }
            binding.filterChipGroup.addView(Chip(ctx).apply {
                text = getString(R.string.filter_chip_novel_format, title)
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    viewModel.setNovelFilters((viewModel.novelFilters.value ?: emptySet()) - id)
                }
            })
        }
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
                setOnCloseIconClickListener { viewModel.removeFieldFilter(f) }
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
        // R-27: 종전에는 이름을 비운 채 누르면 **아무 말도 없이** 창이 닫혔다(B-76).
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.character_preset_save)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                input.showInlineError(getString(R.string.preset_name_required))
                return@setValidatedPositiveButton false
            }
            viewModel.saveAsPreset(name)
            true
        }
        dialog.show()
    }

    private fun showPresetOptionsDialog(preset: CharacterListPreset) {
        val ctx = requireContext()
        val options = arrayOf(
            getString(R.string.character_preset_apply),
            getString(R.string.preset_edit_name),
            getString(R.string.character_preset_save),   // 현재 상태로 갱신
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(preset.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.applyPreset(preset)
                    1 -> showRenamePresetDialog(preset)
                    2 -> viewModel.overwritePreset(preset)
                    3 -> MaterialAlertDialogBuilder(ctx)
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
        // R-27: 같은 부류다 — 비운 채 누르면 이름이 사라진 것처럼 보이고 창은 닫혔다(B-76).
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.preset_edit_name)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                input.showInlineError(getString(R.string.preset_name_required))
                return@setValidatedPositiveButton false
            }
            viewModel.renamePreset(preset, name)
            true
        }
        dialog.show()
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
        // 선택은 activity 스코프라 화면 이탈 후에도 남는다 → 회전이 아닌 실제 이탈이면 비워
        // 다른(다른 작품) 목록에서 안 보이는 캐릭터가 선택된 채 일괄 작업/삭제되는 것을 막는다.
        if (isSelectionMode && activity?.isChangingConfigurations != true) {
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
        // 비교 인원 경계. 하한은 비교 화면이 스스로 거는 것과 같다(2명 미만이면 되돌아 나간다).
        // 상한은 가독성 판정이다 — 비교 표는 열 수가 고정돼 있지 않아 넷도 그려지지만 좁아진다.
        // 종전 비교 모드가 4번째 선택을 막던 그 값을 그대로 옮겨 왔다(문구 max_compare_limit와 한 벌).
        private const val MIN_COMPARE = 2
        private const val MAX_COMPARE = 3

        // 선택 바 ⋮ 오버플로 메뉴 항목 id
        private const val MENU_SELECT_ALL = 1
        private const val MENU_DESELECT_ALL = 2
        private const val MENU_FILTER_SELECT = 3

        // 액션 행 ⋮ 통합 메뉴 항목 id (N-1)
        private const val MENU_SUPPLEMENT = 11
        private const val MENU_COMPARE = 12
        private const val MENU_BATCH_EDIT = 13
        private const val MENU_REORDER = 14
    }
}
