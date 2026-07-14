package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.BottomSheetCharacterFilterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 캐릭터 목록 필터 시트 — 태그(다중 선택) + 필드 값(세계관→필드→값, exact/contains)을 한 시트에.
 * `SearchFilterBottomSheet`의 필드-값 흐름을 답습하되 중첩 시트 없이 태그까지 한 곳에서 처리한다.
 * 적용 시 태그 집합을 통째로 반영하고, 필드+값이 선택돼 있으면 필드 필터 하나를 추가한다.
 */
class CharacterFilterBottomSheet : BottomSheetDialogFragment() {

    var currentTags: Set<String> = emptySet()
    var currentNovelIds: Set<Long> = emptySet()
    /** 작품 필터 섹션 노출 여부 — 전역 목록에서만 의미(단일 작품 스코프면 중복이라 숨김). */
    var showNovelSection: Boolean = true
    var loadAllTags: (suspend () -> List<String>)? = null
    var loadNovels: (suspend () -> List<Novel>)? = null
    var loadUniverses: (suspend () -> List<Universe>)? = null
    var loadFields: (suspend (Long) -> List<FieldDefinition>)? = null
    var loadFieldValues: (suspend (Long) -> List<String>)? = null
    var onApply: ((tags: Set<String>, novelIds: Set<Long>, fieldFilter: FieldFilter?) -> Unit)? = null

    private var _binding: BottomSheetCharacterFilterBinding? = null
    private val binding get() = _binding!!

    private var universes: List<Universe> = emptyList()
    private var fields: List<FieldDefinition> = emptyList()
    private var fieldValues: List<String> = emptyList()
    // 세계관/필드 스피너를 빠르게 바꿀 때 이전 로드가 나중에 끝나 새 결과를 덮어써(stale) 엉뚱한 필터가
    // 저장되는 것을 막기 위한 취소 핸들. BatchStateChangeBottomSheet의 fieldLoadJob 패턴과 동일.
    private var fieldLoadJob: Job? = null
    private var valueLoadJob: Job? = null
    // 작품 칩 비동기 로드 완료 여부 — 로드 전 '적용' 시 childCount=0을 빈 선택으로 오인해 기존 필터를 지우지 않도록.
    private var novelsLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCharacterFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (showNovelSection) {
            setupNovels()
        } else {
            binding.novelSection.visibility = View.GONE
        }
        setupTags()
        setupUniverseSpinner()
        binding.btnApplyFilter.setOnClickListener { apply() }
    }

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
                    textSize = 13f
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
                    textSize = 13f
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
            fields = loadFields?.invoke(universeId) ?: emptyList()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (fields.isEmpty()) {
                binding.spinnerField.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.search_filter_no_fields))
                )
                binding.valueChipGroup.removeAllViews()
                return@launch
            }
            binding.spinnerField.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, fields.map { it.name }
            )
            binding.spinnerField.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    fields.getOrNull(position)?.let { loadValuesForField(it.id) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            loadValuesForField(fields[0].id)
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
                    textSize = 13f
                }
                binding.valueChipGroup.addView(chip)
            }
        }
    }

    private fun apply() {
        // 작품: 체크된 칩의 tag(작품 id) 수집. 섹션이 숨겨졌으면 기존 선택 유지.
        val selectedNovelIds = if (showNovelSection && novelsLoaded) {
            val ids = mutableSetOf<Long>()
            for (i in 0 until binding.novelChipGroup.childCount) {
                (binding.novelChipGroup.getChildAt(i) as? Chip)?.let {
                    if (it.isChecked) (it.tag as? Long)?.let { id -> ids.add(id) }
                }
            }
            ids
        } else currentNovelIds  // 섹션 숨김이거나 아직 로드 전 — 기존 선택 유지(소실 방지)
        // 태그: 시트의 선택 상태를 통째로 반영
        val selectedTags = mutableSetOf<String>()
        for (i in 0 until binding.tagChipGroup.childCount) {
            (binding.tagChipGroup.getChildAt(i) as? Chip)?.let { if (it.isChecked) selectedTags.add(it.text.toString()) }
        }
        // 필드 값: 필드+값이 선택돼 있으면 필터 하나 생성
        val field = fields.getOrNull(binding.spinnerField.selectedItemPosition)
        val selectedValues = mutableListOf<String>()
        for (i in 0 until binding.valueChipGroup.childCount) {
            (binding.valueChipGroup.getChildAt(i) as? Chip)?.let { if (it.isChecked) selectedValues.add(it.text.toString()) }
        }
        val filter = if (field != null && selectedValues.isNotEmpty()) {
            val matchMode = if (binding.radioContains.isChecked) "contains" else "exact"
            FieldFilter(field.id, field.name, selectedValues, matchMode)
        } else null

        onApply?.invoke(selectedTags, selectedNovelIds, filter)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "CharacterFilterBottomSheet" }
}
