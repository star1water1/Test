package com.novelcharacter.app.ui.namebank

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.databinding.FragmentNameBankBinding
import com.novelcharacter.app.ui.adapter.NameBankAdapter
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.setValidatedPositiveButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NameBankFragment : Fragment() {

    private var _binding: FragmentNameBankBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NameBankViewModel by viewModels()
    private lateinit var adapter: NameBankAdapter

    // 일괄 캐릭터 등록용 선택 모드 (FieldValueListFragment 선택 패턴).
    // 선택 상태(selectionMode·selectedIds)는 회전 생존을 위해 ViewModel이 보관한다.
    private val selectionMode get() = viewModel.selectionMode
    private val selectedIds get() = viewModel.selectedIds
    private var backCallback: androidx.activity.OnBackPressedCallback? = null
    private var displayedEntries: List<NameBankEntry> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNameBankBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 푸시 목적지(대시보드·어시스턴트 진입) — 업 버튼은 디스패처 경유
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            if (selectionMode) exitSelectionMode()
            else requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        // 시스템 뒤로가기도 선택 모드를 먼저 해제 — 선택 중 화면 이탈로 선택이 유실되지 않게
        backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitSelectionMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback!!)
        binding.toolbar.inflateMenu(R.menu.menu_name_bank)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select -> { enterSelectionMode(null); true }
                R.id.action_select_all -> { selectAllDisplayed(); true }
                R.id.action_bulk_register -> { openBulkRegisterSheet(); true }
                R.id.action_ai_name -> { openNameSuggestSheet(); true }
                else -> false
            }
        }
        setupRecyclerView()
        setupSearch()
        setupFilter()
        setupFab()
        // 회전 복원 — VM에 선택 모드가 살아 있으면 UI를 그 상태로 되돌린다
        if (viewModel.selectionMode) restoreSelectionUi()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = NameBankAdapter(
            onClick = { entry -> showEditDialog(entry) },
            onLongClick = { entry -> showOptionsDialog(entry) },
            onToggleSelect = { entry -> toggleSelection(entry) },
            onOpenCharacter = { characterId -> openCharacter(characterId) }
        )
        binding.nameBankRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.nameBankRecyclerView.adapter = adapter
    }

    // ===== 선택 모드 / 일괄 캐릭터 등록 =====

    private fun enterSelectionMode(initial: NameBankEntry?) {
        viewModel.selectionMode = true
        selectedIds.clear()
        initial?.let { selectedIds.add(it.id) }
        restoreSelectionUi()
    }

    /** 선택 모드 UI 렌더 — 신규 진입과 회전 복원이 공용 (메뉴 inflate 이후에만 호출) */
    private fun restoreSelectionUi() {
        backCallback?.isEnabled = true
        binding.toolbar.menu.findItem(R.id.action_bulk_register)?.isVisible = true
        binding.toolbar.menu.findItem(R.id.action_select_all)?.isVisible = true
        binding.toolbar.menu.findItem(R.id.action_select)?.isVisible = false
        updateSelectionTitle()
        adapter.setSelectionState(true, selectedIds.toSet())
    }

    private fun exitSelectionMode() {
        viewModel.selectionMode = false
        selectedIds.clear()
        backCallback?.isEnabled = false
        binding.toolbar.menu.findItem(R.id.action_bulk_register)?.isVisible = false
        binding.toolbar.menu.findItem(R.id.action_select_all)?.isVisible = false
        binding.toolbar.menu.findItem(R.id.action_select)?.isVisible = true
        binding.toolbar.title = getString(R.string.tab_name_bank)
        adapter.setSelectionState(false, emptySet())
    }

    private fun toggleSelection(entry: NameBankEntry) {
        if (entry.id in selectedIds) selectedIds.remove(entry.id) else selectedIds.add(entry.id)
        updateSelectionTitle()
        adapter.setSelectionState(true, selectedIds.toSet())
    }

    private fun selectAllDisplayed() {
        // 표시 중 항목을 추가한다(교체 아님) — 검색을 넘나드는 누적 선택과 일관
        displayedEntries.forEach { selectedIds.add(it.id) }
        updateSelectionTitle()
        adapter.setSelectionState(true, selectedIds.toSet())
    }

    private fun updateSelectionTitle() {
        val hidden = selectedIds.count { id -> displayedEntries.none { it.id == id } }
        binding.toolbar.title = if (hidden > 0) {
            getString(R.string.name_bank_selected_count_hidden, selectedIds.size, hidden)
        } else {
            getString(R.string.name_bank_selected_count, selectedIds.size)
        }
    }

    /**
     * 일괄 등록 시트 — 선택 모드의 여러 건과 **롱클릭의 단건**이 같은 경로를 탄다.
     *
     * 설계 8-2 ⓒ가 *"기존 일괄 등록 계획기의 단건판 — 새 기계 없음"*이라 적은 자리다.
     * 갈라 두면 중복 정책·성별 기록·결과 집계가 두 벌이 되고, 그 둘은 반드시 어긋난다.
     */
    private fun openBulkRegisterSheet(ids: List<Long> = selectedIds.toList()) {
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), R.string.name_bank_bulk_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = viewModel.getEntriesByIds(ids)
            val novels = viewModel.getAllNovelsList()
            val existingNames = viewModel.getExistingCharacterNames()
            if (_binding == null || !isAdded) return@launch
            // 백그라운드 전환 등으로 상태 저장 후면 show()가 IllegalStateException —
            // 생략해도 선택은 VM에 생존하므로 재진입 1탭으로 재시도 가능
            if (parentFragmentManager.isStateSaved) return@launch
            val (vsExisting, withinSelection) = BulkRegisterPlanner.countCollisions(entries, existingNames)
            val sheet = BulkRegisterBottomSheet()
            sheet.setup = BulkRegisterBottomSheet.Setup(
                count = entries.size,
                novels = novels,
                collisionsVsExisting = vsExisting,
                collisionsWithinSelection = withinSelection,
                usedCount = entries.count { it.isUsed }
            )
            sheet.onConfirm = { novelId, mapGender, includeOriginNotes, policy ->
                viewModel.bulkRegister(ids, novelId, mapGender, includeOriginNotes, policy)
                if (selectionMode) exitSelectionMode()
            }
            sheet.show(parentFragmentManager, BulkRegisterBottomSheet.TAG)
        }
    }

    /**
     * [AI로 이름 만들기] — 캐릭터 없이 세계관의 결로만 이름을 짓는다 (B-123 · 설계 8-2 ⓒ).
     *
     * **B-124가 일부러 남긴 한 항목이 여기서 닫힌다** — 그때는 열 시트가 없어 진입점만 달면
     * *고를 수는 있는데 아무 일도 안 일어나는 자리*가 됐을 것이라 미뤘다.
     *
     * 세계관을 먼저 묻는 이유는 견본 때문이다: 은행은 전역이라 스코프 칸이 없고(설계 8-3 —
     * v1 밖), 어느 작품의 결로 지을지는 사용자만 안다. **세계관이 없어도 막지 않는다** —
     * 견본 없이 가는 길을 남겨 두지 않으면 세계관을 아직 안 만든 사용자가 이 기능을 못 쓴다.
     */
    private fun openNameSuggestSheet() {
        // 미설정 안내는 편집 폼의 ✨과 **같은 함수**다(시트가 든다) — 같은 기능이 들어온 문에
        // 따라 다르게 말하지 않는다.
        if (!NameSuggestSheet.guardProvider(this)) return
        viewLifecycleOwner.lifecycleScope.launch {
            val universes = viewModel.getAllUniversesList()
            if (!isAdded || _binding == null) return@launch
            if (universes.isEmpty()) {
                showNameSuggestSheet(null)
                return@launch
            }
            val labels = (universes.map { it.name } + getString(R.string.name_bank_ai_no_universe))
                .toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.name_bank_ai_pick_universe)
                .setItems(labels) { _, which ->
                    showNameSuggestSheet(universes.getOrNull(which))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showNameSuggestSheet(universe: com.novelcharacter.app.data.model.Universe?) {
        if (parentFragmentManager.isStateSaved) return
        val sheet = NameSuggestSheet()
        sheet.setup(
            NameSuggestViewModel.Setup(
                // 캐릭터가 없다 — 적용할 폼도 없으므로 시트가 [적용]을 세우지 않는다.
                character = null,
                universeId = universe?.id,
                universeName = universe?.name.orEmpty(),
                title = universe?.name.orEmpty()
            )
        )
        sheet.show(parentFragmentManager, NameSuggestSheet.TAG)
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
    }

    private fun setupFilter() {
        // 저장된 필터 상태 복원
        binding.chipAvailableOnly.isChecked = viewModel.isShowOnlyAvailable()
        binding.chipAvailableOnly.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowOnlyAvailable(isChecked)
        }

        // 정렬 — 필터(미사용만)·검색만 있고 정렬이 없던 짝 격차를 메운다
        updateSortChip()
        binding.chipSort.setOnClickListener { anchor ->
            val modes = listOf(
                NameBankViewModel.SORT_RECENT to R.string.sort_recent_added,
                NameBankViewModel.SORT_NAME to R.string.sort_name
            )
            android.widget.PopupMenu(requireContext(), anchor).apply {
                modes.forEachIndexed { i, pair -> menu.add(0, i, i, pair.second) }
                setOnMenuItemClickListener { item ->
                    viewModel.setSortMode(modes[item.itemId].first)
                    updateSortChip()
                    true
                }
                show()
            }
        }
    }

    private fun updateSortChip() {
        val labelRes = if (viewModel.getSortMode() == NameBankViewModel.SORT_NAME) {
            R.string.sort_name
        } else {
            R.string.sort_recent_added
        }
        binding.chipSort.text = getString(R.string.sort_chip_format, getString(labelRes))
    }

    private fun setupFab() {
        binding.fabAddName.setOnClickListener {
            showEditDialog(null)
        }
    }

    private fun observeData() {
        viewModel.displayedNames.observe(viewLifecycleOwner) { names ->
            displayedEntries = names
            adapter.submitList(names)
            binding.emptyText.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
            // 검색·필터를 넘나드는 누적 선택 허용 — 화면 밖 선택은 지우지 않고
            // 타이틀에 "(화면 밖 N)"으로 상시 고지한다 (숨은 데이터 금지, 원칙 04)
            if (selectionMode) {
                updateSelectionTitle()
                adapter.setSelectionState(true, selectedIds.toSet())
            }
        }

        // 사용 캐릭터 이름표 (B-124 ⓒ) — 링크가 바뀌면 함께 바뀐다
        viewModel.usedByNames.observe(viewLifecycleOwner) { adapter.setUsedByNames(it) }

        // 삭제된 엔트리만 선택에서 정리 — 은행 전체 기준 (표시 필터와 무관)
        viewModel.allEntries.observe(viewLifecycleOwner) { all ->
            if (!selectionMode) return@observe
            val existing = all.mapTo(HashSet()) { it.id }
            if (selectedIds.retainAll(existing)) {
                updateSelectionTitle()
                adapter.setSelectionState(true, selectedIds.toSet())
            }
        }

        // 데이터 처리 결과 알림 (이름 추가/수정/삭제·사용처리/해제 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }
    }

    private fun showEditDialog(existing: NameBankEntry?) {
        val context = requireContext()
        val layout = LayoutInflater.from(context).inflate(R.layout.dialog_name_bank_edit, null)
        val editName = layout.findViewById<TextInputEditText>(R.id.editNameBank)
        val editGender = layout.findViewById<MaterialAutoCompleteTextView>(R.id.editGender)
        val editOrigin = layout.findViewById<TextInputEditText>(R.id.editOrigin)
        val editNotes = layout.findViewById<TextInputEditText>(R.id.editNotes)

        // 성별은 고정 3옵션 스피너였다 (B-124 ⓓ) — 창작자가 정하는 축을 셋으로 닫아 둔 자리라
        // 열린 구조(원칙 01)에 어긋났다. 기본 둘은 제안으로 남기고, **은행이 이미 쓰고 있는 값**을
        // 함께 올린다(사용자가 만든 어휘가 다음에도 한 번에 나온다).
        val defaultGenders = listOf(getString(R.string.gender_male), getString(R.string.gender_female))
        editGender.setSimpleItems(defaultGenders.toTypedArray())
        viewLifecycleOwner.lifecycleScope.launch {
            val used = viewModel.getGenderSuggestions()
            if (!isAdded) return@launch
            editGender.setSimpleItems((defaultGenders + used).distinct().toTypedArray())
        }

        if (existing != null) {
            editName.setText(existing.name)
            editGender.setText(existing.gender, false)
            editOrigin.setText(existing.origin)
            editNotes.setText(existing.notes)
        }

        // 검증 실패 시 다이얼로그를 닫지 않는다 (입력 유실 방지)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing != null) getString(R.string.edit_name_title) else getString(R.string.add_name_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setValidatedPositiveButton {
            val name = editName.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(context, R.string.enter_name, Toast.LENGTH_SHORT).show()
                return@setValidatedPositiveButton false
            }
            val gender = editGender.text.toString().trim()
            val origin = editOrigin.text.toString().trim()
            val notes = editNotes.text.toString().trim()

            if (existing != null) {
                viewModel.update(existing.copy(
                    name = name, gender = gender, origin = origin, notes = notes
                ))
            } else {
                viewModel.insert(NameBankEntry(
                    name = name, gender = gender, origin = origin, notes = notes
                ))
            }
            true
        }
        dialog.show()
    }

    private fun showOptionsDialog(entry: NameBankEntry) {
        val editStr = getString(R.string.edit)
        val deleteStr = getString(R.string.delete)
        val markAvailableStr = getString(R.string.mark_as_available)
        val markUsedStr = getString(R.string.name_bank_mark_used)
        val createCharacterStr = getString(R.string.name_bank_create_character)
        val openCharacterStr = getString(R.string.name_bank_open_character)
        val selectModeStr = getString(R.string.name_bank_select_mode)
        val options = mutableListOf(editStr, deleteStr)
        if (entry.isUsed) {
            options.add(markAvailableStr)
            // 목록 줄 탭으로도 가지만 롱클릭 메뉴에도 둔다 — 줄에 이름표가 없는 상태
            // (캐릭터를 못 찾는 경우)에서는 탭할 자리가 없다.
            if (entry.usedByCharacterId != null) options.add(openCharacterStr)
        } else {
            // 죽어 있던 `markAsUsed`가 여기서 UI 진입점을 얻는다 (B-124 ⓒ·ⓓ).
            options.add(markUsedStr)
            options.add(createCharacterStr)
        }
        options.add(selectModeStr)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(entry.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    editStr -> showEditDialog(entry)
                    deleteStr -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage(getString(R.string.confirm_delete_name, entry.name))
                            .setPositiveButton(R.string.yes) { _, _ -> viewModel.delete(entry) }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                    markAvailableStr -> viewModel.markAsAvailable(entry.id)
                    markUsedStr -> showMarkUsedPicker(entry)
                    createCharacterStr -> openBulkRegisterSheet(listOf(entry.id))
                    openCharacterStr -> entry.usedByCharacterId?.let { openCharacter(it) }
                    selectModeStr -> enterSelectionMode(entry)
                }
            }
            .show()
    }

    /**
     * [사용 처리] — 이 이름을 쓰는 캐릭터를 고른다 (B-124 ⓒ).
     *
     * `markAsUsed` API와 결과 문구는 처음부터 있었고 **진입점만 없었다**(감사 문서 등재분).
     *
     * **고르는 창은 `EntityPickerBottomSheet`를 그대로 쓴다** — 그 파일 머리가 이미
     * *"수백 명 캐릭터 스케일 대응(`AlertDialog.setItems` 선례는 검색이 없어 부적합)"*이라
     * 판정해 뒀고, 목표 규모의 캐릭터는 **6,420명**이다(`scalability_performance` 2장).
     * 첫 구현이 `setItems`였다가 콜드 검토에서 바꿨다 — 저장소가 이미 내린 판정을
     * 되돌리는 자리였다. 유형은 캐릭터로 못 박는다(`lockedType`).
     */
    private fun showMarkUsedPicker(entry: NameBankEntry) {
        if (parentFragmentManager.isStateSaved) return
        val sheet = com.novelcharacter.app.ui.image.EntityPickerBottomSheet()
        sheet.lockedType = com.novelcharacter.app.ui.image.ImageManagerViewModel.OwnerType.CHARACTER
        sheet.titleRes = R.string.name_bank_mark_used_pick
        sheet.loadTargets = { viewModel.getCharacterPickRows() }
        sheet.onPicked = { _, row -> viewModel.markAsUsed(entry.id, row.id) }
        sheet.show(parentFragmentManager, "namebank_mark_used")
    }

    /** 사용 캐릭터로 이동 — 지금까지 엑셀 시트에만 있던 연결을 화면으로 (B-124 ⓒ). */
    private fun openCharacter(characterId: Long) {
        val bundle = Bundle().apply { putLong("characterId", characterId) }
        findNavController().navigateSafe(R.id.nameBankFragment, R.id.characterDetailFragment, bundle)
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        binding.nameBankRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
