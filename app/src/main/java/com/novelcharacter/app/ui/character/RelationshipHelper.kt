package com.novelcharacter.app.ui.character

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.LayoutInflater
import java.util.Collections
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.util.DetailListSort
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.dismissSafely
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.ui.adapter.RelationshipAdapter
import com.novelcharacter.app.ui.adapter.RelationshipDisplayItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RelationshipHelper(
    private val binding: FragmentCharacterDetailBinding,
    private val viewModel: CharacterViewModel,
    private val viewLifecycleOwner: LifecycleOwner,
    private val characterId: Long,
    private val contextGetter: () -> Context,
    private val getString: (Int) -> String,
    private val getFormattedString: (Int, Array<out Any>) -> String,
    private val navController: () -> NavController,
    private val isBindingAlive: () -> Boolean = { true }
) {
    private lateinit var relationshipAdapter: RelationshipAdapter
    private var relationshipJob: Job? = null
    private var currentRelationships: List<CharacterRelationship> = emptyList()
    private var currentDisplayItems: MutableList<RelationshipDisplayItem> = mutableListOf()

    /**
     * 보기 정렬(B-85). 기본은 저장된 수동 순서이며, **그때만 드래그 재정렬이 열린다** —
     * 정렬된 화면에서 드래그를 허용하면 그 위치가 그대로 `displayOrder`로 저장되어
     * 사용자가 손으로 잡아 둔 순서가 지워지기 때문이다(판정 근거는 `DetailListSort`).
     */
    private var sortMode = DetailListSort.RelationshipMode.MANUAL

    /** 정렬 적용 전의 순서 = DB의 `displayOrder` 순서. 저장은 언제나 이 목록을 기준으로 한다. */
    private var manualOrderItems: List<RelationshipDisplayItem> = emptyList()

    fun setup() {
        relationshipAdapter = RelationshipAdapter(
            onClick = { item ->
                val bundle = Bundle().apply { putLong("characterId", item.otherCharacterId) }
                navController().navigateSafe(R.id.characterDetailFragment, R.id.characterDetailFragment, bundle)
            },
            onLongClick = { item ->
                showRelationshipOptionsDialog(item)
            }
        )
        binding.relationshipsRecyclerView.layoutManager = LinearLayoutManager(contextGetter())
        binding.relationshipsRecyclerView.adapter = relationshipAdapter

        // 드래그앤드롭 정렬
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            // 비기본 정렬에서는 드래그 자체를 닫는다. onMove에서 막으면 항목이 손에 붙었다가
            // 제자리로 튀어 "되는데 안 먹는" 것처럼 보이므로, 잡히지도 않게 하는 쪽이 정직하다.
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
                if (from < 0 || to < 0 || from >= currentDisplayItems.size || to >= currentDisplayItems.size) return false
                Collections.swap(currentDisplayItems, from, to)
                relationshipAdapter.submitList(currentDisplayItems.toList())
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 드래그 종료 시 순서를 DB에 저장
                saveDisplayOrder()
            }
        })
        touchHelper.attachToRecyclerView(binding.relationshipsRecyclerView)

        binding.btnAddRelationship.setOnClickListener {
            showAddRelationshipDialog()
        }
        sortMode = CharacterDetailSortPrefs.relationshipMode(contextGetter())
        binding.btnSortRelationships.setOnClickListener { showSortMenu(it) }
        updateSortUi()
    }

    // ── 보기 정렬(B-85) ──

    private fun showSortMenu(anchor: View) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        val modes = DetailListSort.RelationshipMode.values()
        android.widget.PopupMenu(context, anchor).apply {
            modes.forEachIndexed { i, m -> menu.add(0, i, i, sortLabelRes(m)) }
            setOnMenuItemClickListener { item ->
                sortMode = modes[item.itemId]
                CharacterDetailSortPrefs.setRelationshipMode(context, sortMode)
                updateSortUi()
                render()
                true
            }
            show()
        }
    }

    private fun updateSortUi() {
        binding.btnSortRelationships.setText(sortLabelRes(sortMode))
        // 드래그가 닫힌 사유와 되돌릴 길을 함께 보여 준다 — 잠근 사실만 알면 "왜 안 되지"가 남는다.
        binding.textRelationshipSortNote.visibility =
            if (sortMode.allowsManualReorder) View.GONE else View.VISIBLE
    }

    private fun sortLabelRes(mode: DetailListSort.RelationshipMode): Int = when (mode) {
        // 캐릭터 목록의 수동 정렬과 같은 라벨을 쓴다 — 드래그로 순서를 잡는 목록이 둘뿐인데
        // 서로 다른 이름을 쓰면 같은 것을 다른 것으로 읽는다(sort_default_order는 정렬이 없는 화면의 말이다).
        DetailListSort.RelationshipMode.MANUAL -> R.string.sort_manual
        DetailListSort.RelationshipMode.NAME -> R.string.sort_name
        DetailListSort.RelationshipMode.INTENSITY -> R.string.sort_intensity
        DetailListSort.RelationshipMode.TYPE -> R.string.sort_relationship_type
    }

    /**
     * 화면 다시 그리기 — 저장 순서([manualOrderItems])는 그대로 두고 보이는 순서만 만든다.
     * 정렬을 바꿔도 DB를 읽지 않는다(원칙 04) — 재정렬은 보기 설정이지 데이터 변경이 아니다.
     */
    private fun render() {
        val cmp = DetailListSort.relationships<RelationshipDisplayItem>(
            sortMode, { it.otherCharacterName }, { it.intensity }, { it.relationshipType }
        )
        val shown = if (cmp == null) manualOrderItems else manualOrderItems.sortedWith(cmp)
        // 드래그가 열린 기본 순서에서만 이 목록이 저장 대상이 된다(getDragDirs가 그것을 보장).
        currentDisplayItems = shown.toMutableList()
        relationshipAdapter.submitList(shown)
        if (!isBindingAlive()) return
        binding.textNoRelationships.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.relationshipsRecyclerView.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun saveDisplayOrder() {
        // 보기 정렬 중에는 저장하지 않는다 — 그때 화면 순서는 저장 순서가 아니라 정렬 결과이고,
        // 그대로 쓰면 사용자가 손으로 잡아 둔 순서를 덮어 버린다. getDragDirs가 이미 드래그를
        // 닫아 두었지만 **쓰는 자리에서 한 번 더** 막는다 — 순서 유실은 되돌릴 방법이 없다.
        if (!sortMode.allowsManualReorder) return
        val updatedRelationships = currentDisplayItems.mapIndexedNotNull { index, displayItem ->
            currentRelationships.find { it.id == displayItem.relationshipId }
                ?.copy(displayOrder = index)
        }
        if (updatedRelationships.isNotEmpty()) {
            viewModel.updateRelationshipOrders(updatedRelationships)
        }
    }

    fun observe() {
        viewModel.getRelationshipsForCharacter(characterId).observe(viewLifecycleOwner) { relationships ->
            relationshipJob?.cancel()
            relationshipJob = viewLifecycleOwner.lifecycleScope.launch {
                currentRelationships = relationships
                val displayItems = relationships.mapNotNull { rel ->
                    val otherId = if (rel.characterId1 == characterId) rel.characterId2 else rel.characterId1
                    val otherChar = viewModel.getCharacterByIdSuspend(otherId)
                    if (otherChar != null) {
                        RelationshipDisplayItem(
                            relationshipId = rel.id,
                            otherCharacterName = otherChar.name,
                            otherCharacterId = otherId,
                            relationshipType = rel.relationshipType,
                            description = rel.description,
                            intensity = rel.intensity,
                            isBidirectional = rel.isBidirectional
                        )
                    } else null
                }
                // DAO가 준 순서(displayOrder)가 곧 저장 순서다 — 정렬은 이 목록을 건드리지 않는다.
                manualOrderItems = displayItems
                render()
            }
        }
    }

    fun cancelJob() {
        relationshipJob?.cancel()
        relationshipJob = null
    }

    private fun showAddRelationshipDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val allCharacters = viewModel.getAllCharactersList()
            val currentChar = viewModel.getCharacterByIdSuspend(characterId)
            val currentNovelId = currentChar?.novelId
            val otherCharacters = allCharacters.filter { it.id != characterId }

            val context = try { contextGetter() } catch (_: Exception) { return@launch }

            if (otherCharacters.isEmpty()) {
                Toast.makeText(context, getString(R.string.no_other_characters), Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 같은 작품 캐릭터를 상단에, 나머지는 작품명과 함께 표시
            val novels = viewModel.getAllNovelsList()
            val novelMap = novels.associate { it.id to it.title }
            val sorted = otherCharacters.sortedWith(compareBy<Character> {
                if (it.novelId == currentNovelId && currentNovelId != null) 0 else 1
            }.thenBy { it.name })
            val charDisplayNames = sorted.map { char ->
                val novelName = char.novelId?.let { novelMap[it] }
                if (novelName != null && char.novelId != currentNovelId) "${char.name} ($novelName)"
                else char.name
            }
            val typeNames = viewModel.getRelationshipTypesForCharacter(characterId).toTypedArray()
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_relationship_edit, null)

            // AutoCompleteTextView 설정
            val layoutCharSearch = dialogView.findViewById<TextInputLayout>(R.id.layoutCharacterSearch)
            val autoComplete = dialogView.findViewById<AutoCompleteTextView>(R.id.autoCompleteCharacter)
            val textTargetName = dialogView.findViewById<TextView>(R.id.textTargetCharacterName)
            val spinnerType = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerRelType)
            val editDesc = dialogView.findViewById<TextInputEditText>(R.id.editRelDescription)
            val sliderIntensity = dialogView.findViewById<Slider>(R.id.sliderIntensity)
            val textIntensityValue = dialogView.findViewById<TextView>(R.id.textIntensityValue)
            val checkBidirectional = dialogView.findViewById<MaterialCheckBox>(R.id.checkBidirectional)

            // 추가 모드: AutoComplete 표시, 편집 모드 텍스트 숨김
            textTargetName.visibility = View.GONE
            layoutCharSearch.visibility = View.VISIBLE

            val charAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, charDisplayNames)
            autoComplete.setAdapter(charAdapter)
            autoComplete.threshold = 1

            spinnerType.adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item, typeNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            sliderIntensity.addOnChangeListener { _, value, _ ->
                textIntensityValue.text = value.toInt().toString()
            }

            var selectedCharIndex = -1
            autoComplete.setOnItemClickListener { _, _, position, _ ->
                // position은 필터링된 어댑터 내 위치 → 원본 리스트에서 인덱스를 찾아야 함
                val selectedName = charAdapter.getItem(position) ?: return@setOnItemClickListener
                selectedCharIndex = charDisplayNames.indexOf(selectedName)
            }
            // 텍스트 변경 시 이전 선택을 무효화하여, 직접 입력 시 올바른 매칭이 이루어지도록 함
            autoComplete.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    selectedCharIndex = -1
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            // B-28: 상대 미매칭도, 중복 관계도 창을 닫아 버려서 고른 유형·설명·강도가 사라졌다.
            // 중복 판정은 비동기라 닫힘이 판정보다 **먼저** 왔다 — 이제 판정을 받고 나서 닫는다.
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.add_relationship))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create()

            var saving = false
            dialog.setValidatedPositiveButton {
                if (saving) return@setValidatedPositiveButton false
                // AutoComplete에서 텍스트로 직접 입력한 경우 매칭 시도
                if (selectedCharIndex < 0) {
                    val typed = autoComplete.text.toString().trim()
                    selectedCharIndex = charDisplayNames.indexOfFirst {
                        it.equals(typed, ignoreCase = true)
                    }
                }
                if (selectedCharIndex < 0 || selectedCharIndex >= sorted.size) {
                    // 종전 문구는 '다른 캐릭터가 없습니다'였는데 실제 사유는 '적은 이름이 목록에 없다'다.
                    layoutCharSearch.showInlineError(getString(R.string.relationship_target_not_found))
                    return@setValidatedPositiveButton false
                }

                val selectedType = typeNames[spinnerType.selectedItemPosition]
                val desc = editDesc.text.toString().trim()
                val intensity = sliderIntensity.value.toInt()
                val bidirectional = checkBidirectional.isChecked
                val otherChar = sorted[selectedCharIndex]

                saving = true
                viewLifecycleOwner.lifecycleScope.launch {
                    // finally로 되돌린다 — 조회가 예외로 끝나면 잠금이 남아 창이 영영 반응하지 않는다.
                    val alreadyExists = try {
                        val existingRels = viewModel.getRelationshipsForCharacterList(characterId)
                        existingRels.any {
                            ((it.characterId1 == characterId && it.characterId2 == otherChar.id) ||
                            (it.characterId1 == otherChar.id && it.characterId2 == characterId)) &&
                            it.relationshipType == selectedType
                        }
                    } finally {
                        saving = false
                    }
                    if (alreadyExists) {
                        // 창을 살려 둔다 — 상대는 그대로 두고 유형만 바꿔 다시 저장할 수 있다.
                        layoutCharSearch.showInlineError(getString(R.string.relationship_already_exists))
                        return@launch
                    }
                    viewModel.insertRelationship(
                        CharacterRelationship(
                            characterId1 = characterId,
                            characterId2 = otherChar.id,
                            relationshipType = selectedType,
                            description = desc,
                            intensity = intensity,
                            isBidirectional = bidirectional
                        )
                    )
                    // 중단 뒤라 뷰가 사라졌을 수 있다 — 윈도우 분리 예외를 무해화한다.
                    dialog.dismissSafely()
                }
                // 저장 여부는 위 코루틴이 정한다 — 여기서 닫으면 중복 판정을 받을 창이 사라진다.
                false
            }
            dialog.show()
        }
    }

    private fun showRelationshipOptionsDialog(item: RelationshipDisplayItem) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        val options = arrayOf(
            getString(R.string.relationship_options_edit),
            getString(R.string.relationship_changes_title),
            getString(R.string.relationship_options_delete)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(getFormattedString(R.string.relationship_title_format, arrayOf(item.otherCharacterName, item.relationshipType)))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditRelationshipDialog(item)
                    1 -> showRelationshipChangesDialog(item)
                    2 -> confirmDeleteRelationship(item)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditRelationshipDialog(item: RelationshipDisplayItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val relationship = viewModel.getRelationshipById(item.relationshipId) ?: return@launch
            val context = try { contextGetter() } catch (_: Exception) { return@launch }

            val typeNames = viewModel.getRelationshipTypesForCharacter(characterId).toTypedArray()
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_relationship_edit, null)

            // 편집 모드: 대상 캐릭터 읽기 전용
            val layoutCharSearch = dialogView.findViewById<TextInputLayout>(R.id.layoutCharacterSearch)
            val textTargetName = dialogView.findViewById<TextView>(R.id.textTargetCharacterName)
            val spinnerType = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerRelType)
            val editDesc = dialogView.findViewById<TextInputEditText>(R.id.editRelDescription)
            val sliderIntensity = dialogView.findViewById<Slider>(R.id.sliderIntensity)
            val textIntensityValue = dialogView.findViewById<TextView>(R.id.textIntensityValue)
            val checkBidirectional = dialogView.findViewById<MaterialCheckBox>(R.id.checkBidirectional)

            layoutCharSearch.visibility = View.GONE
            textTargetName.visibility = View.VISIBLE
            textTargetName.text = item.otherCharacterName

            spinnerType.adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item, typeNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            // 현재 값으로 초기화
            val currentTypeIndex = typeNames.indexOf(relationship.relationshipType)
            if (currentTypeIndex >= 0) spinnerType.setSelection(currentTypeIndex)
            editDesc.setText(relationship.description)
            sliderIntensity.value = relationship.intensity.toFloat().coerceIn(1f, 10f)
            textIntensityValue.text = relationship.intensity.toString()
            checkBidirectional.isChecked = relationship.isBidirectional

            sliderIntensity.addOnChangeListener { _, value, _ ->
                textIntensityValue.text = value.toInt().toString()
            }

            MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.edit_relationship))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    val selectedType = typeNames[spinnerType.selectedItemPosition]
                    val desc = editDesc.text.toString().trim()
                    val intensity = sliderIntensity.value.toInt()
                    val bidirectional = checkBidirectional.isChecked

                    viewModel.updateRelationship(
                        relationship.copy(
                            relationshipType = selectedType,
                            description = desc,
                            intensity = intensity,
                            isBidirectional = bidirectional
                        )
                    )
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun showRelationshipChangesDialog(item: RelationshipDisplayItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val changes = viewModel.getRelationshipChangesList(item.relationshipId)
            val context = try { contextGetter() } catch (_: Exception) { return@launch }

            if (changes.isEmpty()) {
                // 변화 없으면 바로 추가 다이얼로그
                MaterialAlertDialogBuilder(context)
                    .setTitle(getString(R.string.relationship_changes_title))
                    .setMessage(getString(R.string.no_relationship_changes))
                    .setPositiveButton(getString(R.string.add_relationship_change)) { _, _ ->
                        showAddRelationshipChangeDialog(item.relationshipId)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
                return@launch
            }

            // 변화 목록 표시
            val changeLabels = changes.sortedBy { it.year }.map { change ->
                val eventInfo = if (change.eventId != null) " [사건 연결됨]" else ""
                "${change.year}년: ${change.relationshipType} (강도 ${change.intensity})$eventInfo"
            }.toTypedArray()

            MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.relationship_changes_title))
                .setItems(changeLabels) { _, which ->
                    showEditRelationshipChangeDialog(changes.sortedBy { it.year }[which])
                }
                .setPositiveButton(getString(R.string.add_relationship_change)) { _, _ ->
                    showAddRelationshipChangeDialog(item.relationshipId)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun showAddRelationshipChangeDialog(relationshipId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = try { contextGetter() } catch (_: Exception) { return@launch }
            val typeNames = viewModel.getRelationshipTypesForCharacter(characterId).toTypedArray()

            // 연결 가능한 사건 목록 로드
            val character = viewModel.getCharacterByIdSuspend(characterId)
            val events = if (character?.novelId != null) {
                viewModel.getEventsForNovelList(character.novelId)
            } else emptyList()

            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_relationship_change_edit, null)
            val layoutYear = dialogView.findViewById<TextInputLayout>(R.id.layoutChangeYear)
            val editYear = dialogView.findViewById<TextInputEditText>(R.id.editYear)
            val spinnerType = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerChangeType)
            val editDesc = dialogView.findViewById<TextInputEditText>(R.id.editChangeDescription)
            val slider = dialogView.findViewById<Slider>(R.id.sliderChangeIntensity)
            val textValue = dialogView.findViewById<TextView>(R.id.textChangeIntensityValue)
            val checkBi = dialogView.findViewById<MaterialCheckBox>(R.id.checkChangeBidirectional)
            val spinnerEvent = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerLinkedEvent)

            spinnerType.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, typeNames)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            // 사건 스피너 (첫 항목: "없음")
            val eventLabels = mutableListOf(getString(R.string.rel_change_no_event))
            events.forEach { eventLabels.add("${it.description} (${it.year}년)") }
            spinnerEvent.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, eventLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            slider.addOnChangeListener { _, value, _ -> textValue.text = value.toInt().toString() }

            // S-12: 연도 검증 실패가 입력 전체를 무통보 폐기하지 않게 — 자동 닫힘을 막고
            // 인라인 오류로 교정 경로를 준다(검증→알림→교정). 성공 시에만 저장·닫힘.
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.add_relationship_change))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            dialog.setValidatedPositiveButton {
                val year = validateChangeYear(editYear, layoutYear) ?: return@setValidatedPositiveButton false
                val type = typeNames[spinnerType.selectedItemPosition]
                val desc = editDesc.text.toString().trim()
                val intensity = slider.value.toInt()
                val bidirectional = checkBi.isChecked
                val eventIdx = spinnerEvent.selectedItemPosition
                val eventId = if (eventIdx > 0 && eventIdx <= events.size) events[eventIdx - 1].id else null

                viewModel.insertRelationshipChange(
                    CharacterRelationshipChange(
                        relationshipId = relationshipId,
                        year = year,
                        relationshipType = type,
                        description = desc,
                        intensity = intensity,
                        isBidirectional = bidirectional,
                        eventId = eventId
                    )
                )
                true
            }
            dialog.show()
        }
    }

    /**
     * 연도 파싱 — 실패 시 인라인 오류를 걸고 null(다이얼로그 유지, 입력 보존 — S-12).
     * 오류 표시·해제는 `showInlineError`가 단일 소스다(B-28) — 여기서 TextWatcher를 다시 짜지 않는다.
     */
    private fun validateChangeYear(editYear: TextInputEditText, layoutYear: TextInputLayout): Int? {
        val yearText = editYear.text.toString().trim()
        val year = yearText.toIntOrNull()
        if (year == null) {
            layoutYear.showInlineError(
                getString(if (yearText.isEmpty()) R.string.year_required else R.string.enter_valid_year)
            )
        }
        return year
    }

    private fun showEditRelationshipChangeDialog(change: CharacterRelationshipChange) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = try { contextGetter() } catch (_: Exception) { return@launch }
            val typeNames = viewModel.getRelationshipTypesForCharacter(characterId).toTypedArray()

            val character = viewModel.getCharacterByIdSuspend(characterId)
            val events = if (character?.novelId != null) {
                viewModel.getEventsForNovelList(character.novelId)
            } else emptyList()

            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_relationship_change_edit, null)
            val layoutYear = dialogView.findViewById<TextInputLayout>(R.id.layoutChangeYear)
            val editYear = dialogView.findViewById<TextInputEditText>(R.id.editYear)
            val spinnerType = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerChangeType)
            val editDesc = dialogView.findViewById<TextInputEditText>(R.id.editChangeDescription)
            val slider = dialogView.findViewById<Slider>(R.id.sliderChangeIntensity)
            val textValue = dialogView.findViewById<TextView>(R.id.textChangeIntensityValue)
            val checkBi = dialogView.findViewById<MaterialCheckBox>(R.id.checkChangeBidirectional)
            val spinnerEvent = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerLinkedEvent)

            spinnerType.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, typeNames)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val eventLabels = mutableListOf(getString(R.string.rel_change_no_event))
            events.forEach { eventLabels.add("${it.description} (${it.year}년)") }
            spinnerEvent.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, eventLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            // 기존 값 세팅
            editYear.setText(change.year.toString())
            val typeIdx = typeNames.indexOf(change.relationshipType)
            if (typeIdx >= 0) spinnerType.setSelection(typeIdx)
            editDesc.setText(change.description)
            slider.value = change.intensity.toFloat().coerceIn(1f, 10f)
            textValue.text = change.intensity.toString()
            checkBi.isChecked = change.isBidirectional

            // 연결된 사건 선택
            if (change.eventId != null) {
                val eventIdx = events.indexOfFirst { it.id == change.eventId }
                if (eventIdx >= 0) spinnerEvent.setSelection(eventIdx + 1)
            }

            slider.addOnChangeListener { _, value, _ -> textValue.text = value.toInt().toString() }

            // S-12: 추가 다이얼로그와 같은 검증 구조 — 한쪽만 고치면 방편식 패치다.
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.edit_relationship_change))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save), null)
                .setNeutralButton(getString(R.string.delete)) { _, _ ->
                    viewModel.deleteRelationshipChange(change)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            dialog.setValidatedPositiveButton {
                val year = validateChangeYear(editYear, layoutYear) ?: return@setValidatedPositiveButton false
                val type = typeNames[spinnerType.selectedItemPosition]
                val desc = editDesc.text.toString().trim()
                val intensity = slider.value.toInt()
                val bidirectional = checkBi.isChecked
                val eventIdx = spinnerEvent.selectedItemPosition
                val eventId = if (eventIdx > 0 && eventIdx <= events.size) events[eventIdx - 1].id else null

                viewModel.updateRelationshipChange(
                    change.copy(
                        year = year,
                        relationshipType = type,
                        description = desc,
                        intensity = intensity,
                        isBidirectional = bidirectional,
                        eventId = eventId
                    )
                )
                true
            }
            dialog.show()
        }
    }

    private fun confirmDeleteRelationship(item: RelationshipDisplayItem) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getFormattedString(R.string.relationship_title_format, arrayOf(item.otherCharacterName, item.relationshipType)))
            .setMessage(getString(R.string.confirm_delete_relationship))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteRelationshipById(item.relationshipId)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
