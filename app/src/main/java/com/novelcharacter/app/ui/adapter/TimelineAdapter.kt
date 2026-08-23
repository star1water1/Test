package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemTimelineBinding
import com.novelcharacter.app.util.CardFieldSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sealed class representing items displayed in the timeline.
 * Can be either an individual event or a grouped summary header.
 */
sealed class TimelineDisplayItem {
    /**
     * @param fieldSummary 카드에 얹을 사건 커스텀 필드값 (B-5). 표시 데이터를 항목에 담는 이유는
     *   DiffUtil 때문이다 — 어댑터 바깥 맵으로 두면 값이 바뀌어도 항목이 같아 보여
     *   목록 전체를 강제로 다시 그려야 한다.
     */
    data class EventItem(
        val event: TimelineEvent,
        val fieldSummary: CardFieldSummary.Summary = CardFieldSummary.empty()
    ) : TimelineDisplayItem()
    data class GroupHeader(
        val label: String,
        val eventCount: Int,
        val events: List<TimelineEvent>
    ) : TimelineDisplayItem()
}

class TimelineAdapter(
    private val onClick: (TimelineEvent) -> Unit,
    private val onLongClick: (TimelineEvent) -> Unit,
    private val coroutineScope: CoroutineScope,
    private val loadCharactersForEvent: suspend (Long) -> List<Character> = { emptyList() }
) : ListAdapter<TimelineDisplayItem, RecyclerView.ViewHolder>(TimelineDisplayDiffCallback()) {

    /** 사건 ID → 연결 작품명 리스트 (외부에서 설정) */
    var novelNamesMap: Map<Long, List<String>> = emptyMap()
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    /**
     * 사건 ID → 등장 캐릭터 (외부에서 **한 번에** 실어 준다).
     *
     * **`null`은 "아직 못 받았다"이고 빈 목록은 "등장이 없다"이다** — 둘을 같게 다루면
     * 등장 없는 카드마다 폴백 질의가 다시 난다. 표가 실린 뒤에는 바인딩이 **질의를 띄우지
     * 않는다**: 종전에는 카드가 그려질 때마다 코루틴 하나와 질의 하나가 났고, 되돌아오면
     * 같은 카드에 또 났다(사건 수백 건에서 스크롤이 그대로 느려지는 자리).
     *
     * 표를 안 실은 화면(캐릭터 상세의 연표 절)은 종전처럼 [loadCharactersForEvent]로 간다 —
     * 그쪽은 한 캐릭터의 사건만 그리므로 카드 수가 애초에 적다.
     */
    var charactersMap: Map<Long, List<Character>>? = null
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    var isReorderMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    var zoomLevel: Int = 4
        set(value) {
            if (field != value) {
                field = value
                reprocessEvents()
            }
        }

    /**
     * 사건 ID → 카드에 얹을 필드값 요약 (B-5, 외부에서 설정).
     * 항목에 실어 보내므로 DiffUtil이 바뀐 카드만 다시 그린다.
     */
    var fieldSummaries: Map<Long, CardFieldSummary.Summary> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            // 재정렬 중에는 목록을 다시 만들지 않는다. reprocessEvents는 rawEvents 순서로 되돌리는데,
            // getVisualOrderEvents가 읽는 것은 사용자가 끌어 옮긴 currentList다 —
            // 비동기로 도착한 요약이 여기서 끼어들면 드래그한 순서가 통째로 사라진다.
            // 재정렬을 끝내면 순서 저장 → 목록 재방출로 이어져 요약이 다시 실린다.
            if (!isReorderMode) reprocessEvents()
        }

    private var rawEvents: List<TimelineEvent> = emptyList()

    /**
     * Accept a list of TimelineEvent and convert to display items based on zoom level.
     */
    fun submitEventList(events: List<TimelineEvent>) {
        rawEvents = events.toList()
        reprocessEvents()
    }

    /**
     * Move item from [fromPosition] to [toPosition] during drag-and-drop.
     * Only allows movement between EventItems (not GroupHeaders).
     * Returns true if the move was successful.
     */
    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        val fromItem = currentList.getOrNull(fromPosition)
        val toItem = currentList.getOrNull(toPosition)
        if (fromItem !is TimelineDisplayItem.EventItem || toItem !is TimelineDisplayItem.EventItem) return false

        val mutableList = currentList.toMutableList()
        java.util.Collections.swap(mutableList, fromPosition, toPosition)
        submitList(mutableList)
        return true
    }

    /**
     * 드래그가 끝난 뒤 **사용자가 화면에서 만든 차례** 그대로의 사건 목록.
     *
     * `displayOrder`를 여기서 매기지 않는 것이 요점이다 (B-47) — 화면은 역순일 수 있고,
     * 그때 보이는 차례는 저장 순서의 뒤집힌 모습이다. 번호를 매기는 일은 표시 방향을
     * 아는 쪽(`TimelineViewModel` → [com.novelcharacter.app.util.TimelineDisplayOrder])이 한다.
     */
    fun getVisualOrderEvents(): List<TimelineEvent> =
        currentList.filterIsInstance<TimelineDisplayItem.EventItem>().map { it.event }

    private fun eventItem(event: TimelineEvent) = TimelineDisplayItem.EventItem(
        event, fieldSummaries[event.id] ?: CardFieldSummary.empty()
    )

    private fun reprocessEvents() {
        val displayItems = when (zoomLevel) {
            1 -> groupEvents(rawEvents, 1000)  // Group by 1000-year intervals
            2 -> groupEvents(rawEvents, 100)    // Group by 100-year intervals
            3 -> groupEvents(rawEvents, 10)     // Group by 10-year intervals
            4 -> rawEvents.map { eventItem(it) }  // Individual events
            5 -> rawEvents.map { eventItem(it) }  // Individual events with month/day
            else -> rawEvents.map { eventItem(it) }
        }
        submitList(displayItems)
    }

    /**
     * Group events by the given interval size.
     * For example, interval=100 groups events into centuries.
     */
    private fun groupEvents(events: List<TimelineEvent>, interval: Int): List<TimelineDisplayItem> {
        if (events.isEmpty()) return emptyList()

        val groups = events.groupBy { event ->
            if (event.year >= 0) {
                (event.year / interval) * interval
            } else {
                ((event.year - interval + 1) / interval) * interval
            }
        }

        return groups.entries.sortedBy { it.key }.map { (groupStart, groupEvents) ->
            val groupEnd = groupStart + interval - 1
            val label = formatGroupLabel(groupStart, groupEnd)
            TimelineDisplayItem.GroupHeader(
                label = label,
                eventCount = groupEvents.size,
                events = groupEvents
            )
        }
    }

    /**
     * Format a group label with proper handling for negative (BC) years.
     * Ensures chronological ordering: earlier year on the left, later year on the right.
     */
    private fun formatGroupLabel(groupStart: Int, groupEnd: Int): String {
        val startStr = formatYearLabel(groupStart)
        val endStr = formatYearLabel(groupEnd)
        return "$startStr ~ $endStr"
    }

    private fun formatYearLabel(year: Int): String {
        return if (year < 0) "BC ${-year}" else "${year}"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemTimelineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TimelineViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as TimelineViewHolder).bind(getItem(position))
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is TimelineViewHolder) {
            holder.cancelLoad()
            holder.clearChips()
        }
    }

    inner class TimelineViewHolder(
        private val binding: ItemTimelineBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null
        private var boundEventId: Long = -1

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun clearChips() {
            binding.characterChipGroup.removeAllViews()
        }

        fun bind(item: TimelineDisplayItem) {
            // Cancel any ongoing coroutine from previous bind
            loadJob?.cancel()
            when (item) {
                is TimelineDisplayItem.EventItem -> bindEvent(item.event, item.fieldSummary)
                is TimelineDisplayItem.GroupHeader -> bindGroup(item)
            }
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        private fun bindEvent(event: TimelineEvent, fieldSummary: CardFieldSummary.Summary) {
            loadJob?.cancel()
            boundEventId = event.id
            binding.yearText.text = formatEventDate(event)
            binding.calendarTypeText.text = event.calendarType

            // 드래그 핸들 표시/숨김
            binding.dragHandle.visibility = if (isReorderMode) android.view.View.VISIBLE else android.view.View.GONE
            if (isReorderMode) {
                binding.dragHandle.setOnTouchListener { _, motionEvent ->
                    if (motionEvent.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        onStartDrag?.invoke(this)
                    }
                    false
                }
            } else {
                binding.dragHandle.setOnTouchListener(null)
            }
            binding.descriptionText.text = if (event.isTemporary) {
                "📌 ${event.description}"
            } else {
                event.description
            }
            // 간편 사건 시각적 구분: 반투명 배경
            binding.root.alpha = if (event.isTemporary) 0.7f else 1.0f

            // 사건 커스텀 필드값 (B-5). 상한을 넘어 잘린 줄은 개수로 존재를 알린다.
            if (fieldSummary.isEmpty) {
                binding.eventFieldsText.visibility = android.view.View.GONE
                binding.eventFieldsMoreText.visibility = android.view.View.GONE
            } else {
                binding.eventFieldsText.text = fieldSummary.lines.joinToString("\n") {
                    binding.root.context.getString(R.string.card_field_line_format, it.label, it.value)
                }
                binding.eventFieldsText.visibility = android.view.View.VISIBLE
                if (fieldSummary.hiddenCount > 0) {
                    binding.eventFieldsMoreText.text = binding.root.context.getString(
                        R.string.card_field_more_format, fieldSummary.hiddenCount
                    )
                    binding.eventFieldsMoreText.visibility = android.view.View.VISIBLE
                } else {
                    binding.eventFieldsMoreText.visibility = android.view.View.GONE
                }
            }

            // 연결 작품명 표시
            val novelNames = novelNamesMap[event.id]
            if (!novelNames.isNullOrEmpty()) {
                binding.novelNamesText.text = novelNames.joinToString(", ")
                binding.novelNamesText.visibility = android.view.View.VISIBLE
            } else {
                binding.novelNamesText.visibility = android.view.View.GONE
            }

            // Load related character chips via callback (repository 경유)
            binding.characterChipGroup.removeAllViews()
            val eventId = event.id
            val prefetched = charactersMap
            if (prefetched != null) {
                // 표가 실려 있으면 **질의도 코루틴도 띄우지 않는다.**
                addCharacterChips(prefetched[eventId].orEmpty())
            } else {
                loadJob = coroutineScope.launch {
                    val characters = loadCharactersForEvent(eventId)
                    // Double-check: boundEventId may change if ViewHolder was recycled and rebound
                    if (boundEventId == eventId && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        addCharacterChips(characters)
                    }
                }
            }

            binding.root.setOnClickListener { onClick(event) }
            binding.root.setOnLongClickListener {
                onLongClick(event)
                true
            }
        }

        /** 등장 캐릭터 칩 — 실어 온 표에서 오든 질의에서 오든 그리는 법은 하나다(R-33). */
        private fun addCharacterChips(characters: List<Character>) {
            for (character in characters) {
                val chip = Chip(binding.root.context).apply {
                    text = character.name
                    textSize = 11f
                    isClickable = false
                }
                binding.characterChipGroup.addView(chip)
            }
        }

        private fun bindGroup(group: TimelineDisplayItem.GroupHeader) {
            binding.dragHandle.visibility = android.view.View.GONE
            binding.novelNamesText.visibility = android.view.View.GONE
            // 묶음 머리글은 사건 하나가 아니므로 필드값을 얹지 않는다 (재활용된 뷰의 잔상 제거).
            binding.eventFieldsText.visibility = android.view.View.GONE
            binding.eventFieldsMoreText.visibility = android.view.View.GONE
            binding.yearText.text = group.label
            binding.calendarTypeText.text = ""
            binding.descriptionText.text = binding.root.context.getString(R.string.event_count_format, group.eventCount)

            binding.characterChipGroup.removeAllViews()

            // Click on a group header opens the first event for simplicity
            if (group.events.isNotEmpty()) {
                binding.root.setOnClickListener { onClick(group.events.first()) }
                binding.root.setOnLongClickListener {
                    onLongClick(group.events.first())
                    true
                }
            } else {
                binding.root.setOnClickListener(null)
                binding.root.setOnLongClickListener(null)
            }
        }

        /**
         * Format the event date based on the current zoom level.
         * - Zoom levels 1-4: show year only
         * - Zoom level 5: show year/month/day detail
         */
        private fun formatEventDate(event: TimelineEvent): String {
            val yearStr = formatYearLabel(event.year)
            return if (zoomLevel == 5) {
                val parts = mutableListOf(yearStr)
                event.month?.let { parts.add(binding.root.context.getString(R.string.month_suffix, it)) }
                event.day?.let { parts.add(binding.root.context.getString(R.string.day_suffix, it)) }
                parts.joinToString(" ")
            } else {
                yearStr
            }
        }
    }

    class TimelineDisplayDiffCallback : DiffUtil.ItemCallback<TimelineDisplayItem>() {
        override fun areItemsTheSame(
            oldItem: TimelineDisplayItem,
            newItem: TimelineDisplayItem
        ): Boolean {
            return when {
                oldItem is TimelineDisplayItem.EventItem && newItem is TimelineDisplayItem.EventItem ->
                    oldItem.event.id == newItem.event.id
                oldItem is TimelineDisplayItem.GroupHeader && newItem is TimelineDisplayItem.GroupHeader ->
                    oldItem.label == newItem.label
                else -> false
            }
        }

        override fun areContentsTheSame(
            oldItem: TimelineDisplayItem,
            newItem: TimelineDisplayItem
        ): Boolean {
            return oldItem == newItem
        }
    }
}
