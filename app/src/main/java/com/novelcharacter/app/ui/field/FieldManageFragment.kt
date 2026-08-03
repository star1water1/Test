package com.novelcharacter.app.ui.field

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.databinding.FragmentFieldManageBinding
import com.novelcharacter.app.ui.adapter.FieldDefinitionAdapter
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.PresetMerge
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.setValidatedPositiveButton
import kotlinx.coroutines.launch

class FieldManageFragment : Fragment() {

    private var _binding: FragmentFieldManageBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FieldViewModel by viewModels()

    private lateinit var adapter: FieldDefinitionAdapter
    private var universeId: Long = -1L
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFieldManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        universeId = arguments?.getLong("universeId", -1L) ?: -1L
        if (universeId == -1L) {
            findNavController().popBackStack()
            return
        }

        viewModel.setUniverseId(universeId)
        setupToolbar()
        setupEntityTypeToggle()
        setupRecyclerView()
        setupFab()
        observeData()
        setupFieldEditResultListener()
    }

    private fun setupFieldEditResultListener() {
        childFragmentManager.setFragmentResultListener(
            FieldEditDialog.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val json = bundle.getString(FieldEditDialog.RESULT_FIELD_JSON) ?: return@setFragmentResultListener
            val savedField = Gson().fromJson(json, FieldDefinition::class.java)
            if (savedField.id == 0L) {
                // 생성 다이얼로그에서 사전 등록한 값들을 저장 직후 라이브러리에 등재
                val initialValues = bundle.getString(FieldEditDialog.RESULT_INITIAL_VALUES).orEmpty()
                viewModel.insertField(savedField, initialValues)
            } else {
                viewModel.updateField(savedField)
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.toolbar.inflateMenu(R.menu.field_manage_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_fields -> {
                    showImportFieldsDialog()
                    true
                }
                R.id.action_field_library -> {
                    findNavController().navigate(
                        R.id.fieldLibraryHomeFragment,
                        androidx.core.os.bundleOf("universeId" to universeId)
                    )
                    true
                }
                else -> false
            }
        }
    }

    // 드래그 후 저장할 순서를 보관 (ListAdapter 비동기 diff가 끝나기 전 currentList가 stale할 수 있음)
    private var pendingOrderList: List<FieldDefinition>? = null

    /**
     * AI 추천 대상 3단 선택(B-80) — 목록 행의 상태 버튼에서 연다.
     * 고른 즉시 저장한다(저장 버튼 없음 — 종전 스위치와 같은 마찰).
     */
    private fun showAiModeMenu(field: FieldDefinition, anchor: View) {
        val modes = FieldAiPolicy.SuggestMode.entries
        val current = FieldAiPolicy.suggestMode(field.config)
        android.widget.PopupMenu(requireContext(), anchor).apply {
            modes.forEachIndexed { i, mode -> menu.add(0, i, i, mode.label) }
            setOnMenuItemClickListener { item ->
                val picked = modes.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                // 같은 값을 다시 고르면 쓰지 않는다 — 무의미한 갱신이 목록을 흔들지 않게.
                if (picked != current) {
                    viewModel.updateFieldQuiet(
                        field.copy(config = FieldAiPolicy.applyMode(field.config, picked))
                    )
                }
                true
            }
            show()
        }
    }

    private fun setupRecyclerView() {
        adapter = FieldDefinitionAdapter(
            onClick = { field ->
                showFieldEditDialog(field)
            },
            onLongClick = { field ->
                showFieldOptionsDialog(field)
            },
            // A-1: 상태 버튼 1탭 → 메뉴 1탭 즉시 반영 — 재정렬과 같은 규칙(성공 무통보, 실패만 알림).
            // 목록 재방출로 라벨이 DB 값과 다시 맞춰지므로 실패해도 화면이 거짓 상태로 남지 않는다.
            // B-80으로 3단이 되어 스위치가 아니다(끄기 / 개별만 / 전부).
            onAiModeClick = { field, anchor -> showAiModeMenu(field, anchor) },
            // A-2: 필드 설명 — 폼의 ⓘ와 같은 다이얼로그
            onInfoClick = { field ->
                com.novelcharacter.app.ui.common.HelpDialog.showFieldNote(requireContext(), field)
            }
        )
        binding.fieldRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.fieldRecyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            // 드래그는 핸들 전용 — 롱프레스는 옵션 다이얼로그(onLongClick)와 경쟁하지 않는다.
            // 세계관·작품·캐릭터·연표 목록과 같은 규약이며, 행에 스위치가 얹혀도 오조작이 없다.
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                // 빠른 연속 이동 시 currentList는 아직 이전 diff가 커밋되지 않았을 수 있으므로 pending을 우선 사용
                val list = (pendingOrderList ?: adapter.currentList).toMutableList()
                if (from < 0 || to < 0 || from >= list.size || to >= list.size) return false
                val item = list.removeAt(from)
                list.add(to, item)
                pendingOrderList = list
                adapter.submitList(list)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 이동이 한 번도 없었다면(pending == null) 저장할 변경도 없다
                val listToSave = pendingOrderList ?: return
                pendingOrderList = null
                viewModel.updateFieldOrder(listToSave)
            }
        })
        itemTouchHelper?.attachToRecyclerView(binding.fieldRecyclerView)
        adapter.itemTouchHelper = itemTouchHelper
    }

    private fun setupFab() {
        binding.fabAddField.setOnClickListener {
            showFieldEditDialog(null)
        }
    }

    private fun observeData() {
        viewModel.fields.observe(viewLifecycleOwner) { fields ->
            adapter.submitList(fields)
            val isEmpty = fields.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.fieldRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
        // 데이터 처리 결과 알림 (성공/실패·자동 교정 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }
    }

    /** 캐릭터 / 사건 / 작품 필드 관리 대상 전환 (B-10 · 확-3) */
    private fun setupEntityTypeToggle() {
        binding.entityTypeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val type = when {
                checkedIds.contains(R.id.chipEventFields) -> FieldDefinition.ENTITY_EVENT
                checkedIds.contains(R.id.chipNovelFields) -> FieldDefinition.ENTITY_NOVEL
                else -> FieldDefinition.ENTITY_CHARACTER
            }
            viewModel.setEntityType(type)
        }
        viewModel.entityType.observe(viewLifecycleOwner) { type ->
            val targetId = when (type) {
                FieldDefinition.ENTITY_EVENT -> R.id.chipEventFields
                FieldDefinition.ENTITY_NOVEL -> R.id.chipNovelFields
                else -> R.id.chipCharacterFields
            }
            if (binding.entityTypeChipGroup.checkedChipId != targetId) {
                binding.entityTypeChipGroup.check(targetId)
            }
        }
    }

    private fun showFieldEditDialog(field: FieldDefinition?) {
        val dialog = FieldEditDialog.newInstance(universeId, field, viewModel.currentEntityType())
        dialog.show(childFragmentManager, "FieldEditDialog")
    }

    /** 길게 누름 — 필드별 옵션: 데이터 라이브러리 드릴다운 / 삭제 (미지원 타입은 사유 비활성) */
    private fun showFieldOptionsDialog(field: FieldDefinition) {
        val supported = com.novelcharacter.app.util.FieldValueTokenizer.supportsLibrary(field)
        val options = mutableListOf(getString(R.string.field_library_title), getString(R.string.delete))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(field.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        if (supported) {
                            findNavController().navigate(
                                R.id.fieldValueListFragment,
                                androidx.core.os.bundleOf("fieldDefinitionId" to field.id)
                            )
                        } else {
                            val reason = when (field.type) {
                                "CALCULATED" -> getString(R.string.field_library_unsupported_calculated)
                                "NUMBER" -> getString(R.string.field_library_unsupported_number)
                                else -> getString(R.string.field_library_unsupported_structured)
                            }
                            android.widget.Toast.makeText(requireContext(), reason, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    1 -> showDeleteDialog(field)
                }
            }
            .show()
    }

    private fun showDeleteDialog(field: FieldDefinition) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(field.name)
            .setMessage("[${field.groupName}] ${field.type}")
            .setPositiveButton(R.string.edit) { _, _ ->
                showFieldEditDialog(field)
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                confirmDeleteField(field)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteField(field: FieldDefinition) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 참조 수식은 같은 종류 안에서만 성립한다 — 종류를 넘겨야 사건·작품 필드도 경고가 뜬다(R-29)
            val refs = viewModel.getReferencingCalculatedFields(universeId, field.key, field.entityType)
            if (refs.isEmpty()) {
                viewModel.deleteField(field)
                return@launch
            }
            val names = refs.joinToString("\n") { "  • ${it.name}" }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_field_warning_title)
                .setMessage(getString(R.string.delete_field_warning_message, field.name, names))
                .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteField(field) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * **프리셋·다른 세계관에서 필드 합치기** (B-89) — 고르면 바로 심지 않고 미리보기를 연다.
     *
     * 종전에는 목록에 `[중복]`만 붙여 두고 그것을 체크해도 **조용히 걸렀다**. 화면은
     * "N개 가져왔습니다"라고 말했는데 그 N은 고른 수였고 실제로 심긴 수는 달랐다.
     * 이제 처분이 셋이다 — 새 필드는 들어오고, 이미 있는 필드는 기본으로 손대지 않으며,
     * 원하는 것만 덮어쓰기로 올린다([PresetMerge]).
     *
     * **종류를 가리지 않는다**(③ 사용자 확정) — 프리셋이 담은 캐릭터·사건·작품 필드가 함께
     * 오고, 미리보기가 종류별로 묶어 보여 준다. 관리 중인 탭에 갇혀 있으면 프리셋이 담은
     * 사건 필드는 그 탭으로 옮겨 다시 열기 전에는 존재조차 알 수 없다(원칙 04).
     */
    private fun showImportFieldsDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val allSources = viewModel.getMergeSources(universeId)
            if (allSources.isEmpty()) {
                Toast.makeText(requireContext(), R.string.merge_no_sources, Toast.LENGTH_LONG).show()
                return@launch
            }

            val ctx = requireContext()
            val sourceNames = allSources.keys.toList()
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), 0)
            }

            val sourceSpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                    sourceNames.map { getString(R.string.merge_source_entry, it, allSources[it]?.size ?: 0) }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            }
            container.addView(sourceSpinner)

            // R-25 목적문 — 이 창이 무엇을 어디에 어떻게 하는가를 한 줄로 말한다.
            container.addView(TextView(ctx).apply {
                setText(R.string.merge_purpose)
                textSize = 12f
                setPadding(0, dp(6), 0, dp(2))
            })

            val summaryView = TextView(ctx).apply {
                textSize = 13f
                setPadding(0, dp(2), 0, dp(6))
            }
            container.addView(summaryView)

            // R-31 — 항목 수가 소스에 비례하므로 상한 없이는 긴 프리셋이 잘린 채 끝까지
            // 스크롤되지 않는다. 짧으면 아무 일도 하지 않는다.
            val listHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            container.addView(
                cappedScrollView(ctx).apply { addView(listHolder) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            // 화면이 쥐는 상태는 이 둘뿐이다 — 지금 보고 있는 계획과, 켜 둔 항목.
            var plan = PresetMerge.Plan(emptyList())
            val selected = linkedSetOf<String>()

            fun renderSummary() {
                summaryView.text = getString(
                    R.string.merge_summary, plan.additions.size, plan.duplicates.size
                )
            }

            fun changeLabel(changes: Set<PresetMerge.Change>): String = changes.joinToString(
                getString(R.string.merge_change_separator)
            ) {
                getString(
                    when (it) {
                        PresetMerge.Change.NAME -> R.string.merge_change_name
                        PresetMerge.Change.TYPE -> R.string.merge_change_type
                        PresetMerge.Change.CONFIG -> R.string.merge_change_config
                        PresetMerge.Change.GROUP -> R.string.merge_change_group
                        PresetMerge.Change.REQUIRED -> R.string.merge_change_required
                    }
                )
            }

            fun renderList() {
                listHolder.removeAllViews()
                for ((entityType, items) in plan.byEntityType()) {
                    val boxes = ArrayList<CheckBox>(items.size)

                    // 종류 머리글은 그 종류를 통째로 켜고 끄는 자리이기도 하다 — 캐릭터 필드
                    // 스물몇 개를 하나씩 끄게 두면 "사건 필드만 받고 싶다"가 스무 번의 조작이 된다.
                    val header = CheckBox(ctx).apply {
                        text = getString(R.string.merge_group_header, entityTypeLabel(entityType), items.size)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, dp(10), 0, dp(2))
                    }
                    listHolder.addView(header)

                    // 머리글 상태를 아이들에 맞춘다. **프로그램 대입은 아래 setOnClickListener를
                    // 부르지 않으므로**(클릭 리스너는 사용자 조작에만 반응한다) 재귀가 없다.
                    fun syncHeader() {
                        val togglable = boxes.filter { it.isEnabled }
                        // 전부 '같은 내용'이라 고를 것이 없는 묶음은 머리글도 누를 수 없다 —
                        // 눌러도 아무 일이 없는 조작을 남겨 두면 고장으로 읽힌다.
                        header.isEnabled = togglable.isNotEmpty()
                        header.isChecked = togglable.isNotEmpty() && togglable.all { it.isChecked }
                    }

                    for (item in items) {
                        val box = CheckBox(ctx).apply {
                            val label = getString(
                                R.string.merge_item_label,
                                item.source.groupName, item.source.name, item.source.type
                            )
                            text = when {
                                !item.isDuplicate -> label
                                item.isIdentical ->
                                    label + getString(R.string.merge_item_identical)
                                else -> label + getString(
                                    R.string.merge_item_overwrite, changeLabel(item.changes)
                                )
                            }
                            // 같은 정의는 덮어써도 결과가 같다 — 아무 일도 하지 않는 조작을
                            // 선택지로 주지 않는다. 지우지는 않는다(있다는 사실은 알려야 한다).
                            // 딤만으로는 사유를 말하지 못하므로 라벨이 사유를 함께 든다.
                            isEnabled = !item.isIdentical
                            isChecked = item.itemKey in selected
                            setPadding(dp(8), dp(2), 0, dp(2))
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) selected.add(item.itemKey) else selected.remove(item.itemKey)
                                // 하나를 끄면 머리글도 함께 풀린다 — 아니면 머리글이 "전부 켰다"고
                                // 거짓을 말한다.
                                syncHeader()
                            }
                        }
                        boxes.add(box)
                        listHolder.addView(box)
                    }

                    syncHeader()
                    header.setOnClickListener {
                        // CheckBox의 클릭 리스너는 상태가 **바뀐 뒤** 불린다 — 지금 값이 곧 목표다.
                        val on = header.isChecked
                        boxes.filter { it.isEnabled }.forEach { it.isChecked = on }
                    }
                }
            }

            /**
             * 마지막으로 요청된 소스. 스피너를 빠르게 넘기면 코루틴 여럿이 동시에 뜨고,
             * **먼저 요청한 것이 나중에 끝나면** 화면과 `plan`이 다른 소스를 가리킨다 —
             * 그러면 미리보기에 보이는 것과 실제로 심기는 것이 갈린다.
             */
            var pendingSource: String? = null

            suspend fun loadSource(name: String) {
                pendingSource = name
                val built = viewModel.buildMergePlan(universeId, allSources[name] ?: emptyList())
                if (pendingSource != name) return
                plan = built
                selected.clear()
                selected.addAll(plan.defaultSelection())
                renderSummary()
                renderList()
            }

            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.merge_title)
                .setView(container)
                // R-27 — 아무것도 안 고른 채 누르면 알리되 창은 유지한다. 창이 닫히면
                // 고르던 것이 함께 사라진다.
                .setPositiveButton(R.string.merge_apply, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    viewLifecycleOwner.lifecycleScope.launch { loadSource(sourceNames[position]) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            dialog.show()
            dialog.setValidatedPositiveButton {
                if (selected.isEmpty()) {
                    Toast.makeText(ctx, R.string.merge_none_selected, Toast.LENGTH_SHORT).show()
                    false
                } else {
                    val sourceName = sourceNames.getOrNull(sourceSpinner.selectedItemPosition).orEmpty()
                    applyMerge(plan, selected.toSet(), sourceName)
                    true
                }
            }
            // 첫 소스는 스피너가 붙으면서 리스너를 부르지만, 그것은 다음 레이아웃 패스의 일이라
            // 창이 잠깐 빈 채로 뜬다. 여기서 한 번 부르고, 겹쳐도 위 [pendingSource]가 뒤엣것만
            // 반영하므로 두 번 그려지지 않는다.
            loadSource(sourceNames[0])
        }
    }

    /**
     * 고른 처분을 반영하고 **반영된 수**로 알린다.
     *
     * 고른 수가 아니라 반영된 수인 것이 이 함수의 요점이다 — 종전 경로는 고른 수를 토스트로,
     * 실제로 심긴 수를 결과 채널로 말해 같은 조작에 숫자가 둘이었다(개발 의도 2번 '변수 제어').
     * 다른 종류에 심긴 것은 지금 보고 있는 목록에 나타나지 않으므로 **종류를 함께 말한다** —
     * 말하지 않으면 사용자가 보는 화면에서는 아무 일도 일어나지 않은 것과 같다(R-29).
     */
    private fun applyMerge(plan: PresetMerge.Plan, selected: Set<String>, sourceName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                val resolution = viewModel.applyMergePlan(universeId, plan, selected, sourceName)
                val parts = mutableListOf<String>()
                for ((entityType, count) in resolution.insertsByEntityType()) {
                    parts.add(getString(R.string.merge_result_added, entityTypeLabel(entityType), count))
                }
                for ((entityType, count) in resolution.updatesByEntityType()) {
                    parts.add(getString(R.string.merge_result_overwritten, entityTypeLabel(entityType), count))
                }
                if (parts.isEmpty()) {
                    OpResult.success(OpResult.CAT_FIELD, getString(R.string.merge_result_none))
                } else {
                    OpResult.success(
                        OpResult.CAT_FIELD,
                        parts.joinToString(getString(R.string.merge_change_separator)),
                        // 되돌릴 수 있다는 사실은 덮어쓴 때만 참이다(④ — 스냅샷은 덮어쓰기를
                        // 고른 항목에만 남는다). 늘 붙이면 건너뛰기만 한 병합에서 거짓이 된다.
                        if (resolution.updates.isEmpty()) null
                        else getString(R.string.merge_result_revert_hint)
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("FieldManageFragment", "Failed to merge fields", e)
                OpResult.failure(OpResult.CAT_FIELD, getString(R.string.merge_failed), e.message)
            }
            if (isAdded) notifyResult(result)
        }
    }

    private fun entityTypeLabel(entityType: String): String = getString(
        when (entityType) {
            FieldDefinition.ENTITY_EVENT -> R.string.field_target_event
            FieldDefinition.ENTITY_NOVEL -> R.string.field_target_novel
            else -> R.string.field_target_character
        }
    )

    override fun onDestroyView() {
        adapter.itemTouchHelper = null
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        binding.fieldRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
