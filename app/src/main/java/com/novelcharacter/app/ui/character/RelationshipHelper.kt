package com.novelcharacter.app.ui.character

import android.content.Context
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
import com.novelcharacter.app.util.navigateSafe
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
    private val navController: () -> NavController
) {
    private lateinit var relationshipAdapter: RelationshipAdapter
    private var relationshipJob: Job? = null
    private var currentRelationships: List<CharacterRelationship> = emptyList()
    private var currentDisplayItems: MutableList<RelationshipDisplayItem> = mutableListOf()

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
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from < 0 || to < 0 || from >= currentDisplayItems.size || to >= currentDisplayItems.size) return false
                Collections.swap(currentDisplayItems, from, to)
                relationshipAdapter.notifyItemMoved(from, to)
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
    }

    private fun saveDisplayOrder() {
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
                currentDisplayItems = displayItems.toMutableList()
                relationshipAdapter.submitList(displayItems)
                try {
                    binding.textNoRelationships.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
                    binding.relationshipsRecyclerView.visibility = if (displayItems.isEmpty()) View.GONE else View.VISIBLE
                } catch (_: Exception) {
                    // Fragment view may have been destroyed during suspend
                }
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

            AlertDialog.Builder(context)
                .setTitle(getString(R.string.add_relationship))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    // AutoComplete에서 텍스트로 직접 입력한 경우 매칭 시도
                    if (selectedCharIndex < 0) {
                        val typed = autoComplete.text.toString().trim()
                        selectedCharIndex = charDisplayNames.indexOfFirst {
                            it.equals(typed, ignoreCase = true)
                        }
                    }
                    if (selectedCharIndex < 0 || selectedCharIndex >= sorted.size) {
                        Toast.makeText(context, getString(R.string.no_other_characters), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val selectedType = typeNames[spinnerType.selectedItemPosition]
                    val desc = editDesc.text.toString().trim()
                    val intensity = sliderIntensity.value.toInt()
                    val bidirectional = checkBidirectional.isChecked
                    val otherChar = sorted[selectedCharIndex]

                    viewLifecycleOwner.lifecycleScope.launch {
                        val existingRels = viewModel.getRelationshipsForCharacterList(characterId)
                        val alreadyExists = existingRels.any {
                            ((it.characterId1 == characterId && it.characterId2 == otherChar.id) ||
                            (it.characterId1 == otherChar.id && it.characterId2 == characterId)) &&
                            it.relationshipType == selectedType
                        }
                        if (alreadyExists) {
                            val ctx = try { contextGetter() } catch (_: Exception) { return@launch }
                            Toast.makeText(ctx, getString(R.string.relationship_already_exists), Toast.LENGTH_SHORT).show()
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
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun showRelationshipOptionsDialog(item: RelationshipDisplayItem) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        val options = arrayOf(
            getString(R.string.relationship_options_edit),
            getString(R.string.relationship_changes_title),
            getString(R.string.relationship_options_delete)
        )
        AlertDialog.Builder(context)
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

            AlertDialog.Builder(context)
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
                AlertDialog.Builder(context)
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

            AlertDialog.Builder(context)
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

            AlertDialog.Builder(context)
                .setTitle(getString(R.string.add_relationship_change))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    val yearText = editYear.text.toString().trim()
                    val year = yearText.toIntOrNull() ?: return@setPositiveButton
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
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
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

            AlertDialog.Builder(context)
                .setTitle(getString(R.string.edit_relationship_change))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    val yearText = editYear.text.toString().trim()
                    val year = yearText.toIntOrNull() ?: return@setPositiveButton
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
                }
                .setNeutralButton(getString(R.string.delete)) { _, _ ->
                    viewModel.deleteRelationshipChange(change)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun confirmDeleteRelationship(item: RelationshipDisplayItem) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        AlertDialog.Builder(context)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(getFormattedString(R.string.relationship_title_format, arrayOf(item.otherCharacterName, item.relationshipType)))
            .setMessage(getString(R.string.confirm_delete_relationship))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteRelationshipById(item.relationshipId)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
