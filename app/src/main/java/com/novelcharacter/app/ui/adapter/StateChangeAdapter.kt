package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.databinding.ItemStateChangeBinding

class StateChangeAdapter(
    private val onClick: (CharacterStateChange) -> Unit,
    private val onLongClick: (CharacterStateChange) -> Unit
) : ListAdapter<CharacterStateChange, StateChangeAdapter.StateChangeViewHolder>(StateChangeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StateChangeViewHolder {
        val binding = ItemStateChangeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StateChangeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StateChangeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StateChangeViewHolder(
        private val binding: ItemStateChangeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(change: CharacterStateChange) {
            // Year label with optional month/day
            val yearText = buildString {
                append(change.year)
                if (change.month != null) {
                    append(".${change.month}")
                    if (change.day != null) {
                        append(".${change.day}")
                    }
                }
            }
            binding.textYear.text = yearText

            // Field key - display a friendly name for special keys
            binding.textFieldKey.text = when (change.fieldKey) {
                CharacterStateChange.KEY_BIRTH -> "탄생"
                CharacterStateChange.KEY_DEATH -> "사망"
                CharacterStateChange.KEY_ALIVE -> "생존상태"
                CharacterStateChange.KEY_AGE -> "나이"
                else -> change.fieldKey
            }

            // New value
            binding.textNewValue.text = change.newValue

            // Description
            if (change.description.isNotBlank()) {
                binding.textDescription.visibility = View.VISIBLE
                binding.textDescription.text = change.description
            } else {
                binding.textDescription.visibility = View.GONE
            }

            binding.root.setOnClickListener { onClick(change) }
            binding.root.setOnLongClickListener {
                onLongClick(change)
                true
            }
        }
    }

    class StateChangeDiffCallback : DiffUtil.ItemCallback<CharacterStateChange>() {
        override fun areItemsTheSame(oldItem: CharacterStateChange, newItem: CharacterStateChange) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: CharacterStateChange, newItem: CharacterStateChange) =
            oldItem == newItem
    }
}
