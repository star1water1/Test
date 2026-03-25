package com.novelcharacter.app.util

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.databinding.DialogTimelineEditBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 재사용 가능한 사건(TimelineEvent) 편집 다이얼로그 헬퍼.
 * TimelineFragment, CharacterDetailFragment, CharacterEditFragment에서 공통 사용.
 */
class EventEditDialogHelper(
    private val context: Context,
    private val lifecycleScope: CoroutineScope,
    private val layoutInflater: LayoutInflater
) {

    enum class ShiftDirection { BEFORE, AFTER }

    interface DataProvider {
        suspend fun getAllNovelsList(): List<Novel>
        suspend fun getAllCharactersList(): List<Character>
        suspend fun getCharacterIdsForEvent(eventId: Long): List<Long>
        suspend fun getNovelIdsForEvent(eventId: Long): List<Long>
        fun insertEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>)
        fun updateEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>)
        suspend fun getEventsInScope(novelIds: List<Long>, universeId: Long?): List<TimelineEvent>
        fun updateEventAndShiftOthers(
            event: TimelineEvent,
            characterIds: List<Long>,
            novelIds: List<Long>,
            shiftDirection: ShiftDirection,
            delta: Int,
            originalNovelIds: List<Long>,
            originalUniverseId: Long?
        )
    }

    fun showEventDialog(
        dataProvider: DataProvider,
        event: TimelineEvent? = null,
        preSelectedCharacterIds: Set<Long> = emptySet(),
        preSelectedNovelIds: List<Long> = emptyList(),
        onSaved: (() -> Unit)? = null
    ) {
        lifecycleScope.launch {
            val novels = dataProvider.getAllNovelsList()
            val characters = dataProvider.getAllCharactersList()
            val selectedCharIds = if (event != null) {
                dataProvider.getCharacterIdsForEvent(event.id).toMutableSet()
            } else {
                preSelectedCharacterIds.toMutableSet()
            }
            val selectedNovelIds = if (event != null) {
                dataProvider.getNovelIdsForEvent(event.id).toMutableSet()
            } else {
                preSelectedNovelIds.toMutableSet()
            }

            val dialogBinding = DialogTimelineEditBinding.inflate(layoutInflater)

            // 사건 유형 스피너
            val eventTypes = listOf(
                TimelineEvent.TYPE_NONE to context.getString(R.string.event_type_none),
                TimelineEvent.TYPE_BIRTH to context.getString(R.string.event_type_birth),
                TimelineEvent.TYPE_DEATH to context.getString(R.string.event_type_death)
            )
            val typeAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, eventTypes.map { it.second })
            typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            dialogBinding.spinnerEventType.adapter = typeAdapter

            // Fill existing data
            event?.let {
                dialogBinding.editYear.setText(it.year.toString())
                dialogBinding.editMonth.setText(it.month?.toString() ?: "")
                dialogBinding.editDay.setText(it.day?.toString() ?: "")
                dialogBinding.editCalendarType.setText(it.calendarType)
                dialogBinding.editDescription.setText(it.description)
                val typeIndex = eventTypes.indexOfFirst { (key, _) -> key == it.eventType }
                if (typeIndex >= 0) dialogBinding.spinnerEventType.setSelection(typeIndex)
            }

            // Character checkboxes — 선택된 작품 기준 자동 정렬
            fun filteredChars(): List<Character> {
                if (selectedNovelIds.isEmpty()) return characters
                return characters.sortedWith(compareBy<Character> {
                    if (it.novelId in selectedNovelIds) 0 else 1
                }.thenBy { it.name })
            }

            // Novel checkboxes (다대다 — 복수 작품 선택)
            // 작품 선택 변경 시 캐릭터 목록 자동 갱신
            dialogBinding.spinnerNovel.visibility = View.GONE
            setupNovelCheckboxes(dialogBinding, novels, selectedNovelIds) {
                setupCharacterCheckboxes(dialogBinding, filteredChars(), selectedCharIds)
            }
            setupCharacterCheckboxes(dialogBinding, filteredChars(), selectedCharIds)

            val alertDialog = AlertDialog.Builder(context)
                .setTitle(if (event == null) R.string.add_event else R.string.edit_event)
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            alertDialog.setOnShowListener {
                alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val yearStr = dialogBinding.editYear.text.toString().trim()
                    val description = dialogBinding.editDescription.text.toString().trim()

                    if (yearStr.isEmpty() || description.isEmpty()) {
                        Toast.makeText(context, R.string.enter_year_and_desc, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val year = yearStr.toIntOrNull()
                    if (year == null) {
                        Toast.makeText(context, R.string.enter_valid_year, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val monthStr = dialogBinding.editMonth.text.toString().trim()
                    val dayStr = dialogBinding.editDay.text.toString().trim()
                    val month = if (monthStr.isNotEmpty()) monthStr.toIntOrNull() else null
                    val day = if (dayStr.isNotEmpty()) dayStr.toIntOrNull() else null

                    if (month != null && (month < 1 || month > 12)) {
                        Toast.makeText(context, R.string.month_valid_range, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (day != null && !isValidDay(month, day)) {
                        Toast.makeText(context, R.string.day_valid_range, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val calendarType = dialogBinding.editCalendarType.text.toString().trim()
                    val selectedTypeIndex = dialogBinding.spinnerEventType.selectedItemPosition
                    val selectedEventType = eventTypes.getOrNull(selectedTypeIndex)?.first ?: TimelineEvent.TYPE_NONE

                    // 선택된 작품들에서 세계관 ID 결정 (첫 번째 작품 기준)
                    val selectedNovels = novels.filter { it.id in selectedNovelIds }
                    val universeId = selectedNovels.firstOrNull()?.universeId

                    val newEvent = TimelineEvent(
                        id = event?.id ?: 0,
                        year = year,
                        month = month,
                        day = day,
                        calendarType = calendarType,
                        description = description,
                        eventType = selectedEventType,
                        universeId = universeId,
                        isTemporary = false,
                        createdAt = event?.createdAt ?: System.currentTimeMillis()
                    )
                    val novelIdsList = selectedNovelIds.toList()

                    if (event == null) {
                        dataProvider.insertEvent(newEvent, selectedCharIds.toList(), novelIdsList)
                        onSaved?.invoke()
                        alertDialog.dismiss()
                    } else {
                        val oldYear = event.year
                        val delta = newEvent.year - oldYear
                        // 기존 연결된 작품 IDs를 원본 scope로 사용
                        val originalNovelIds = dataProvider.getNovelIdsForEvent(event.id)
                        val hasScope = originalNovelIds.isNotEmpty() || event.universeId != null
                        if (delta != 0 && hasScope) {
                            lifecycleScope.launch {
                                showYearShiftDialog(
                                    dataProvider, newEvent, selectedCharIds.toList(),
                                    novelIdsList,
                                    delta, oldYear,
                                    originalNovelIds = originalNovelIds,
                                    originalUniverseId = event.universeId,
                                    onSaved, alertDialog
                                )
                            }
                        } else {
                            dataProvider.updateEvent(newEvent, selectedCharIds.toList(), novelIdsList)
                            onSaved?.invoke()
                            alertDialog.dismiss()
                        }
                    }
                }
            }
            alertDialog.show()
        }
    }

    private suspend fun showYearShiftDialog(
        dataProvider: DataProvider,
        newEvent: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long>,
        delta: Int,
        oldYear: Int,
        originalNovelIds: List<Long>,
        originalUniverseId: Long?,
        onSaved: (() -> Unit)?,
        parentDialog: AlertDialog
    ) {
        val scopeEvents = dataProvider.getEventsInScope(originalNovelIds, originalUniverseId)
            .filter { it.id != newEvent.id }
        val afterCount = scopeEvents.count { it.year >= oldYear }
        val beforeCount = scopeEvents.count { it.year <= oldYear }

        val direction = if (delta > 0) "${delta}년 뒤로" else "${-delta}년 앞으로"
        val items = mutableListOf(context.getString(R.string.shift_this_only))
        val actions = mutableListOf<() -> Unit>()
        actions.add {
            dataProvider.updateEvent(newEvent, characterIds, novelIds)
            onSaved?.invoke()
            if (parentDialog.isShowing) parentDialog.dismiss()
        }
        if (afterCount > 0) {
            items.add(context.getString(R.string.shift_after_events, afterCount, direction))
            actions.add {
                dataProvider.updateEventAndShiftOthers(
                    newEvent, characterIds, novelIds, ShiftDirection.AFTER, delta,
                    originalNovelIds, originalUniverseId
                )
                onSaved?.invoke()
                if (parentDialog.isShowing) parentDialog.dismiss()
            }
        }
        if (beforeCount > 0) {
            items.add(context.getString(R.string.shift_before_events, beforeCount, direction))
            actions.add {
                dataProvider.updateEventAndShiftOthers(
                    newEvent, characterIds, novelIds, ShiftDirection.BEFORE, delta,
                    originalNovelIds, originalUniverseId
                )
                onSaved?.invoke()
                if (parentDialog.isShowing) parentDialog.dismiss()
            }
        }

        withContext(Dispatchers.Main) {
            AlertDialog.Builder(context)
                .setTitle(R.string.shift_events_title)
                .setItems(items.toTypedArray()) { _, which ->
                    actions.getOrNull(which)?.invoke()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupNovelCheckboxes(
        dialogBinding: DialogTimelineEditBinding,
        novels: List<Novel>,
        selectedIds: MutableSet<Long>,
        onChanged: (() -> Unit)? = null
    ) {
        val recyclerView = dialogBinding.novelSelectRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val checkBox = CheckBox(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                    textSize = 14f
                }
                return object : RecyclerView.ViewHolder(checkBox) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val novel = novels[position]
                val checkBox = holder.itemView as CheckBox
                checkBox.text = novel.title
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = selectedIds.contains(novel.id)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIds.add(novel.id)
                    else selectedIds.remove(novel.id)
                    onChanged?.invoke()
                }
            }

            override fun getItemCount() = novels.size
        }
    }

    private fun setupCharacterCheckboxes(
        dialogBinding: DialogTimelineEditBinding,
        characters: List<Character>,
        selectedIds: MutableSet<Long>
    ) {
        val recyclerView = dialogBinding.characterSelectRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val checkBox = CheckBox(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                    textSize = 14f
                }
                return object : RecyclerView.ViewHolder(checkBox) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val character = characters[position]
                val checkBox = holder.itemView as CheckBox
                checkBox.text = character.name
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = selectedIds.contains(character.id)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIds.add(character.id)
                    else selectedIds.remove(character.id)
                }
            }

            override fun getItemCount() = characters.size
        }
    }
}

/** 월별 최대 일수 반환 (윤년 무시 — 판타지 세계관에서는 2월 29일 항상 허용) */
fun maxDayOfMonth(month: Int?): Int = when (month) {
    2 -> 29
    4, 6, 9, 11 -> 30
    else -> 31
}

/** 일(day) 값이 해당 월에 유효한지 검사 */
fun isValidDay(month: Int?, day: Int?): Boolean {
    if (day == null) return true
    if (day < 1 || day > 31) return false
    return day <= maxDayOfMonth(month)
}
