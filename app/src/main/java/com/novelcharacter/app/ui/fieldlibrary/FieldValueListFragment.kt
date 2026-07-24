package com.novelcharacter.app.ui.fieldlibrary

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.FieldValueLibraryConfig
import com.novelcharacter.app.data.repository.FieldValueLibraryRepository
import com.novelcharacter.app.databinding.FragmentFieldValueListBinding
import com.novelcharacter.app.databinding.ItemFieldValueEntryBinding
import com.novelcharacter.app.databinding.SheetFieldValueDetailBinding
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.setValidatedPositiveButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 한 필드의 값 카탈로그 화면 — 검색/정렬/필터, 직접 편집(라벨·별칭·분류·설명),
 * 이름 변경·병합·삭제(전파 확인), 사용처 드릴다운, 입력 모드 설정, AI 정리.
 */
class FieldValueListFragment : Fragment() {

    private var _binding: FragmentFieldValueListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FieldValueLibraryViewModel by viewModels()

    private var field: FieldDefinition? = null
    private var allEntries: List<FieldValueEntry> = emptyList()
    private lateinit var adapter: EntryAdapter

    private var searchQuery = ""
    private var sortMode = SORT_USAGE
    private var selectionMode = false
    private val selectedIds = linkedSetOf<Long>()
    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    private val prefs by lazy {
        requireContext().getSharedPreferences("field_library_ui_state", Context.MODE_PRIVATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFieldValueListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sortMode = prefs.getInt(KEY_SORT, SORT_USAGE)

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
        binding.toolbar.inflateMenu(R.menu.menu_field_value_list)
        binding.toolbar.setOnMenuItemClickListener { onMenuItem(it.itemId) }

        adapter = EntryAdapter()
        binding.entryRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.entryRecyclerView.adapter = adapter

        setupSearchAndFilters()

        val fieldDefId = arguments?.getLong("fieldDefinitionId") ?: -1L
        viewLifecycleOwner.lifecycleScope.launch {
            val fd = viewModel.getField(fieldDefId)
            if (fd == null) {
                requireActivity().onBackPressedDispatcher.onBackPressed()
                return@launch
            }
            field = fd
            binding.toolbar.title = fd.name
            binding.toolbar.subtitle = inputModeLabel(fd)
            // 진입 시 usageCount 최신화 (diff-only)
            viewModel.refreshUsage(fd.id)
            viewModel.entriesLive(fd.id).observe(viewLifecycleOwner) { entries ->
                allEntries = entries
                render()
            }
        }

        binding.fabAddValue.setOnClickListener { showAddDialog() }

        viewModel.result.observe(viewLifecycleOwner) { r ->
            r?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }
    }

    // ===== 목록 렌더 =====

    private fun setupSearchAndFilters() {
        var searchJob: Job? = null
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    searchQuery = s.toString()
                    render()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        listOf(binding.chipIncludeUnused, binding.chipIncludeHidden, binding.chipUncategorizedOnly)
            .forEach { chip -> chip.setOnCheckedChangeListener { _, _ -> render() } }
    }

    private fun render() {
        if (_binding == null) return
        var list = allEntries
        if (!binding.chipIncludeUnused.isChecked) list = list.filter { it.usageCount > 0 }
        if (!binding.chipIncludeHidden.isChecked) list = list.filter { !it.isHidden }
        if (binding.chipUncategorizedOnly.isChecked) list = list.filter { it.category.isBlank() }
        if (searchQuery.isNotBlank()) {
            list = list.filter { e ->
                e.value.contains(searchQuery, true) ||
                    e.displayLabel.contains(searchQuery, true) ||
                    e.category.contains(searchQuery, true) ||
                    e.aliases().any { it.contains(searchQuery, true) }
            }
        }
        list = when (sortMode) {
            SORT_NAME -> list.sortedBy { it.value }
            SORT_UPDATED -> list.sortedByDescending { it.updatedAt }
            else -> list.sortedWith(compareByDescending<FieldValueEntry> { it.usageCount }.thenBy { it.value })
        }
        adapter.submit(list)
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ===== 메뉴 =====

    private fun onMenuItem(id: Int): Boolean {
        val fd = field ?: return false
        when (id) {
            R.id.action_merge_selected -> startMergeFlow(fd)
            R.id.action_ai_organize -> AiOrganizeSheet.show(this, fd, viewModel)
            R.id.action_sort -> showSortDialog()
            R.id.action_input_mode -> showInputModeDialog(fd)
            R.id.action_prune_unused -> showPruneDialog(fd)
            else -> return false
        }
        return true
    }

    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.field_library_sort_usage),
            getString(R.string.field_library_sort_name),
            getString(R.string.field_library_sort_updated)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.field_library_menu_sort)
            .setSingleChoiceItems(labels, sortMode) { dialog, which ->
                sortMode = which
                prefs.edit().putInt(KEY_SORT, which).apply()
                render()
                dialog.dismiss()
            }
            .show()
    }

    private fun inputModeLabel(fd: FieldDefinition): String {
        return when (FieldValueLibraryConfig.fromConfig(fd.config).inputMode) {
            FieldValueLibraryConfig.MODE_FREE -> getString(R.string.field_library_input_mode_free)
            FieldValueLibraryConfig.MODE_RESTRICTED -> getString(R.string.field_library_input_mode_restricted)
            else -> getString(R.string.field_library_input_mode_suggest)
        }.substringBefore(" —")
    }

    private fun showInputModeDialog(fd: FieldDefinition) {
        val modes = FieldValueLibraryConfig.MODES
        val labels = arrayOf(
            getString(R.string.field_library_input_mode_suggest),
            getString(R.string.field_library_input_mode_free),
            getString(R.string.field_library_input_mode_restricted)
        )
        val current = modes.indexOf(FieldValueLibraryConfig.fromConfig(fd.config).inputMode)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.field_library_input_mode_title, fd.name))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val mode = modes[which]
                if (mode == FieldValueLibraryConfig.MODE_RESTRICTED && allEntries.isEmpty()) {
                    // 변수 제어: 허용 값 0개인 제한 모드는 모든 입력을 막는다 — 사유를 알리고 진행은 허용
                    notifyResult(com.novelcharacter.app.util.OpResult.failure(
                        com.novelcharacter.app.util.OpResult.CAT_FIELD_LIBRARY,
                        getString(R.string.field_library_restricted_no_values)))
                }
                viewModel.setInputMode(fd, mode) { updated ->
                    field = updated
                    if (_binding != null) binding.toolbar.subtitle = inputModeLabel(updated)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showPruneDialog(fd: FieldDefinition) {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = viewModel.countPrunable(fd)
            if (count == 0) {
                notifyResult(com.novelcharacter.app.util.OpResult.success(
                    com.novelcharacter.app.util.OpResult.CAT_FIELD_LIBRARY,
                    getString(R.string.field_library_prune_none)))
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.field_library_menu_prune)
                .setMessage(getString(R.string.field_library_prune_confirm, count))
                .setPositiveButton(R.string.delete) { _, _ -> viewModel.pruneUnused(fd) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ===== 값 추가 =====

    private fun showAddDialog() {
        val fd = field ?: return
        val input = TextInputEditText(requireContext()).apply {
            hint = getString(R.string.field_library_value_hint)
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.field_library_add_title, fd.name))
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val value = input.text.toString().trim()
            if (value.isEmpty()) return@setValidatedPositiveButton false
            // 비동기 검증: 중복이면 다이얼로그를 유지한 채 오류 표시, 성공 시에만 닫는다
            viewModel.addValue(fd, value,
                onAdded = { if (dialog.isShowing) dialog.dismiss() },
                onDuplicate = { message ->
                    if (dialog.isShowing) input.error = message
                    else notifyResult(com.novelcharacter.app.util.OpResult.failure(
                        com.novelcharacter.app.util.OpResult.CAT_FIELD_LIBRARY, message))
                })
            false
        }
        dialog.show()
    }

    // ===== 선택/병합 =====

    private fun enterSelectionMode(initial: FieldValueEntry) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(initial.id)
        backCallback?.isEnabled = true
        binding.toolbar.menu.findItem(R.id.action_merge_selected)?.isVisible = true
        binding.toolbar.title = getString(R.string.field_library_select_mode_hint)
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        backCallback?.isEnabled = false
        binding.toolbar.menu.findItem(R.id.action_merge_selected)?.isVisible = false
        binding.toolbar.title = field?.name ?: ""
        adapter.notifyDataSetChanged()
    }

    private fun startMergeFlow(fd: FieldDefinition) {
        val selected = allEntries.filter { it.id in selectedIds }
        if (selected.size < 2) return
        val labels = selected.map { "${it.value} (${getString(R.string.field_library_usage_count, it.usageCount)})" }
            .toTypedArray()
        var targetIndex = selected.indices.maxByOrNull { selected[it].usageCount } ?: 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.field_library_merge_target_title)
            .setSingleChoiceItems(labels, targetIndex) { _, which -> targetIndex = which }
            .setPositiveButton(R.string.field_library_menu_merge) { _, _ ->
                val target = selected[targetIndex]
                val sources = selected.filter { it.id != target.id }
                confirmMerge(fd, target, sources)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmMerge(fd: FieldDefinition, target: FieldValueEntry, sources: List<FieldValueEntry>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val tokens = sources.flatMap { listOf(it.value) + it.aliases() }.toSet()
            val preview = viewModel.previewPropagation(fd, tokens)
            val message = getString(
                R.string.field_library_merge_confirm, sources.size, target.value,
                getString(R.string.field_library_propagation_message,
                    preview.characterValues, preview.eventValues, preview.stateChanges)
            ) + joinNoticeIfMulti(fd)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.field_library_menu_merge)
                .setMessage(message)
                .setPositiveButton(R.string.field_library_menu_merge) { _, _ ->
                    viewModel.mergeValues(fd, target.id, sources.map { it.id })
                    exitSelectionMode()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun joinNoticeIfMulti(fd: FieldDefinition): String =
        if (com.novelcharacter.app.util.FieldValueTokenizer.isMultiToken(fd))
            getString(R.string.field_library_propagation_join_notice) else ""

    // ===== 상세 시트 =====

    private fun showDetailSheet(entry: FieldValueEntry) {
        val fd = field ?: return
        val sheet = BottomSheetDialog(requireContext())
        val sb = SheetFieldValueDetailBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)

        sb.valueTitle.text = entry.value
        sb.usageSummary.text = listOfNotNull(
            getString(R.string.field_library_usage_count, entry.usageCount),
            entry.source.takeIf { it != FieldValueEntry.SOURCE_AUTO },
            getString(R.string.field_library_meta_hidden).takeIf { entry.isHidden }
        ).joinToString(" · ")
        sb.editDisplayLabel.setText(entry.displayLabel)
        sb.editCategory.setText(entry.category)
        sb.editAliases.setText(entry.aliases().joinToString(", "))
        sb.editDescription.setText(entry.description)
        sb.switchHidden.isChecked = entry.isHidden

        // 카테고리 자동완성 — 같은 필드의 기존 카테고리 제안 (일관 분류 유도)
        val categories = allEntries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
        if (categories.isNotEmpty()) {
            sb.editCategory.setAdapter(android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_dropdown_item_1line, categories))
        }

        sb.btnSave.setOnClickListener {
            val aliases = sb.editAliases.text.toString().split(",")
                .map { it.trim() }.filter { it.isNotEmpty() }
            val updated = entry.copy(
                displayLabel = sb.editDisplayLabel.text.toString().trim(),
                category = sb.editCategory.text.toString().trim(),
                description = sb.editDescription.text.toString().trim(),
                aliasesJson = FieldValueEntry.aliasesToJson(aliases),
                isHidden = sb.switchHidden.isChecked
            )
            // 별칭 충돌은 시트를 유지한 채 입력 필드에 표시 — 편집 내용이 조용히 유실되지 않게 한다
            viewModel.saveEntry(updated,
                onSaved = { if (sheet.isShowing) sheet.dismiss() },
                onAliasConflict = { message ->
                    if (sheet.isShowing) {
                        sb.editAliases.error = message
                    } else {
                        notifyResult(com.novelcharacter.app.util.OpResult.failure(
                            com.novelcharacter.app.util.OpResult.CAT_FIELD_LIBRARY, message))
                    }
                })
        }
        sb.btnRename.setOnClickListener {
            sheet.dismiss()
            showRenameDialog(fd, entry)
        }
        sb.btnUsage.setOnClickListener {
            sheet.dismiss()
            showUsageDialog(fd, entry)
        }
        sb.btnDelete.setOnClickListener {
            sheet.dismiss()
            showDeleteDialog(fd, entry)
        }
        sheet.show()
    }

    private fun showRenameDialog(fd: FieldDefinition, entry: FieldValueEntry) {
        val layout = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_field_value_rename, null)
        val input = layout.findViewById<EditText>(R.id.editNewValue)
        val keepAlias = layout.findViewById<CheckBox>(R.id.checkKeepAlias)
        input.setText(entry.value)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.field_library_rename_title)
            .setView(layout)
            .setPositiveButton(R.string.field_library_rename, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val newValue = input.text.toString().trim()
            if (newValue.isEmpty() || newValue == entry.value) return@setValidatedPositiveButton false
            confirmRename(fd, entry, newValue, keepAlias.isChecked)
            true
        }
        dialog.show()
    }

    private fun confirmRename(fd: FieldDefinition, entry: FieldValueEntry, newValue: String, keepAlias: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val preview = viewModel.previewPropagation(fd, setOf(entry.value))
            val message = getString(R.string.field_library_propagation_message,
                preview.characterValues, preview.eventValues, preview.stateChanges) +
                joinNoticeIfMulti(fd) +
                (if (preview.whitespaceNormalized > 0)
                    getString(R.string.field_library_propagation_whitespace) else "")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("'${entry.value}' → '$newValue'")
                .setMessage(message)
                .setPositiveButton(R.string.field_library_rename) { _, _ ->
                    viewModel.renameValue(fd, entry, newValue, keepAlias) { conflict ->
                        // 이미 존재하는 값 → 병합 제안 (변수 제어: 막다른 길 대신 교정 경로)
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage(getString(R.string.field_library_rename_conflict_merge, conflict.value))
                            .setPositiveButton(R.string.field_library_menu_merge) { _, _ ->
                                confirmMerge(fd, conflict, listOf(entry))
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showUsageDialog(fd: FieldDefinition, entry: FieldValueEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            val usage = viewModel.usageDrilldown(fd, entry)
            val lines = buildList {
                if (usage.characters.isNotEmpty()) {
                    add(getString(R.string.field_library_usage_characters, usage.characters.size))
                    usage.characters.take(30).forEach { add("  · ${it.displayName}") }
                    if (usage.characters.size > 30) add("  · … 외 ${usage.characters.size - 30}명")
                }
                if (usage.events.isNotEmpty()) {
                    add(getString(R.string.field_library_usage_events, usage.events.size))
                    usage.events.take(15).forEach { add("  · ${it.getFormattedDate()} ${it.description}") }
                    if (usage.events.size > 15) add("  · … 외 ${usage.events.size - 15}건")
                }
                if (usage.stateChangeCount > 0) {
                    add(getString(R.string.field_library_usage_state_changes, usage.stateChangeCount))
                }
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.field_library_usage_title, entry.display()))
                .setMessage(if (lines.isEmpty()) getString(R.string.field_library_usage_empty)
                    else lines.joinToString("\n"))
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
    }

    private fun showDeleteDialog(fd: FieldDefinition, entry: FieldValueEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            val tokens = setOf(entry.value) + entry.aliases()
            val preview = viewModel.previewPropagation(fd, tokens)
            val options = arrayOf(
                getString(R.string.field_library_delete_entry_only),
                getString(R.string.field_library_delete_with_data,
                    preview.characterValues, preview.eventValues, preview.stateChanges)
            )
            var choice = 0
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.field_library_delete_title, entry.value))
                .setSingleChoiceItems(options, 0) { _, which -> choice = which }
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.deleteEntry(fd, entry, alsoClearData = choice == 1)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ===== 어댑터 =====

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.Holder>() {
        private val items = mutableListOf<FieldValueEntry>()

        fun submit(newItems: List<FieldValueEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemFieldValueEntryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemFieldValueEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            val b = holder.b
            b.valueText.text = if (e.displayLabel.isNotBlank()) "${e.value} → ${e.displayLabel}" else e.value
            b.valueText.alpha = if (e.isHidden) 0.5f else 1f
            b.metaText.text = buildList {
                if (e.category.isNotBlank()) add(e.category)
                val aliasCount = e.aliases().size
                if (aliasCount > 0) add(getString(R.string.field_library_meta_alias_count, aliasCount))
                if (e.isHidden) add(getString(R.string.field_library_meta_hidden))
                if (e.source != FieldValueEntry.SOURCE_AUTO) add(e.source)
                if (e.description.isNotBlank()) add(e.description)
            }.joinToString(" · ")
            b.metaText.visibility = if (b.metaText.text.isBlank()) View.GONE else View.VISIBLE
            b.usageText.text = getString(R.string.field_library_usage_count, e.usageCount)

            b.selectCheckBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            b.selectCheckBox.setOnCheckedChangeListener(null)
            b.selectCheckBox.isChecked = e.id in selectedIds
            b.selectCheckBox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedIds.add(e.id) else selectedIds.remove(e.id)
            }

            b.root.setOnClickListener {
                if (selectionMode) {
                    b.selectCheckBox.isChecked = !b.selectCheckBox.isChecked
                } else {
                    showDetailSheet(e)
                }
            }
            b.root.setOnLongClickListener {
                if (!selectionMode) enterSelectionMode(e)
                true
            }
        }
    }

    companion object {
        private const val KEY_SORT = "sort_mode"
        private const val SORT_USAGE = 0
        private const val SORT_NAME = 1
        private const val SORT_UPDATED = 2
    }
}
