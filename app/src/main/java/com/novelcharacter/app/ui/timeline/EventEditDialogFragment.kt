package com.novelcharacter.app.ui.timeline

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.databinding.DialogTimelineEditBinding
import com.novelcharacter.app.util.FieldOptionParser
import com.novelcharacter.app.util.isValidDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 사건(TimelineEvent) 편집 다이얼로그.
 *
 * AlertDialog 헬퍼(구 EventEditDialogHelper)와 달리 DialogFragment이므로
 * 회전/프로세스 킬 시에도 시스템이 다이얼로그를 재생성하고,
 * ID 있는 입력 뷰(연/월/일/역법/설명/유형)는 뷰 상태 자동 복원,
 * ID 없는 동적 체크박스 선택(캐릭터/작품)은 onSaveInstanceState로 복원한다.
 *
 * 데이터 접근은 호스트 프래그먼트가 [Host]로 제공한다 — 재생성 후에도
 * parentFragment를 통해 새 provider를 얻으므로 콜백 유실이 없다.
 */
class EventEditDialogFragment : DialogFragment() {

    enum class ShiftDirection { BEFORE, AFTER }

    interface DataProvider {
        suspend fun getAllNovelsList(): List<Novel>
        suspend fun getAllCharactersList(): List<Character>
        suspend fun getCharacterIdsForEvent(eventId: Long): List<Long>
        suspend fun getNovelIdsForEvent(eventId: Long): List<Long>
        suspend fun getEventFieldsForUniverse(universeId: Long): List<FieldDefinition>
        suspend fun getEventFieldValuesForEvent(eventId: Long): List<EventFieldValue>
        fun insertEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, eventFieldValues: List<EventFieldValue>)
        fun updateEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, eventFieldValues: List<EventFieldValue>)
        suspend fun getEventsInScope(novelIds: List<Long>, universeId: Long?): List<TimelineEvent>
        fun updateEventAndShiftOthers(
            event: TimelineEvent,
            characterIds: List<Long>,
            novelIds: List<Long>,
            shiftDirection: ShiftDirection,
            delta: Int,
            originalNovelIds: List<Long>,
            originalUniverseId: Long?,
            eventFieldValues: List<EventFieldValue>
        )
    }

    /** 호스트 프래그먼트가 구현. 재생성 후 provider 재획득 경로. */
    interface Host {
        fun eventDialogDataProvider(): DataProvider
    }

    private var _binding: DialogTimelineEditBinding? = null
    private val binding get() = _binding!!

    private val gson = Gson()
    private var editingEvent: TimelineEvent? = null
    private val selectedCharIds = mutableSetOf<Long>()
    private val selectedNovelIds = mutableSetOf<Long>()
    private var selectionsInitialized = false
    private var novels: List<Novel> = emptyList()
    private var characters: List<Character> = emptyList()
    private var eventTypes: List<Pair<String, String>> = emptyList()

    // 사건 커스텀 필드 (B-10)
    private var eventFields: List<FieldDefinition> = emptyList()
    private val eventFieldInputMap = mutableMapOf<Long, Any>()  // fieldId -> EditText/Spinner
    private var pendingEventFieldValues: MutableMap<String, String>? = null

    private fun requireProvider(): DataProvider =
        (parentFragment as? Host ?: activity as? Host)?.eventDialogDataProvider()
            ?: throw IllegalStateException(
                "EventEditDialogFragment host must implement EventEditDialogFragment.Host"
            )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogTimelineEditBinding.inflate(layoutInflater)
        editingEvent = arguments?.getString(ARG_EVENT_JSON)?.let {
            gson.fromJson(it, TimelineEvent::class.java)
        }

        eventTypes = listOf(
            TimelineEvent.TYPE_NONE to getString(R.string.event_type_none),
            TimelineEvent.TYPE_BIRTH to getString(R.string.event_type_birth),
            TimelineEvent.TYPE_DEATH to getString(R.string.event_type_death)
        )
        val typeAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, eventTypes.map { it.second }
        )
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerEventType.adapter = typeAdapter

        // 다대다 작품 선택 — 구 단일 스피너는 사용 안 함
        binding.spinnerNovel.visibility = View.GONE

        // 체크 선택 복원 (뷰 ID가 없는 동적 체크박스는 자동 복원 대상이 아님)
        savedInstanceState?.getLongArray(STATE_CHAR_IDS)?.let {
            selectedCharIds.addAll(it.toList())
            selectedNovelIds.addAll(savedInstanceState.getLongArray(STATE_NOVEL_IDS)?.toList() ?: emptyList())
            selectionsInitialized = true
        }
        savedInstanceState?.getBundle(STATE_EVENT_FIELD_VALUES)?.let { bundle ->
            val restored = mutableMapOf<String, String>()
            for (key in bundle.keySet()) {
                bundle.getString(key)?.let { restored[key] = it }
            }
            pendingEventFieldValues = restored
        }
        val isRecreated = savedInstanceState != null

        val alertDialog = AlertDialog.Builder(requireContext())
            .setTitle(if (editingEvent == null) R.string.add_event else R.string.edit_event)
            .setView(binding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        alertDialog.setOnShowListener {
            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onSaveClicked()
            }
        }

        // 목록/선택 상태 비동기 로드. 정적 입력값은 재생성 시 뷰 상태로 자동 복원되므로
        // 초기값 채우기는 최초 생성(savedInstanceState == null)에만 수행한다.
        lifecycleScope.launch {
            val provider = requireProvider()
            novels = provider.getAllNovelsList()
            characters = provider.getAllCharactersList()
            if (!selectionsInitialized) {
                val event = editingEvent
                if (event != null) {
                    selectedCharIds.addAll(provider.getCharacterIdsForEvent(event.id))
                    selectedNovelIds.addAll(provider.getNovelIdsForEvent(event.id))
                } else {
                    selectedCharIds.addAll(arguments?.getLongArray(ARG_PRE_CHAR_IDS)?.toList() ?: emptyList())
                    selectedNovelIds.addAll(arguments?.getLongArray(ARG_PRE_NOVEL_IDS)?.toList() ?: emptyList())
                }
                selectionsInitialized = true
            }
            if (_binding == null) return@launch
            if (!isRecreated) {
                editingEvent?.let { event ->
                    fillInitialValues(event)
                    // 기존 사건 필드값 → 폼 빌드 후 지연 적용
                    val values = provider.getEventFieldValuesForEvent(event.id)
                    if (values.isNotEmpty() && pendingEventFieldValues == null) {
                        pendingEventFieldValues = values
                            .associate { it.fieldDefinitionId.toString() to it.value }
                            .toMutableMap()
                    }
                }
            }
            if (_binding == null) return@launch
            setupNovelCheckboxes()
            setupCharacterCheckboxes()
            rebuildEventFieldSection()
        }

        return alertDialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)  // 다이얼로그 뷰 계층 상태(정적 입력) 저장
        if (selectionsInitialized) {
            outState.putLongArray(STATE_CHAR_IDS, selectedCharIds.toLongArray())
            outState.putLongArray(STATE_NOVEL_IDS, selectedNovelIds.toLongArray())
        }
        // 사건 필드 입력 (동적 위젯은 ID가 없어 자동 복원 대상이 아님)
        if (eventFieldInputMap.isNotEmpty() || pendingEventFieldValues != null) {
            val bundle = Bundle()
            pendingEventFieldValues?.forEach { (k, v) -> bundle.putString(k, v) }
            for ((fieldId, widget) in eventFieldInputMap) {
                bundle.putString(fieldId.toString(), eventFieldWidgetValue(widget))
            }
            outState.putBundle(STATE_EVENT_FIELD_VALUES, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun fillInitialValues(event: TimelineEvent) {
        binding.editYear.setText(event.year.toString())
        binding.editMonth.setText(event.month?.toString() ?: "")
        binding.editDay.setText(event.day?.toString() ?: "")
        binding.editCalendarType.setText(event.calendarType)
        binding.editDescription.setText(event.description)
        val typeIndex = eventTypes.indexOfFirst { (key, _) -> key == event.eventType }
        if (typeIndex >= 0) binding.spinnerEventType.setSelection(typeIndex)
    }

    /** 선택된 작품 소속 캐릭터가 위로 오도록 정렬 */
    private fun filteredChars(): List<Character> {
        if (selectedNovelIds.isEmpty()) return characters
        return characters.sortedWith(compareBy<Character> {
            if (it.novelId in selectedNovelIds) 0 else 1
        }.thenBy { it.name })
    }

    private fun onSaveClicked() {
        if (!selectionsInitialized) return  // 목록 로딩 중
        val context = context ?: return

        val yearStr = binding.editYear.text.toString().trim()
        val description = binding.editDescription.text.toString().trim()

        if (yearStr.isEmpty() || description.isEmpty()) {
            Toast.makeText(context, R.string.enter_year_and_desc, Toast.LENGTH_SHORT).show()
            return
        }

        val year = yearStr.toIntOrNull()
        if (year == null) {
            Toast.makeText(context, R.string.enter_valid_year, Toast.LENGTH_SHORT).show()
            return
        }

        val monthStr = binding.editMonth.text.toString().trim()
        val dayStr = binding.editDay.text.toString().trim()
        val month = if (monthStr.isNotEmpty()) monthStr.toIntOrNull() else null
        val day = if (dayStr.isNotEmpty()) dayStr.toIntOrNull() else null

        if (month != null && (month < 1 || month > 12)) {
            Toast.makeText(context, R.string.month_valid_range, Toast.LENGTH_SHORT).show()
            return
        }
        if (day != null && !isValidDay(month, day)) {
            Toast.makeText(context, R.string.day_valid_range, Toast.LENGTH_SHORT).show()
            return
        }

        val calendarType = binding.editCalendarType.text.toString().trim()
        val selectedTypeIndex = binding.spinnerEventType.selectedItemPosition
        val selectedEventType = eventTypes.getOrNull(selectedTypeIndex)?.first ?: TimelineEvent.TYPE_NONE

        // 선택된 작품들에서 세계관 ID 결정 (첫 번째 작품 기준)
        val selectedNovels = novels.filter { it.id in selectedNovelIds }
        val universeId = selectedNovels.firstOrNull()?.universeId

        val event = editingEvent
        val newEvent = TimelineEvent(
            id = event?.id ?: 0,
            year = year,
            month = month,
            day = day,
            calendarType = calendarType,
            description = description,
            eventType = selectedEventType,
            universeId = universeId,
            displayOrder = event?.displayOrder ?: 0,  // 편집 시 기존 표시 순서 보존
            isTemporary = false,
            createdAt = event?.createdAt ?: System.currentTimeMillis()
        )
        val novelIdsList = selectedNovelIds.toList()
        val provider = requireProvider()

        val eventFieldValues = collectEventFieldValues()
        if (event == null) {
            provider.insertEvent(newEvent, selectedCharIds.toList(), novelIdsList, eventFieldValues)
            dismiss()
        } else {
            val delta = newEvent.year - event.year
            lifecycleScope.launch {
                // 기존 연결된 작품 IDs를 원본 scope로 사용
                val originalNovelIds = provider.getNovelIdsForEvent(event.id)
                val hasScope = originalNovelIds.isNotEmpty() || event.universeId != null
                if (delta != 0 && hasScope) {
                    showYearShiftDialog(
                        provider, newEvent, selectedCharIds.toList(), novelIdsList,
                        delta, event.year,
                        originalNovelIds = originalNovelIds,
                        originalUniverseId = event.universeId,
                        eventFieldValues = eventFieldValues
                    )
                } else {
                    provider.updateEvent(newEvent, selectedCharIds.toList(), novelIdsList, eventFieldValues)
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    /**
     * 연도 변경 시 같은 scope의 다른 사건들을 함께 이동할지 선택.
     * 순간적 결정 다이얼로그이므로 재생성 대상이 아니다 — 회전 시 사라져도
     * 본 다이얼로그의 입력은 보존되며 저장을 다시 누르면 재표시된다.
     */
    private suspend fun showYearShiftDialog(
        provider: DataProvider,
        newEvent: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long>,
        delta: Int,
        oldYear: Int,
        originalNovelIds: List<Long>,
        originalUniverseId: Long?,
        eventFieldValues: List<EventFieldValue>
    ) {
        val scopeEvents = provider.getEventsInScope(originalNovelIds, originalUniverseId)
            .filter { it.id != newEvent.id }
        val afterCount = scopeEvents.count { it.year >= oldYear }
        val beforeCount = scopeEvents.count { it.year <= oldYear }

        val direction = if (delta > 0) "${delta}년 뒤로" else "${-delta}년 앞으로"
        val items = mutableListOf(getString(R.string.shift_this_only))
        val actions = mutableListOf<() -> Unit>()
        actions.add {
            provider.updateEvent(newEvent, characterIds, novelIds, eventFieldValues)
            dismissAllowingStateLoss()
        }
        if (afterCount > 0) {
            items.add(getString(R.string.shift_after_events, afterCount, direction))
            actions.add {
                provider.updateEventAndShiftOthers(
                    newEvent, characterIds, novelIds, ShiftDirection.AFTER, delta,
                    originalNovelIds, originalUniverseId, eventFieldValues
                )
                dismissAllowingStateLoss()
            }
        }
        if (beforeCount > 0) {
            items.add(getString(R.string.shift_before_events, beforeCount, direction))
            actions.add {
                provider.updateEventAndShiftOthers(
                    newEvent, characterIds, novelIds, ShiftDirection.BEFORE, delta,
                    originalNovelIds, originalUniverseId, eventFieldValues
                )
                dismissAllowingStateLoss()
            }
        }

        withContext(Dispatchers.Main) {
            val ctx = context ?: return@withContext
            AlertDialog.Builder(ctx)
                .setTitle(R.string.shift_events_title)
                .setItems(items.toTypedArray()) { _, which ->
                    actions.getOrNull(which)?.invoke()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ── 사건 커스텀 필드 (B-10) ──

    /** 선택된 작품의 세계관 기준으로 사건 필드 입력 섹션 재구성. 입력 중이던 값은 보존. */
    private fun rebuildEventFieldSection() {
        if (_binding == null) return
        // 현재 입력값 보존
        if (eventFieldInputMap.isNotEmpty()) {
            val preserved = pendingEventFieldValues ?: mutableMapOf()
            for ((fieldId, widget) in eventFieldInputMap) {
                preserved[fieldId.toString()] = eventFieldWidgetValue(widget)
            }
            pendingEventFieldValues = preserved
        }

        val universeId = novels.firstOrNull { it.id in selectedNovelIds }?.universeId
        if (universeId == null) {
            eventFields = emptyList()
            eventFieldInputMap.clear()
            binding.eventFieldContainer.removeAllViews()
            binding.eventFieldContainer.visibility = View.GONE
            binding.eventFieldSectionLabel.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            val fields = requireProvider().getEventFieldsForUniverse(universeId)
            if (_binding == null) return@launch
            eventFields = fields
                .filter { FieldType.fromName(it.type) != FieldType.CALCULATED }
                .sortedBy { it.displayOrder }
            buildEventFieldInputs()
        }
    }

    private fun buildEventFieldInputs() {
        val ctx = context ?: return
        eventFieldInputMap.clear()
        binding.eventFieldContainer.removeAllViews()

        if (eventFields.isEmpty()) {
            binding.eventFieldContainer.visibility = View.GONE
            binding.eventFieldSectionLabel.visibility = View.GONE
            return
        }
        binding.eventFieldContainer.visibility = View.VISIBLE
        binding.eventFieldSectionLabel.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        for (field in eventFields) {
            val saved = pendingEventFieldValues?.get(field.id.toString()) ?: ""
            when (FieldType.fromName(field.type)) {
                FieldType.SELECT, FieldType.GRADE -> {
                    val label = android.widget.TextView(ctx).apply {
                        text = field.name
                        textSize = 13f
                    }
                    binding.eventFieldContainer.addView(label)

                    val options = mutableListOf(getString(R.string.no_selection))
                    options.addAll(
                        if (FieldType.fromName(field.type) == FieldType.SELECT) {
                            FieldOptionParser.parseSelectOptions(field.config)
                        } else {
                            FieldOptionParser.parseGradeOptions(field.config)
                        }
                    )
                    // 고아 값 보존: 저장된 값이 현재 옵션에 없어도 유실하지 않는다
                    if (saved.isNotBlank() && saved !in options) options.add(saved)

                    val spinner = android.widget.Spinner(ctx).apply {
                        val spinnerAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, options)
                        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        adapter = spinnerAdapter
                        val idx = options.indexOf(saved)
                        if (idx > 0) setSelection(idx)
                    }
                    binding.eventFieldContainer.addView(spinner)
                    eventFieldInputMap[field.id] = spinner
                }
                else -> {
                    val editText = android.widget.EditText(ctx).apply {
                        hint = field.name
                        setText(saved)
                        if (FieldType.fromName(field.type) == FieldType.NUMBER) {
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (4 * density).toInt() }
                    }
                    binding.eventFieldContainer.addView(editText)
                    eventFieldInputMap[field.id] = editText
                }
            }
        }
    }

    private fun eventFieldWidgetValue(widget: Any): String = when (widget) {
        is android.widget.EditText -> widget.text.toString().trim()
        is android.widget.Spinner -> {
            val pos = widget.selectedItemPosition
            if (pos <= 0) "" else widget.selectedItem?.toString() ?: ""
        }
        else -> ""
    }

    /** 빈 값은 저장하지 않는다 — replaceAllByEvent가 전체 교체하므로 삭제와 동치 */
    private fun collectEventFieldValues(): List<EventFieldValue> {
        val result = mutableListOf<EventFieldValue>()
        for (field in eventFields) {
            val widget = eventFieldInputMap[field.id] ?: continue
            val value = eventFieldWidgetValue(widget)
            if (value.isNotBlank()) {
                result.add(
                    EventFieldValue(
                        eventId = editingEvent?.id ?: 0,
                        fieldDefinitionId = field.id,
                        value = value
                    )
                )
            }
        }
        return result
    }

    private fun setupNovelCheckboxes() {
        val recyclerView = binding.novelSelectRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
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
                checkBox.isChecked = selectedNovelIds.contains(novel.id)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedNovelIds.add(novel.id)
                    else selectedNovelIds.remove(novel.id)
                    // 작품 선택 변경 시 캐릭터 정렬 + 사건 필드(세계관 기준) 갱신
                    setupCharacterCheckboxes()
                    rebuildEventFieldSection()
                }
            }

            override fun getItemCount() = novels.size
        }
    }

    private fun setupCharacterCheckboxes() {
        val chars = filteredChars()
        val recyclerView = binding.characterSelectRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
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
                val character = chars[position]
                val checkBox = holder.itemView as CheckBox
                checkBox.text = character.name
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = selectedCharIds.contains(character.id)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedCharIds.add(character.id)
                    else selectedCharIds.remove(character.id)
                }
            }

            override fun getItemCount() = chars.size
        }
    }

    companion object {
        const val TAG = "EventEditDialogFragment"
        private const val ARG_EVENT_JSON = "eventJson"
        private const val ARG_PRE_CHAR_IDS = "preSelectedCharacterIds"
        private const val ARG_PRE_NOVEL_IDS = "preSelectedNovelIds"
        private const val STATE_CHAR_IDS = "selectedCharIds"
        private const val STATE_NOVEL_IDS = "selectedNovelIds"
        private const val STATE_EVENT_FIELD_VALUES = "eventFieldValues"

        /**
         * 다이얼로그 표시. 호스트 프래그먼트의 childFragmentManager로 띄워야
         * 재생성 시 parentFragment를 통해 [Host]를 다시 찾을 수 있다.
         */
        fun show(
            fragmentManager: FragmentManager,
            event: TimelineEvent? = null,
            preSelectedCharacterIds: Set<Long> = emptySet(),
            preSelectedNovelIds: List<Long> = emptyList()
        ) {
            val fragment = EventEditDialogFragment()
            fragment.arguments = bundleOf(
                ARG_EVENT_JSON to event?.let { Gson().toJson(it) },
                ARG_PRE_CHAR_IDS to preSelectedCharacterIds.toLongArray(),
                ARG_PRE_NOVEL_IDS to preSelectedNovelIds.toLongArray()
            )
            fragment.show(fragmentManager, TAG)
        }
    }
}
