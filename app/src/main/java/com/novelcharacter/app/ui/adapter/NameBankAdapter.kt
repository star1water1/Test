package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.NameBankEntry

class NameBankAdapter(
    private val onClick: (NameBankEntry) -> Unit,
    private val onLongClick: (NameBankEntry) -> Unit
) : ListAdapter<NameBankEntry, NameBankAdapter.NameBankViewHolder>(NameBankDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NameBankViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_name_bank, parent, false)
        return NameBankViewHolder(view)
    }

    override fun onBindViewHolder(holder: NameBankViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NameBankViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.nameText)
        private val genderText: TextView = itemView.findViewById(R.id.genderText)
        private val originText: TextView = itemView.findViewById(R.id.originText)
        private val notesText: TextView = itemView.findViewById(R.id.notesText)
        private val usedIndicator: TextView = itemView.findViewById(R.id.usedIndicator)

        fun bind(entry: NameBankEntry) {
            nameText.text = entry.name

            genderText.text = entry.gender.ifBlank { "" }
            genderText.visibility = if (entry.gender.isNotBlank()) View.VISIBLE else View.GONE

            originText.text = entry.origin.ifBlank { "" }
            originText.visibility = if (entry.origin.isNotBlank()) View.VISIBLE else View.GONE

            notesText.text = entry.notes
            notesText.visibility = if (entry.notes.isNotBlank()) View.VISIBLE else View.GONE

            if (entry.isUsed) {
                usedIndicator.visibility = View.VISIBLE
                usedIndicator.text = itemView.context.getString(R.string.used_indicator)
                itemView.alpha = 0.6f
            } else {
                usedIndicator.visibility = View.GONE
                itemView.alpha = 1.0f
            }

            itemView.setOnClickListener { onClick(entry) }
            itemView.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }
    }

    class NameBankDiffCallback : DiffUtil.ItemCallback<NameBankEntry>() {
        override fun areItemsTheSame(oldItem: NameBankEntry, newItem: NameBankEntry) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: NameBankEntry, newItem: NameBankEntry) = oldItem == newItem
    }
}
