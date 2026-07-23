package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.BottomSheetCharacterControlsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 통합 목록 컨트롤 시트 — 프리셋 / 정렬(기준+방향) / 필터를 한 표면에 담는다.
 * 기존 CharacterSortBottomSheet·CharacterFilterBottomSheet의 로직을 그대로 승계했고,
 * 정렬 방향이 드디어 기준 옆(토글 그룹)에 위치한다. '적용' 한 번으로 정렬+필터가
 * 함께 반영된다(원칙 04: 최소 단계). 프리셋은 탭 즉시 적용·닫기(최속 경로).
 */
class CharacterListControlsBottomSheet : BottomSheetDialogFragment() {

    // ===== 정렬 (CharacterSortBottomSheet 승계) =====
    var currentSort: CharacterSort = CharacterSort()
    var loadSortableFields: (suspend () -> List<SortableField>)? = null

    // ===== 필터 (CharacterFilterBottomSheet 승계) =====
    var currentTags: Set<String> = emptySet()
    var currentNovelIds: Set<Long> = emptySet()
    /** 작품 필터 섹션 노출 여부 — 전역 목록에서만 의미(단일 작품 스코프면 중복이라 숨김). */
    var showNovelSection: Boolean = true
    var loadAllTags: (suspend () -> List<String>)? = null
    var loadNovels: (suspend () -> List<Novel>)? = null
    var loadUniverses: (suspend () -> List<Universe>)? = null
    var loadFields: (suspend (Long) -> List<FieldDefinition>)? = null
    var loadFieldValues: (suspend (Long) -> List<String>)? = null

    /** 적용: 정렬과 필터를 한 번에 반영 (콜백 표면 = 기존 두 시트의 합집합) */
    var onApplyAll: ((sort: CharacterSort, tags: Set<String>, novelIds: Set<Long>, fieldFilter: FieldFilter?) -> Unit)? = null
    var onClearAllFilters: (() -> Unit)? = null

    // ===== 프리셋 =====
    /** 프래그먼트의 LiveData를 그대로 관찰 — 이름변경/삭제 후 시트가 열려 있어도 목록이 살아있게 */
    var presetsLive: LiveData<List<CharacterListPreset>>? = null
    var onApplyPreset: ((CharacterListPreset) -> Unit)? = null
    var onPresetLongPress: ((CharacterListPreset) -> Unit)? = null
    var onSavePreset: (() -> Unit)? = null

    private var _binding: BottomSheetCharacterControlsBinding? = null
    private val binding get() = _binding!!

    private var fields: List<SortableField> = emptyList()
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
            onClearAllFilters?.invoke()
            dismiss()
        }
        binding.btnApply.setOnClickListener { applyAll() }
    }

    // ===== 프리셋 =====

    private fun setupPresets() {
        binding.btnSavePreset.setOnClickListener { onSavePreset?.invoke() }
        presetsLive?.observe(viewLifecycleOwner) { presets ->
            val b = _binding ?: return@observe
            val ctx = context ?: return@observe
            b.presetChipGroupSheet.removeAllViews()
            for (p in presets.orEmpty()) {
                b.presetChipGroupSheet.addView(Chip(ctx).apply {
                    text = p.name
                    setOnClickListener {
                        onApplyPreset?.invoke(p)
                        dismiss()
                    }
                    setOnLongClickListener { onPresetLongPress?.invoke(p); true }
                })
            }
        }
    }

    // ===== 정렬 (CharacterSortBottomSheet 로직 승계 + 방향 토글) =====

    private fun setupSort() {
        binding.sortKindGroup.check(when (currentSort.kind) {
            CharacterListPreset.SORT_NAME -> binding.rbName.id
            CharacterListPreset.SORT_CREATED -> binding.rbCreated.id
            CharacterListPreset.SORT_RECENT -> binding.rbRecent.id
            CharacterListPreset.SORT_FIELD -> binding.rbField.id
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
    }

    private fun loadSortFields() {
        viewLifecycleOwner.lifecycleScope.launch {
            fields = loadSortableFields?.invoke() ?: emptyList()
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
        val isManual = binding.sortKindGroup.checkedRadioButtonId == binding.rbManual.id
        val vis = if (isField && fields.isNotEmpty()) View.VISIBLE else View.GONE
        binding.sortFieldLabel.visibility = vis
        binding.spinnerSortField.visibility = vis
        // 수동 정렬은 방향 개념이 없다
        binding.dirToggleGroup.visibility = if (isManual) View.GONE else View.VISIBLE
        updateBodyPartVisibility()
    }

    private fun updateBodyPartVisibility() {
        val isField = binding.sortKindGroup.checkedRadioButtonId == binding.rbField.id
        val field = if (isField) fields.getOrNull(binding.spinnerSortField.selectedItemPosition) else null
        if (field != null && field.type == "BODY_SIZE" && field.bodySizePartLabels.isNotEmpty()) {
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
            else -> CharacterListPreset.SORT_MANUAL
        }
        val fieldKey = if (kind == CharacterListPreset.SORT_FIELD)
            fields.getOrNull(binding.spinnerSortField.selectedItemPosition)?.key else null
        return kind to fieldKey
    }

    /** 기준 유지 시 현재 방향 보존, 바뀌면 합리적 기본값 (기존 시트 로직 그대로). */
    private fun defaultAscending(kind: String, fieldKey: String?): Boolean {
        if (kind == currentSort.kind && fieldKey == currentSort.fieldKey) return currentSort.ascending
        return when (kind) {
            CharacterListPreset.SORT_NAME -> true          // 가나다
            CharacterListPreset.SORT_CREATED -> false      // 최신순
            CharacterListPreset.SORT_RECENT -> false       // 최근 수정순
            CharacterListPreset.SORT_FIELD -> false        // 높은 값 먼저
            else -> true
        }
    }

    // ===== 필터 (CharacterFilterBottomSheet 로직 승계) =====

    private fun setupNovels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val novels = loadNovels?.invoke() ?: emptyList()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            novelsLoaded = true  // 로드 완료(빈 목록 포함) — 이후 apply()는 칩 상태를 신뢰할 수 있음
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
                    isChecked = novel.id in currentNovelIds
                }
                binding.novelChipGroup.addView(chip)
            }
        }
    }

    private fun setupTags() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = loadAllTags?.invoke() ?: emptyList()
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
                    isChecked = tag in currentTags
                }
                binding.tagChipGroup.addView(chip)
            }
        }
    }

    private fun setupUniverseSpinner() {
        viewLifecycleOwner.lifecycleScope.launch {
            universes = loadUniverses?.invoke() ?: emptyList()
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
                    universes.getOrNull(position)?.let { loadFieldsForUniverse(it.id) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            loadFieldsForUniverse(universes[0].id)
        }
    }

    private fun loadFieldsForUniverse(universeId: Long) {
        // 이전 필드 로드와, 그에 딸린 값 로드까지 취소 — 새 세계관 결과 위에 옛 값이 얹히지 않게.
        fieldLoadJob?.cancel()
        valueLoadJob?.cancel()
        fieldLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            filterFields = loadFields?.invoke(universeId) ?: emptyList()
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
                    filterFields.getOrNull(position)?.let { loadValuesForField(it.id) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            loadValuesForField(filterFields[0].id)
        }
    }

    private fun loadValuesForField(fieldDefId: Long) {
        valueLoadJob?.cancel()
        valueLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            fieldValues = loadFieldValues?.invoke(fieldDefId) ?: emptyList()
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
                    isChecked = false
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
        val partIndex = if (field?.type == "BODY_SIZE") binding.spinnerBodyPart.selectedItemPosition.takeIf { it >= 0 } else null
        val ascending = if (kind == CharacterListPreset.SORT_MANUAL) true
                        else binding.dirToggleGroup.checkedButtonId == binding.btnDirAsc.id
        val sort = CharacterSort(kind, fieldKey, ascending, partIndex)

        // 작품: 체크된 칩의 tag(작품 id) 수집. 섹션 숨김이거나 로드 전이면 기존 선택 유지(소실 방지).
        val selectedNovelIds = if (showNovelSection && novelsLoaded) {
            val ids = mutableSetOf<Long>()
            for (i in 0 until binding.novelChipGroup.childCount) {
                (binding.novelChipGroup.getChildAt(i) as? Chip)?.let {
                    if (it.isChecked) (it.tag as? Long)?.let { id -> ids.add(id) }
                }
            }
            ids
        } else currentNovelIds
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
            FieldFilter(filterField.id, filterField.name, selectedValues, matchMode)
        } else null

        onApplyAll?.invoke(sort, selectedTags, selectedNovelIds, filter)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "CharacterListControlsBottomSheet" }
}
