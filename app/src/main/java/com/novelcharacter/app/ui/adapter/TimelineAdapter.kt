package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.databinding.ItemTimelineBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimelineAdapter(
    private val onClick: (TimelineEvent) -> Unit,
    private val onLongClick: (TimelineEvent) -> Unit
) : ListAdapter<TimelineEvent, TimelineAdapter.TimelineViewHolder>(TimelineDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimelineViewHolder {
        val binding = ItemTimelineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TimelineViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TimelineViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TimelineViewHolder(
        private val binding: ItemTimelineBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(event: TimelineEvent) {
            binding.yearText.text = formatYear(event.year)
            binding.calendarTypeText.text = event.calendarType
            binding.descriptionText.text = event.description

            // 관련 캐릭터 칩 로드
            binding.characterChipGroup.removeAllViews()
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(binding.root.context)
                val characters = db.timelineDao().getCharactersForEvent(event.id)
                withContext(Dispatchers.Main) {
                    characters.forEach { character ->
                        val chip = Chip(binding.root.context).apply {
                            text = character.name
                            textSize = 11f
                            isClickable = false
                        }
                        binding.characterChipGroup.addView(chip)
                    }
                }
            }

            binding.root.setOnClickListener { onClick(event) }
            binding.root.setOnLongClickListener {
                onLongClick(event)
                true
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

    class TimelineDiffCallback : DiffUtil.ItemCallback<TimelineEvent>() {
        override fun areItemsTheSame(oldItem: TimelineEvent, newItem: TimelineEvent) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TimelineEvent, newItem: TimelineEvent) =
            oldItem == newItem
    }
}
