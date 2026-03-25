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
        fun insertEvent(event: TimelineEvent, characterIds: List<Long>)
        fun updateEvent(event: TimelineEvent, characterIds: List<Long>)
        suspend fun getEventsInScope(novelId: Long?, universeId: Long?): List<TimelineEvent>
        fun updateEventAndShiftOthers(
            event: TimelineEvent,
            characterIds: List<Long>,
            shiftDirection: ShiftDirection,
            delta: Int,
            originalNovelId: Long?,
            originalUniverseId: Long?
        )
    }

    fun showEventDialog(
        dataProvider: DataProvider,
        event: TimelineEvent? = null,
        preSelectedCharacterIds: Set<Long> = emptySet(),
        preSelectedNovelId: Long? = null,
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

            // Novel spinner
            val novelNames = mutableListOf(context.getString(R.string.all_novels_event))
            novelNames.addAll(novels.map { it.title })
            val novelAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, novelNames)
            novelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            dialogBinding.spinnerNovel.adapter = novelAdapter

            // 사전 선택된 작품 또는 기존 이벤트의 작품
            val preNovelId = event?.novelId ?: preSelectedNovelId
            preNovelId?.let { nid ->
                val index = novels.indexOfFirst { it.id == nid }
                if (index >= 0) dialogBinding.spinnerNovel.setSelection(index + 1)
            }

            // Character checkboxes — 작품 선택 시 자동 필터링
            fun filteredChars(novelPos: Int): List<Character> {
                if (novelPos <= 0) return characters
                val novelId = novels.getOrNull(novelPos - 1)?.id ?: return characters
                return characters.sortedWith(compareBy<Character> {
                    if (it.novelId == novelId) 0 else 1
                }.thenBy { it.name })
            }
            setupCharacterCheckboxes(dialogBinding, filteredChars(dialogBinding.spinnerNovel.selectedItemPosition), selectedCharIds)

            dialogBinding.spinnerNovel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    setupCharacterCheckboxes(dialogBinding, filteredChars(pos), selectedCharIds)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

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
                    val novelPosition = dialogBinding.spinnerNovel.selectedItemPosition
                    val selectedNovel = if (novelPosition > 0) novels.getOrNull(novelPosition - 1) else null
                    val novelId = selectedNovel?.id
                    val universeId = selectedNovel?.universeId

                    val newEvent = TimelineEvent(
                        id = event?.id ?: 0,
                        year = year,
                        month = month,
                        day = day,
                        calendarType = calendarType,
                        description = description,
                        eventType = selectedEventType,
                        universeId = universeId,
                        novelId = novelId,
                        isTemporary = false,
                        createdAt = event?.createdAt ?: System.currentTimeMillis()
                    )

                    if (event == null) {
                        dataProvider.insertEvent(newEvent, selectedCharIds.toList())
                        onSaved?.invoke()
                        alertDialog.dismiss()
                    } else {
                        val oldYear = event.year
                        val delta = newEvent.year - oldYear
                        val hasScope = event.novelId != null || event.universeId != null
                        if (delta != 0 && hasScope) {
                            lifecycleScope.launch {
                                showYearShiftDialog(
                                    dataProvider, newEvent, selectedCharIds.toList(),
                                    delta, oldYear,
                                    originalNovelId = event.novelId,
                                    originalUniverseId = event.universeId,
                                    onSaved, alertDialog
                                )
                            }
                        } else {
                            dataProvider.updateEvent(newEvent, selectedCharIds.toList())
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
        delta: Int,
        oldYear: Int,
        originalNovelId: Long?,
        originalUniverseId: Long?,
        onSaved: (() -> Unit)?,
        parentDialog: AlertDialog
    ) {
        val scopeEvents = dataProvider.getEventsInScope(originalNovelId, originalUniverseId)
            .filter { it.id != newEvent.id }
        val afterCount = scopeEvents.count { it.year >= oldYear }
        val beforeCount = scopeEvents.count { it.year <= oldYear }

        val direction = if (delta > 0) "${delta}년 뒤로" else "${-delta}년 앞으로"
        val items = mutableListOf(context.getString(R.string.shift_this_only))
        val actions = mutableListOf<() -> Unit>()
        actions.add {
            dataProvider.updateEvent(newEvent, characterIds)
            onSaved?.invoke()
            if (parentDialog.isShowing) parentDialog.dismiss()
        }
        if (afterCount > 0) {
            items.add(context.getString(R.string.shift_after_events, afterCount, direction))
            actions.add {
                dataProvider.updateEventAndShiftOthers(
                    newEvent, characterIds, ShiftDirection.AFTER, delta,
                    originalNovelId, originalUniverseId
                )
                onSaved?.invoke()
                if (parentDialog.isShowing) parentDialog.dismiss()
            }
        }
        if (beforeCount > 0) {
            items.add(context.getString(R.string.shift_before_events, beforeCount, direction))
            actions.add {
                dataProvider.updateEventAndShiftOthers(
                    newEvent, characterIds, ShiftDirection.BEFORE, delta,
                    originalNovelId, originalUniverseId
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
