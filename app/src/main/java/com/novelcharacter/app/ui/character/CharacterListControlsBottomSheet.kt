package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.BottomSheetCharacterControlsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.novelcharacter.app.data.model.FieldType

/**
 * 통합 목록 컨트롤 시트 — 프리셋 / 정렬(기준+방향) / 필터를 한 표면에 담는다.
 * 기존 CharacterSortBottomSheet·CharacterFilterBottomSheet의 로직을 그대로 승계했고,
 * 정렬 방향이 드디어 기준 옆(토글 그룹)에 위치한다. '적용' 한 번으로 정렬+필터가
 * 함께 반영된다(원칙 04: 최소 단계). 프리셋은 탭 즉시 적용·닫기(최속 경로).
 *
 * ## 꽂는 배선이 없다 (R-65)
 *
 * 종전에는 호스트가 재료·처분 **열넷**을 인스턴스에 꽂았다. 회전하면 FragmentManager가 이
 * 시트를 **기본 생성자로 다시 세우고** 호스트는 다시 꽂지 않았다 — 시트는 빈 목록으로 멀쩡히
 * 떠 있고 [적용]·[전체 해제]·프리셋 탭이 **아무 데도 닿지 않는다.** 눌러도 아무 일이 없으니
 * 고장과 구분되지 않고, 사용자는 정렬·필터를 다시 잡은 것이 반영됐다고 읽는다.
 *
 * 그래서 배선을 되살리는 대신 **없앴다**:
 * - **재료와 처분은 [CharacterViewModel]이 든다.** 이 시트는 `childFragmentManager`로 뜨므로
 *   부모의 ViewModelStore가 곧 이 시트의 것이다 — 호스트와 **같은 인스턴스**를 잡는다.
 * - **호스트만 할 수 있는 일**(프리셋 저장 창·프리셋 옵션 창)만 결과 채널로 건넨다([RESULT_KEY]).
 * - **판마다 다른 맥락**(작품 섹션을 보이는가)은 인자 번들로 싣는다 — 번들은 회전을 넘는다.
 *
 * ## 고르던 것도 지킨다 (R-41)
 *
 * 칩은 동적으로 붙어 뷰 상태 저장이 들지 못하고, 스피너는 어댑터가 비동기라 위치 복원이
 * 재료보다 먼저 온다. 그래서 **고르던 것**(태그·작품·값·세계관·필드)을 [onSaveInstanceState]로
 * 들고 있다가 각 목록이 선 뒤에 되살린다 — 회전이 지키는 것은 *적용된 값*이 아니라 *고르던 것*이다.
 */
class CharacterListControlsBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: CharacterViewModel by viewModels(ownerProducer = { requireParentFragment() })

    /** 지금 적용돼 있는 정렬 — 시트가 열릴 때의 출발점이다. */
    private val currentSort: CharacterSort get() = viewModel.sortSpec.value ?: CharacterSort()

    /** 작품 필터 섹션 노출 여부 — 전역 목록에서만 의미(단일 작품 스코프면 중복이라 숨김). */
    private val showNovelSection: Boolean get() = arguments?.getBoolean(ARG_SHOW_NOVEL_SECTION, true) ?: true

    private var _binding: BottomSheetCharacterControlsBinding? = null
    private val binding get() = _binding!!

    /**
     * 회전 전에 고르던 것. `null`은 *되살릴 것이 없다*는 뜻이고, 그때는 지금 적용된 값
     * (뷰모델)이 출발점이 된다. 되살린 자리를 떠나면(세계관·필드를 바꾸면) 버린다.
     */
    private var pendingTags: Set<String>? = null
    private var pendingNovelIds: Set<Long>? = null
    private var pendingUniverseId: Long? = null
    private var pendingFieldId: Long? = null
    private var pendingValues: Set<String> = emptySet()

    /** 시트가 출발점으로 삼는 태그 선택 — 되살릴 것이 있으면 그것이 이긴다. */
    private val startTags: Set<String> get() = pendingTags ?: viewModel.tagFilters.value ?: emptySet()
    private val startNovelIds: Set<Long> get() = pendingNovelIds ?: viewModel.novelFilters.value ?: emptySet()

    private var fields: List<SortableField> = emptyList()
    private var duelAxes: List<SortableDuelAxis> = emptyList()
    private var universes: List<Universe> = emptyList()
    private var filterFields: List<FieldDefinition> = emptyList()
    private var fieldValues: List<String> = emptyList()
    // 세계관/필드 스피너를 빠르게 바꿀 때 이전 로드가 나중에 끝나 새 결과를 덮어써(stale) 엉뚱한 필터가
    // 저장되는 것을 막기 위한 취소 핸들 (기존 필터 시트의 fieldLoadJob 패턴 그대로).
    private var fieldLoadJob: Job? = null
    private var valueLoadJob: Job? = null
    // 작품 칩 비동기 로드 완료 여부 — 로드 전 '적용' 시 childCount=0을 빈 선택으로 오인해 기존 필터를 지우지 않도록.
    private var novelsLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCharacterControlsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.let { state ->
            state.getStringArray(STATE_TAGS)?.let { pendingTags = it.toSet() }
            state.getLongArray(STATE_NOVEL_IDS)?.let { pendingNovelIds = it.toSet() }
            if (state.containsKey(STATE_UNIVERSE_ID)) pendingUniverseId = state.getLong(STATE_UNIVERSE_ID)
            if (state.containsKey(STATE_FIELD_ID)) pendingFieldId = state.getLong(STATE_FIELD_ID)
            pendingValues = state.getStringArray(STATE_VALUES)?.toSet() ?: emptySet()
        }

        setupPresets()
        setupSort()
        if (showNovelSection) {
            setupNovels()
        } else {
            binding.novelSection.visibility = View.GONE
        }
        setupTags()
        setupUniverseSpinner()

        binding.btnClearAllFilters.setOnClickListener {
            viewModel.clearAllFilters()
            dismiss()
        }
        binding.btnApply.setOnClickListener { applyAll() }
    }

    // ===== 프리셋 =====

    private fun setupPresets() {
        // 창을 띄우는 것은 호스트의 일이다 — 시트는 *무엇을 눌렀는지*만 건넨다(R-65).
        binding.btnSavePreset.setOnClickListener { sendAction(ACTION_SAVE_PRESET) }
        // 뷰모델의 LiveData를 그대로 관찰 — 이름변경/삭제 후 시트가 열려 있어도 목록이 살아있다.
        viewModel.presets.observe(viewLifecycleOwner) { presets ->
            val b = _binding ?: return@observe
            val ctx = context ?: return@observe
            b.presetChipGroupSheet.removeAllViews()
            for (p in presets.orEmpty()) {
                b.presetChipGroupSheet.addView(Chip(ctx).apply {
                    text = p.name
                    setOnClickListener {
                        viewModel.applyPreset(p)
                        dismiss()
                    }
                    setOnLongClickListener { sendAction(ACTION_PRESET_OPTIONS, p.id); true }
                })
            }
        }
    }

    /** 호스트가 할 일을 결과로 건넨다 — 람다를 꽂지 않으므로 회전이 지울 배선이 없다. */
    private fun sendAction(action: String, presetId: Long = 0L) {
        setFragmentResult(RESULT_KEY, Bundle().apply {
            putString(RESULT_ACTION, action)
            if (presetId != 0L) putLong(RESULT_PRESET_ID, presetId)
        })
    }

    // ===== 정렬 (CharacterSortBottomSheet 로직 승계 + 방향 토글) =====

    private fun setupSort() {
        binding.sortKindGroup.check(when (currentSort.kind) {
            CharacterListPreset.SORT_NAME -> binding.rbName.id
            CharacterListPreset.SORT_CREATED -> binding.rbCreated.id
            CharacterListPreset.SORT_RECENT -> binding.rbRecent.id
            CharacterListPreset.SORT_FIELD -> binding.rbField.id
            CharacterListPreset.SORT_DUEL -> binding.rbDuel.id
            else -> binding.rbManual.id
        })
        binding.dirToggleGroup.check(if (currentSort.ascending) binding.btnDirAsc.id else binding.btnDirDesc.id)

        binding.sortKindGroup.setOnCheckedChangeListener { _, _ ->
            updateFieldVisibility()
            // 기준이 바뀌면 합리적 기본 방향을 제안(기준 유지 시 현재 방향 보존) — 이후 토글로 조정 가능
            val (kind, fieldKey) = selectedSortKind()
            binding.dirToggleGroup.check(
                if (defaultAscending(kind, fieldKey)) binding.btnDirAsc.id else binding.btnDirDesc.id
            )
        }
        binding.spinnerSortField.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = updateBodyPartVisibility()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadSortFields()
        loadSortDuelAxes()
    }

    /**
     * 대결 축 목록(B-117). 축이 없으면 선택지를 꺼서 **고를 수 없는 것을 고르게 두지 않는다.**
     *
     * 축 이름 뒤에 판 수를 적는다 — 한 판도 없는 축을 고르면 목록이 그대로이고, 그것을
     * 고르기 **전에** 알 수 있어야 사용자가 "정렬이 고장 났다"고 읽지 않는다(원칙 04).
     */
    private fun loadSortDuelAxes() {
        viewLifecycleOwner.lifecycleScope.launch {
            duelAxes = viewModel.getSortableDuelAxes()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (duelAxes.isEmpty()) {
                binding.rbDuel.isEnabled = false
                binding.rbDuel.text = getString(R.string.sort_duel_none)
            } else {
                // 세계관 이름을 함께 적는 것은 전역 목록에서 축 이름이 겹치기 때문이다
                // ('강함'은 어느 세계관에나 있다).
                binding.spinnerSortDuel.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    duelAxes.map { axis ->
                        getString(R.string.sort_duel_axis_item, axis.name, axis.universeName, axis.matches)
                    }
                )
                val idx = duelAxes.indexOfFirst { it.code == currentSort.duelAxisCode }
                if (idx >= 0) binding.spinnerSortDuel.setSelection(idx)
            }
            updateFieldVisibility()
        }
    }

    private fun loadSortFields() {
        viewLifecycleOwner.lifecycleScope.launch {
            fields = viewModel.getSortableFields()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (fields.isEmpty()) {
                binding.rbField.isEnabled = false
                binding.rbField.text = getString(R.string.sort_field_none)
            } else {
                binding.spinnerSortField.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item, fields.map { it.name }
                )
                val idx = fields.indexOfFirst { it.key == currentSort.fieldKey }
                if (idx >= 0) binding.spinnerSortField.setSelection(idx)
            }
            updateFieldVisibility()
        }
    }

    private fun updateFieldVisibility() {
        val isField = binding.sortKindGroup.checkedRadioButtonId == binding.rbField.id
        val isDuel = binding.sortKindGroup.checkedRadioButtonId == binding.rbDuel.id
        val isManual = binding.sortKindGroup.checkedRadioButtonId == binding.rbManual.id
        val vis = if (isField && fields.isNotEmpty()) View.VISIBLE else View.GONE
        binding.sortFieldLabel.visibility = vis
        binding.spinnerSortField.visibility = vis
        val duelVis = if (isDuel && duelAxes.isNotEmpty()) View.VISIBLE else View.GONE
        binding.sortDuelLabel.visibility = duelVis
        binding.spinnerSortDuel.visibility = duelVis
        // 수동 정렬은 방향 개념이 없다
        binding.dirToggleGroup.visibility = if (isManual) View.GONE else View.VISIBLE
        updateBodyPartVisibility()
    }

    private fun updateBodyPartVisibility() {
        val isField = binding.sortKindGroup.checkedRadioButtonId == binding.rbField.id
        val field = if (isField) fields.getOrNull(binding.spinnerSortField.selectedItemPosition) else null
        if (field != null && field.fieldType == FieldType.BODY_SIZE && field.bodySizePartLabels.isNotEmpty()) {
            val ctx = context ?: return
            binding.spinnerBodyPart.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, field.bodySizePartLabels
            )
            currentSort.bodySizePartIndex?.let { if (it < field.bodySizePartLabels.size) binding.spinnerBodyPart.setSelection(it) }
            binding.sortBodyPartLabel.visibility = View.VISIBLE
            binding.spinnerBodyPart.visibility = View.VISIBLE
        } else {
            binding.sortBodyPartLabel.visibility = View.GONE
            binding.spinnerBodyPart.visibility = View.GONE
        }
    }

    private fun selectedSortKind(): Pair<String, String?> {
        val kind = when (binding.sortKindGroup.checkedRadioButtonId) {
            binding.rbName.id -> CharacterListPreset.SORT_NAME
            binding.rbCreated.id -> CharacterListPreset.SORT_CREATED
            binding.rbRecent.id -> CharacterListPreset.SORT_RECENT
            binding.rbField.id -> if (fields.isEmpty()) CharacterListPreset.SORT_MANUAL else CharacterListPreset.SORT_FIELD
            binding.rbDuel.id -> if (duelAxes.isEmpty()) CharacterListPreset.SORT_MANUAL else CharacterListPreset.SORT_DUEL
            else -> CharacterListPreset.SORT_MANUAL
        }
        val fieldKey = if (kind == CharacterListPreset.SORT_FIELD)
            fields.getOrNull(binding.spinnerSortField.selectedItemPosition)?.key else null
        return kind to fieldKey
    }

    /** 고른 대결 축의 코드 — 대결 정렬이 아니면 null이다(정렬을 바꿔도 옛 축이 따라붙지 않는다). */
    private fun selectedDuelAxisCode(): String? {
        if (binding.sortKindGroup.checkedRadioButtonId != binding.rbDuel.id) return null
        return duelAxes.getOrNull(binding.spinnerSortDuel.selectedItemPosition)?.code
    }

    /** 기준 유지 시 현재 방향 보존, 바뀌면 합리적 기본값 (기존 시트 로직 그대로). */
    private fun defaultAscending(kind: String, fieldKey: String?): Boolean {
        if (kind == currentSort.kind && fieldKey == currentSort.fieldKey) return currentSort.ascending
        return when (kind) {
            CharacterListPreset.SORT_NAME -> true          // 가나다
            CharacterListPreset.SORT_CREATED -> false      // 최신순
            CharacterListPreset.SORT_RECENT -> false       // 최근 수정순
            CharacterListPreset.SORT_FIELD -> false        // 높은 값 먼저
            CharacterListPreset.SORT_DUEL -> false         // 센 쪽 먼저
            else -> true
        }
    }

    // ===== 필터 (CharacterFilterBottomSheet 로직 승계) =====

    private fun setupNovels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val novels = viewModel.getAllNovelsList()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            novelsLoaded = true  // 로드 완료(빈 목록 포함) — 이후 apply()는 칩 상태를 신뢰할 수 있음
            // "작품 미배정" — novelId 없는 캐릭터 선택 (sentinel, 수집부는 tag 기반이라 그대로 동작).
            // 작품이 0개여도 항상 생성한다 — 없으면 저장된 sentinel 필터가 '적용' 시
            // 빈 칩 수집으로 무음 소거된다 (변수 제어)
            val noneId = com.novelcharacter.app.util.UnassignedFilter.NO_NOVEL_ID
            binding.novelChipGroup.addView(Chip(ctx).apply {
                text = getString(R.string.stats_no_novel_assigned)
                tag = noneId
                isCheckable = true
                isChecked = noneId in startNovelIds
            })
            if (novels.isEmpty()) {
                binding.noNovelsText.visibility = View.VISIBLE
                return@launch
            }
            binding.noNovelsText.visibility = View.GONE
            for (novel in novels) {
                val chip = Chip(ctx).apply {
                    text = novel.title
                    tag = novel.id  // 칩 → 작품 id 매핑
                    isCheckable = true
                    isChecked = novel.id in startNovelIds
                }
                binding.novelChipGroup.addView(chip)
            }
        }
    }

    private fun setupTags() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = viewModel.getAllDistinctTags()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (tags.isEmpty()) {
                binding.noTagsText.visibility = View.VISIBLE
                return@launch
            }
            binding.noTagsText.visibility = View.GONE
            for (tag in tags) {
                val chip = Chip(ctx).apply {
                    text = tag
                    isCheckable = true
                    isChecked = tag in startTags
                }
                binding.tagChipGroup.addView(chip)
            }
        }
    }

    private fun setupUniverseSpinner() {
        viewLifecycleOwner.lifecycleScope.launch {
            universes = viewModel.getScopedUniverses()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (universes.isEmpty()) {
                binding.spinnerUniverse.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.search_filter_no_universes))
                )
                return@launch
            }
            binding.spinnerUniverse.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, universes.map { it.name }
            )
            binding.spinnerUniverse.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    val universe = universes.getOrNull(position) ?: return
                    // 되살릴 자리를 떠나는 순간 들고 있던 선택을 버린다 — 안 버리면 다른
                    // 세계관의 목록에 옛 선택이 되살아난다(같은 이름의 값이 겹칠 수 있다).
                    if (universe.id != pendingUniverseId) {
                        pendingUniverseId = null
                        pendingFieldId = null
                        pendingValues = emptySet()
                    }
                    loadFieldsForUniverse(universe.id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            // 되살리기는 **여러 번 불려도 같은 결과**여야 한다 — 스피너의 첫 선택 통지도 같은 자리를 부른다.
            val restoreIndex = pendingUniverseId?.let { id -> universes.indexOfFirst { it.id == id } } ?: -1
            if (restoreIndex > 0) binding.spinnerUniverse.setSelection(restoreIndex)
            loadFieldsForUniverse(universes[restoreIndex.coerceAtLeast(0)].id)
        }
    }

    private fun loadFieldsForUniverse(universeId: Long) {
        // 이전 필드 로드와, 그에 딸린 값 로드까지 취소 — 새 세계관 결과 위에 옛 값이 얹히지 않게.
        fieldLoadJob?.cancel()
        valueLoadJob?.cancel()
        fieldLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            filterFields = viewModel.getFilterableFields(universeId)
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (filterFields.isEmpty()) {
                binding.spinnerField.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.search_filter_no_fields))
                )
                binding.valueChipGroup.removeAllViews()
                return@launch
            }
            binding.spinnerField.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, filterFields.map { it.name }
            )
            binding.spinnerField.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    val field = filterFields.getOrNull(position) ?: return
                    if (field.id != pendingFieldId) {
                        pendingFieldId = null
                        pendingValues = emptySet()
                    }
                    loadValuesForField(field.id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            val restoreIndex = pendingFieldId?.let { id -> filterFields.indexOfFirst { it.id == id } } ?: -1
            if (restoreIndex > 0) binding.spinnerField.setSelection(restoreIndex)
            loadValuesForField(filterFields[restoreIndex.coerceAtLeast(0)].id)
        }
    }

    private fun loadValuesForField(fieldDefId: Long) {
        valueLoadJob?.cancel()
        valueLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            fieldValues = viewModel.getFieldValues(fieldDefId)
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            binding.valueChipGroup.removeAllViews()
            if (fieldValues.isEmpty()) {
                binding.emptyValuesText.visibility = View.VISIBLE
                return@launch
            }
            binding.emptyValuesText.visibility = View.GONE
            for (value in fieldValues) {
                val chip = Chip(ctx).apply {
                    text = value
                    isCheckable = true
                    // 회전 전에 고른 값은 그대로 골라진 채로 선다(칩은 동적이라 뷰 상태 저장이 못 든다).
                    isChecked = value in pendingValues
                }
                binding.valueChipGroup.addView(chip)
            }
        }
    }

    // ===== 적용 =====

    private fun applyAll() {
        // 정렬: 기준 + 토글의 방향
        val (kind, fieldKey) = selectedSortKind()
        val field = if (kind == CharacterListPreset.SORT_FIELD)
            fields.getOrNull(binding.spinnerSortField.selectedItemPosition) else null
        val partIndex = if (field?.fieldType == FieldType.BODY_SIZE) binding.spinnerBodyPart.selectedItemPosition.takeIf { it >= 0 } else null
        val ascending = if (kind == CharacterListPreset.SORT_MANUAL) true
                        else binding.dirToggleGroup.checkedButtonId == binding.btnDirAsc.id
        val sort = CharacterSort(kind, fieldKey, ascending, partIndex, selectedDuelAxisCode())

        // 작품: 체크된 칩의 tag(작품 id) 수집. 섹션 숨김이거나 로드 전이면 기존 선택 유지(소실 방지).
        val selectedNovelIds = if (showNovelSection && novelsLoaded) {
            val ids = mutableSetOf<Long>()
            for (i in 0 until binding.novelChipGroup.childCount) {
                (binding.novelChipGroup.getChildAt(i) as? Chip)?.let {
                    if (it.isChecked) (it.tag as? Long)?.let { id -> ids.add(id) }
                }
            }
            ids
        } else startNovelIds
        // 태그: 시트의 선택 상태를 통째로 반영
        val selectedTags = mutableSetOf<String>()
        for (i in 0 until binding.tagChipGroup.childCount) {
            (binding.tagChipGroup.getChildAt(i) as? Chip)?.let { if (it.isChecked) selectedTags.add(it.text.toString()) }
        }
        // 필드 값: 필드+값이 선택돼 있으면 필터 하나 생성
        val filterField = filterFields.getOrNull(binding.spinnerField.selectedItemPosition)
        val selectedValues = mutableListOf<String>()
        for (i in 0 until binding.valueChipGroup.childCount) {
            (binding.valueChipGroup.getChildAt(i) as? Chip)?.let { if (it.isChecked) selectedValues.add(it.text.toString()) }
        }
        val filter = if (filterField != null && selectedValues.isNotEmpty()) {
            val matchMode = if (binding.radioContains.isChecked) "contains" else "exact"
            // 키를 함께 싣는다(B-11) — 정렬이 이미 키로 병합되므로 필터도 같은 잣대를 쓴다.
            FieldFilter(filterField.id, filterField.name, selectedValues, matchMode, filterField.key)
        } else null

        // 처분은 곧장 뷰모델에 쓴다 — 호스트의 람다를 거치면 회전 뒤 그 람다가 없다.
        viewModel.setSortSpec(sort)
        viewModel.setTagFilters(selectedTags)
        viewModel.setNovelFilters(selectedNovelIds)
        if (filter != null) viewModel.addFieldFilter(filter)
        dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val b = _binding ?: return
        outState.putStringArray(STATE_TAGS, checkedTexts(b.tagChipGroup).toTypedArray())
        if (showNovelSection && novelsLoaded) {
            val ids = (0 until b.novelChipGroup.childCount)
                .mapNotNull { b.novelChipGroup.getChildAt(it) as? Chip }
                .filter { it.isChecked }
                .mapNotNull { it.tag as? Long }
            outState.putLongArray(STATE_NOVEL_IDS, ids.toLongArray())
        }
        universes.getOrNull(b.spinnerUniverse.selectedItemPosition)?.let {
            outState.putLong(STATE_UNIVERSE_ID, it.id)
        }
        filterFields.getOrNull(b.spinnerField.selectedItemPosition)?.let {
            outState.putLong(STATE_FIELD_ID, it.id)
        }
        outState.putStringArray(STATE_VALUES, checkedTexts(b.valueChipGroup).toTypedArray())
    }

    private fun checkedTexts(group: android.view.ViewGroup): List<String> =
        (0 until group.childCount)
            .mapNotNull { group.getChildAt(it) as? Chip }
            .filter { it.isChecked }
            .map { it.text.toString() }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CharacterListControlsBottomSheet"

        /**
         * 호스트가 해야 하는 일만 이 채널로 건넨다(창 띄우기 — 시트는 창의 결과를 받을 자리가 없다).
         * 나머지 처분은 시트가 뷰모델에 곧장 쓴다.
         */
        const val RESULT_KEY = "character_list_controls_result"
        const val RESULT_ACTION = "action"
        const val RESULT_PRESET_ID = "presetId"
        const val ACTION_SAVE_PRESET = "save_preset"
        const val ACTION_PRESET_OPTIONS = "preset_options"

        private const val ARG_SHOW_NOVEL_SECTION = "arg_show_novel_section"
        private const val STATE_TAGS = "state_tags"
        private const val STATE_NOVEL_IDS = "state_novel_ids"
        private const val STATE_UNIVERSE_ID = "state_universe_id"
        private const val STATE_FIELD_ID = "state_field_id"
        private const val STATE_VALUES = "state_values"

        /** @param showNovelSection 전역 목록에서만 참 — 단일 작품 화면에서는 중복이라 숨긴다. */
        fun newInstance(showNovelSection: Boolean) = CharacterListControlsBottomSheet().apply {
            arguments = Bundle().apply { putBoolean(ARG_SHOW_NOVEL_SECTION, showNovelSection) }
        }
    }
}
