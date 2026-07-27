package com.novelcharacter.app.ui.namebank

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.databinding.FragmentNameBankBinding
import com.novelcharacter.app.ui.adapter.NameBankAdapter
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
            onToggleSelect = { entry -> toggleSelection(entry) }
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

    private fun openBulkRegisterSheet() {
        val ids = selectedIds.toList()
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
                exitSelectionMode()
            }
            sheet.show(parentFragmentManager, BulkRegisterBottomSheet.TAG)
        }
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
        val spinnerGender = layout.findViewById<Spinner>(R.id.spinnerGender)
        val editOrigin = layout.findViewById<TextInputEditText>(R.id.editOrigin)
        val editNotes = layout.findViewById<TextInputEditText>(R.id.editNotes)

        val genderOptions = listOf(getString(R.string.gender_unspecified), getString(R.string.gender_male), getString(R.string.gender_female))
        spinnerGender.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, genderOptions).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        if (existing != null) {
            editName.setText(existing.name)
            val genderIndex = genderOptions.indexOf(existing.gender)
            if (genderIndex >= 0) spinnerGender.setSelection(genderIndex)
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
            val gender = if (spinnerGender.selectedItemPosition > 0)
                genderOptions[spinnerGender.selectedItemPosition] else ""
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
        val selectModeStr = getString(R.string.name_bank_select_mode)
        val options = mutableListOf(editStr, deleteStr)
        if (entry.isUsed) {
            options.add(markAvailableStr)
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
                    selectModeStr -> enterSelectionMode(entry)
                }
            }
            .show()
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        binding.nameBankRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
