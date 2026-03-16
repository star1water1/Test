package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.databinding.ItemTimelineBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Sealed class representing items displayed in the timeline.
 * Can be either an individual event or a grouped summary header.
 */
sealed class TimelineDisplayItem {
    data class EventItem(val event: TimelineEvent) : TimelineDisplayItem()
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

    var zoomLevel: Int = 4
        set(value) {
            if (field != value) {
                field = value
                reprocessEvents()
            }
        }

    private var rawEvents: List<TimelineEvent> = emptyList()

    /**
     * Accept a list of TimelineEvent and convert to display items based on zoom level.
     */
    fun submitEventList(events: List<TimelineEvent>) {
        rawEvents = events
        reprocessEvents()
    }

    private fun reprocessEvents() {
        val displayItems = when (zoomLevel) {
            1 -> groupEvents(rawEvents, 1000)  // Group by 1000-year intervals
            2 -> groupEvents(rawEvents, 100)    // Group by 100-year intervals
            3 -> groupEvents(rawEvents, 10)     // Group by 10-year intervals
            4 -> rawEvents.map { TimelineDisplayItem.EventItem(it) }  // Individual events
            5 -> rawEvents.map { TimelineDisplayItem.EventItem(it) }  // Individual events with month/day
            else -> rawEvents.map { TimelineDisplayItem.EventItem(it) }
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
            val label = if (groupStart < 0 && groupEnd < 0) {
                "BC ${-groupEnd} ~ BC ${-groupStart}"
            } else if (groupStart < 0) {
                "BC ${-groupStart} ~ $groupEnd"
            } else {
                "$groupStart ~ $groupEnd"
            }
            TimelineDisplayItem.GroupHeader(
                label = label,
                eventCount = groupEvents.size,
                events = groupEvents
            )
        }
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
        }
    }

    inner class TimelineViewHolder(
        private val binding: ItemTimelineBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun bind(item: TimelineDisplayItem) {
            // Cancel any ongoing coroutine from previous bind
            loadJob?.cancel()
            when (item) {
                is TimelineDisplayItem.EventItem -> bindEvent(item.event)
                is TimelineDisplayItem.GroupHeader -> bindGroup(item)
            }
        }

        private fun bindEvent(event: TimelineEvent) {
            binding.yearText.text = formatEventDate(event)
            binding.calendarTypeText.text = event.calendarType
            binding.descriptionText.text = event.description

            // Load related character chips via callback (repository 경유)
            binding.characterChipGroup.removeAllViews()
            loadJob = coroutineScope.launch {
                val characters = loadCharactersForEvent(event.id)
                ensureActive() // Check cancellation before modifying UI
                characters.forEach { character ->
                    val chip = Chip(binding.root.context).apply {
                        text = character.name
                        textSize = 11f
                        isClickable = false
                    }
                    binding.characterChipGroup.addView(chip)
                }
            }

            binding.root.setOnClickListener { onClick(event) }
            binding.root.setOnLongClickListener {
                onLongClick(event)
                true
            }
        }

        private fun bindGroup(group: TimelineDisplayItem.GroupHeader) {
            binding.yearText.text = group.label
            binding.calendarTypeText.text = ""
            binding.descriptionText.text = "${group.eventCount}건의 사건"

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
            val yearStr = formatYear(event.year)
            return if (zoomLevel == 5) {
                val parts = mutableListOf(yearStr)
                event.month?.let { parts.add("${it}월") }
                event.day?.let { parts.add("${it}일") }
                parts.joinToString(" ")
            } else {
                yearStr
            }
        }

        private fun formatYear(year: Int): String {
            return if (year < 0) {
                "BC ${-year}"
            } else {
                "$year"
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
