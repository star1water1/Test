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
import com.novelcharacter.app.util.DetailListSort
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
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

    /**
     * 보기 정렬 (B-48 · 확정 7-7). **저장 순서와 다른 것이다** — 이 값은 보이는 순서만 정하고
     * `displayOrder`를 읽지도 쓰지도 않는다. 드래그 콜백이 매 이벤트에 이 값을 보므로
     * ViewModel의 LiveData를 그대로 두지 않고 화면에 한 벌 들고 있는다.
     */
    private var sortMode: DetailListSort.FieldMode = DetailListSort.FieldMode.MANUAL

    /** 정렬을 걸기 **전**의 순서(=`displayOrder` 순서). 저장은 언제나 이쪽을 기준으로 한다. */
    private var manualOrderFields: List<FieldDefinition> = emptyList()

    /**
     * 목록이 한 번이라도 도착했는가.
     *
     * 정렬 상태는 `MutableLiveData(초기값)`이라 **구독 즉시** 통보되는데 목록은 세계관 id를
     * 기다린다. 그 사이에 그리면 빈 목록으로 판정해 *"필드 없음"*이 한 번 번쩍인다 —
     * 화면 진입마다 보이므로 사용자에게는 깜빡임이지 정보가 아니다.
     */
    private var fieldsDelivered = false

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
        setupSortMenu()
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
            val wantsDefault = bundle.getBoolean(FieldEditDialog.RESULT_DEFAULT_FIELD, false)
            // 스위치를 끈 것이 **해제**인지 아닌지는 종전 상태가 정한다. 다이얼로그는 config를
            // 건드리지 않으므로 돌아온 필드의 표식이 곧 종전 상태다(B-119).
            val wasLinked = com.novelcharacter.app.data.model.DefaultFieldRef.isLinked(savedField.config)
            if (savedField.id == 0L) {
                // 생성 다이얼로그에서 사전 등록한 값들을 저장 직후 라이브러리에 등재
                val initialValues = bundle.getString(FieldEditDialog.RESULT_INITIAL_VALUES).orEmpty()
                viewModel.insertField(savedField, initialValues, defaultField = wantsDefault)
            } else if (!wantsDefault && wasLinked) {
                // 해제는 **모든 세계관**에 걸린다 — 스위치 하나가 그만큼 넓게 미치므로 먼저 묻는다.
                // 되돌릴 수 있는 조작이지만(다시 켜면 된다), 되돌릴 수 있음과 예고 없음은 다르다.
                confirmUnlinkDefaultField(savedField)
            } else {
                viewModel.updateField(savedField, defaultField = wantsDefault)
            }
        }
    }

    /**
     * '전역 기본 해제'를 묻는다 — 취소하면 **저장은 그대로 두고 연결만 유지한다**(설계 1-3).
     *
     * 취소가 저장까지 물리지 않는 것은, 사용자가 고친 이름·설정은 스위치와 무관하게 그 세계관에
     * 저장하려던 것이기 때문이다. 물리는 것은 스위치 하나뿐이다.
     */
    private fun confirmUnlinkDefaultField(field: FieldDefinition) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.default_field_unlink_confirm_title)
            .setMessage(R.string.default_field_unlink_confirm)
            .setPositiveButton(R.string.default_field_action_unlink) { _, _ ->
                viewModel.updateField(field, defaultField = false)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.updateField(field, defaultField = true)
            }
            .setOnCancelListener { viewModel.updateField(field, defaultField = true) }
            .show()
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
                // 전역 기본 필드 관리(B-119) — 세계관에 매이지 않으므로 인자를 넘기지 않는다
                R.id.action_default_fields -> {
                    findNavController().navigate(R.id.defaultFieldManageFragment)
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

            // 비기본 정렬에서는 **잡히지도 않게** 한다 (B-48 · 확정 7-7). onMove에서 막으면
            // 항목이 손에 붙었다가 제자리로 튀어 "되는데 안 먹는" 것처럼 보인다(B-85가 택한 모양).
            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = if (sortMode.allowsManualReorder) super.getDragDirs(recyclerView, viewHolder) else 0

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
                // **쓰는 자리에서 한 번 더 막는다** (B-48). getDragDirs가 이미 잡기를 닫았지만,
                // 이 순서는 이 목록만의 것이 아니라 **편집 폼의 칸 순서이자 엑셀 열 순서**라
                // 한 번 덮이면 되돌릴 방법이 없다 — 두 자리를 닫는 것이 B-85의 처방이다.
                if (!sortMode.allowsManualReorder) return
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
            manualOrderFields = fields
            fieldsDelivered = true
            renderFields()
        }
        viewModel.sortMode.observe(viewLifecycleOwner) { mode ->
            sortMode = mode
            renderFields()
            updateSortUi()
        }
        // 데이터 처리 결과 알림 (성공/실패·자동 교정 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }
    }

    /**
     * 저장 순서 목록과 화면 목록을 **갈라 든다** (B-48 · B-85 선례).
     *
     * 화면에 정렬을 걸어도 [manualOrderFields]는 `displayOrder` 순서 그대로다 — 드래그가
     * 열린 기본 정렬에서만 화면 목록이 곧 저장 대상이 되고, 그 보장은 `getDragDirs`가 한다.
     */
    private fun renderFields() {
        if (_binding == null || !fieldsDelivered) return
        val cmp = DetailListSort.fields(
            sortMode,
            name = { f: FieldDefinition -> f.name },
            type = { f: FieldDefinition -> f.type },
            group = { f: FieldDefinition -> f.groupName }
        )
        val shown = if (cmp == null) manualOrderFields else manualOrderFields.sortedWith(cmp)
        adapter.submitList(shown)
        val isEmpty = shown.isEmpty()
        binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.fieldRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    /** 보기 정렬 고르기 (B-48). 라벨은 화면이 붙인다 — 순수 계층은 안드로이드를 모른다. */
    private fun setupSortMenu() {
        binding.btnSortFields.setOnClickListener { anchor ->
            val modes = DetailListSort.FieldMode.entries
            android.widget.PopupMenu(requireContext(), anchor).apply {
                modes.forEachIndexed { i, mode -> menu.add(0, i, i, getString(sortLabel(mode))) }
                setOnMenuItemClickListener { item ->
                    modes.getOrNull(item.itemId)?.let { viewModel.setSortMode(it) }
                    true
                }
                show()
            }
        }
    }

    private fun sortLabel(mode: DetailListSort.FieldMode): Int = when (mode) {
        DetailListSort.FieldMode.MANUAL -> R.string.field_sort_manual
        DetailListSort.FieldMode.NAME -> R.string.field_sort_name
        DetailListSort.FieldMode.TYPE -> R.string.field_sort_type
        DetailListSort.FieldMode.GROUP -> R.string.field_sort_group
    }

    /**
     * 고른 기준을 버튼에 적고, **드래그가 잠긴 사유를 화면에 적는다**(확정 7-7).
     * 사유를 적지 않으면 드래그가 안 먹는 것이 고장으로 읽힌다 — B-85가 같은 처방을 썼다.
     */
    private fun updateSortUi() {
        if (_binding == null) return
        binding.btnSortFields.text = getString(sortLabel(sortMode))
        binding.sortLockedNotice.visibility =
            if (sortMode.allowsManualReorder) View.GONE else View.VISIBLE
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
        val dialog = FieldEditDialog.newInstance(
            universeId, field, viewModel.currentEntityType(),
            // 이 화면만 '전역 기본 필드' 스위치를 연다 — 결과를 실제로 처리하는 유일한 자리다
            // (B-119. 처리하지 않는 화면에서 스위치를 보이면 조용한 무동작이 된다).
            allowDefaultField = true
        )
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

            // 종류를 바꿔 심어도 되는 필드 타입 (B-63 · 확정 14번). 사용자가 정한 값이고
            // 창을 여는 시점에 한 번 읽는다 — 아래 '허용 타입' 창이 고치면 함께 갱신된다.
            //
            // **읽기 실패는 여기서 삼킨다.** 저장소는 실패를 그대로 올린다(`TrashSettingsStore`가
            // 세운 규약 — *부르는 쪽이 처분을 정한다*). 이쪽의 처분이 저장소와 다른 이유는
            // **잃는 것이 다르기 때문**이다: 휴지통 정책은 추측한 값이 곧 동의 없는 영구 삭제가
            // 되지만, 여기서는 아무것도 지워지지 않고 **사용자가 미리보기를 보고 확인을 눌러야**
            // 심긴다. 반대로 삼키지 않으면 이 코루틴이 죽어 **'필드 합치기'가 아무 반응도 없다** —
            // 그쪽이 훨씬 나쁘다. 못 읽었으면 기본값으로 간다(키가 없을 때와 같은 처분이다).
            val settingsStore = com.novelcharacter.app.data.settings
                .FieldImportSettingsStore(ctx.applicationContext)
            var convertibleTypes = runCatching { settingsStore.getConvertibleTypes() }
                .getOrElse { PresetMerge.DEFAULT_CONVERTIBLE_TYPES }

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

            // ── 종류 바꿔 심기 (B-63) ──
            // 첫 항목이 '그대로'인 것이 이 스피너의 요점이다 — **오늘의 동작이 기본**이라
            // 이 창을 늘 쓰던 사용자에게 아무것도 달라지지 않는다(변환은 고를 때만 일어난다).
            val convertTargets: List<String?> = listOf(
                null,
                FieldDefinition.ENTITY_CHARACTER,
                FieldDefinition.ENTITY_EVENT,
                FieldDefinition.ENTITY_NOVEL
            )
            val convertSpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                    convertTargets.map {
                        if (it == null) getString(R.string.merge_convert_keep)
                        else getString(R.string.merge_convert_as, entityTypeLabel(it))
                    }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            }
            container.addView(TextView(ctx).apply {
                setText(R.string.merge_convert_label)
                textSize = 13f
                setPadding(0, dp(8), 0, dp(2))
            })
            container.addView(convertSpinner)
            container.addView(TextView(ctx).apply {
                setText(R.string.merge_convert_purpose)
                textSize = 12f
                setPadding(0, dp(2), 0, dp(2))
            })

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

            // 화면이 쥐는 상태는 셋이다 — 지금 보고 있는 계획, 켜 둔 항목, 그리고 종류 변환의
            // 결과(막힌 것·잃는 설정). 셋째는 '그대로'일 때 null이다.
            var plan = PresetMerge.Plan(emptyList())
            val selected = linkedSetOf<String>()
            var conversion: PresetMerge.Conversion? = null

            fun renderSummary() {
                val base = getString(
                    R.string.merge_summary, plan.additions.size, plan.duplicates.size
                )
                // 막힌 것을 요약에 함께 적는다 — 목록에서만 말하면 스크롤 밖으로 나가는
                // 순간 사라지고, 사용자는 자기가 고른 필드가 왜 없는지 알 길이 없다.
                val blocked = conversion?.blocked.orEmpty()
                summaryView.text = if (blocked.isEmpty()) base
                else base + getString(R.string.merge_convert_blocked_summary, blocked.size)
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
                            // 종류를 바꾸느라 잃는 설정은 **심기 전에** 그 항목에 붙인다 —
                            // 조용히 떨어뜨리면 변수 제어 위반이고, 결과 고지로 미루면
                            // 이미 심긴 뒤라 되돌리는 것 말고는 손쓸 방법이 없다.
                            val lost = conversion?.configLoss?.get(item.itemKey).orEmpty()
                            val lostNote = if (lost.isEmpty()) "" else getString(
                                R.string.merge_convert_config_lost,
                                lost.joinToString(getString(R.string.merge_change_separator))
                            )
                            text = when {
                                !item.isDuplicate -> label + lostNote
                                item.isIdentical ->
                                    label + getString(R.string.merge_item_identical) + lostNote
                                else -> label + getString(
                                    R.string.merge_item_overwrite, changeLabel(item.changes)
                                ) + lostNote
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

                // 막힌 필드는 **지우지 않고 사유와 함께 보인다**(B-63) — 목록에서 빼면
                // 사용자는 자기가 고른 소스에 그 필드가 있었다는 것조차 모른다.
                val blocked = conversion?.blocked.orEmpty()
                if (blocked.isNotEmpty()) {
                    listHolder.addView(TextView(ctx).apply {
                        text = getString(R.string.merge_convert_blocked_header, blocked.size)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, dp(10), 0, dp(2))
                    })
                    for (field in blocked) {
                        listHolder.addView(TextView(ctx).apply {
                            text = getString(
                                R.string.merge_convert_blocked_item,
                                field.name, field.type
                            )
                            textSize = 13f
                            setTextColor(ctx.getColor(R.color.text_secondary))
                            setPadding(dp(8), dp(2), 0, dp(2))
                        })
                    }
                    // 사유를 말했으면 **바로잡을 경로**도 함께 준다(개발 의도 2번) —
                    // 설정 화면까지 나갔다 돌아오게 하면 그 자체가 조작 마찰이다(원칙 04).
                    listHolder.addView(TextView(ctx).apply {
                        setText(R.string.merge_convert_open_types)
                        setTextColor(ctx.getColor(R.color.primary))
                        setPadding(dp(8), dp(6), 0, dp(4))
                        setOnClickListener { showConvertibleTypesDialog() }
                    })
                }
            }

            /**
             * 이번 요청의 번호. 소스 스피너와 종류 스피너를 빠르게 넘기면 코루틴 여럿이 동시에
             * 뜨고, **먼저 요청한 것이 나중에 끝나면** 화면과 `plan`이 다른 조합을 가리킨다 —
             * 그러면 미리보기에 보이는 것과 실제로 심기는 것이 갈린다.
             *
             * **소스 이름이 아니라 번호인 것이 B-63이 바꾼 자리다** — 축이 둘이 되면서 같은
             * 소스를 종류만 바꿔 다시 부르는 일이 생겼는데, 이름으로 견주면 그 둘이 같아
             * 늦게 온 옛 결과가 새 결과를 덮는다.
             */
            var loadToken = 0

            suspend fun loadSource(name: String, target: String?) {
                val token = ++loadToken
                val raw = allSources[name] ?: emptyList()
                // 변환은 **계획을 세우기 전에** 끝난다 — 소스·중복·순서·삽입이 모두 같은
                // 종류를 봐야 한다(R-29). 여기서 바꾸면 그 아래는 손댈 것이 없다.
                val converted = if (target == null) null
                    else PresetMerge.convertEntityType(raw, target, convertibleTypes)
                val built = viewModel.buildMergePlan(universeId, converted?.fields ?: raw)
                if (token != loadToken) return
                conversion = converted
                plan = built
                selected.clear()
                selected.addAll(plan.defaultSelection())
                renderSummary()
                renderList()
            }

            fun reload() {
                val name = sourceNames.getOrNull(sourceSpinner.selectedItemPosition) ?: return
                val target = convertTargets.getOrNull(convertSpinner.selectedItemPosition)
                viewLifecycleOwner.lifecycleScope.launch { loadSource(name, target) }
            }

            // 허용 타입을 고친 뒤에는 **보고 있는 미리보기를 다시 세운다** — 안 그러면
            // 방금 켠 타입이 목록에 없는 채로 남아 설정이 안 먹은 것처럼 보인다.
            convertibleTypesChanged = { types ->
                convertibleTypes = types
                reload()
            }

            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.merge_title)
                .setView(container)
                // R-27 — 아무것도 안 고른 채 누르면 알리되 창은 유지한다. 창이 닫히면
                // 고르던 것이 함께 사라진다.
                .setPositiveButton(R.string.merge_apply, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            val reloadOnSelect = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    reload()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            sourceSpinner.onItemSelectedListener = reloadOnSelect
            convertSpinner.onItemSelectedListener = reloadOnSelect

            // 창이 닫히면 위 콜백이 가리키는 뷰가 죽는다 — 남겨 두면 설정 창만 다시 열었을 때
            // 사라진 미리보기를 다시 그리려 든다.
            dialog.setOnDismissListener { convertibleTypesChanged = null }

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
            // 창이 잠깐 빈 채로 뜬다. 여기서 한 번 부르고, 겹쳐도 위 [loadToken]이 뒤엣것만
            // 반영하므로 두 번 그려지지 않는다.
            loadSource(sourceNames[0], null)
        }
    }

    /**
     * 열린 병합 창에게 *"허용 타입이 바뀌었다"*를 알리는 통로 (B-63).
     *
     * 설정 창은 병합 창 위에 떠 있고 그 창의 지역 변수를 볼 수 없다. 통로가 없으면 사용자가
     * 타입을 켠 뒤에도 **미리보기가 옛 목록 그대로**라 설정이 안 먹은 것처럼 보인다.
     * 창이 닫힐 때 null로 되돌린다(죽은 뷰를 다시 그리지 않게).
     */
    private var convertibleTypesChanged: ((Set<String>) -> Unit)? = null

    /**
     * **종류를 바꿔 심어도 되는 필드 타입을 고른다** (B-63 · 확정 14번 — "허용 타입을 고정하지
     * 말고 사용자가 정하게").
     *
     * 목록은 [FieldType]에서 그대로 끌어온다 — 여기 손으로 적으면 타입이 하나 늘 때
     * **그 타입만 이 창에서 조용히 빠진다**(R-29가 종류를 손으로 더하는 형태에서 겪은 결함).
     */
    private fun showConvertibleTypesDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val store = com.novelcharacter.app.data.settings
                .FieldImportSettingsStore(ctx.applicationContext)
            val types = FieldType.entries.toList()
            // 읽기 실패의 처분은 위 [showImportFieldsDialog]와 같다 — 삼키지 않으면 창이
            // 아예 안 뜬다(사용자에게는 '허용 타입 고치기'가 죽은 버튼으로 보인다).
            val current = runCatching { store.getConvertibleTypes() }
                .getOrElse { PresetMerge.DEFAULT_CONVERTIBLE_TYPES }
            val checked = BooleanArray(types.size) { types[it].name in current }

            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.merge_convert_types_title)
                .setMultiChoiceItems(
                    types.map { getString(R.string.merge_convert_type_entry, it.label, it.name) }
                        .toTypedArray(),
                    checked
                ) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(R.string.save) { _, _ ->
                    val picked = types.filterIndexed { i, _ -> checked[i] }
                        .mapTo(linkedSetOf()) { it.name }
                    viewLifecycleOwner.lifecycleScope.launch {
                        // **하나도 안 고른 것도 값이다** — 종류 바꿔 심기를 닫아 둔 상태이고,
                        // 기본값으로 되돌리면 사용자가 끈 것이 켜진다.
                        //
                        // **쓰기 실패는 반드시 말한다** — 읽기와 처분이 갈리는 자리다.
                        // 못 읽은 것은 다음에 다시 읽으면 되지만, 못 쓴 것을 조용히 넘기면
                        // 사용자는 **고친 줄 알고 돌아간다**(개발 의도 2번). 그리고 여기서
                        // 삼키지 않으면 launch가 예외를 그대로 올려 **앱이 죽는다.**
                        val saved = runCatching { store.setConvertibleTypes(picked) }.isSuccess
                        if (!isAdded) return@launch
                        if (!saved) {
                            Toast.makeText(
                                requireContext(),
                                R.string.merge_convert_types_save_failed,
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        convertibleTypesChanged?.invoke(picked)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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
