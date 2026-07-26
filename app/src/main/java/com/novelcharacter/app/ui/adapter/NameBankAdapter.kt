package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.NameBankEntry

class NameBankAdapter(
    private val onClick: (NameBankEntry) -> Unit,
    private val onLongClick: (NameBankEntry) -> Unit,
    private val onToggleSelect: (NameBankEntry) -> Unit = {}
) : ListAdapter<NameBankEntry, NameBankAdapter.NameBankViewHolder>(NameBankDiffCallback()) {

    // 일괄 캐릭터 등록용 선택 모드 (FieldValueListFragment 선택 패턴)
    private var selectionMode = false
    private var selectedIds: Set<Long> = emptySet()

    fun setSelectionState(mode: Boolean, selected: Set<Long>) {
        selectionMode = mode
        selectedIds = selected
        notifyDataSetChanged()
    }

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
        private val selectCheckBox: CheckBox = itemView.findViewById(R.id.selectCheckBox)

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

            selectCheckBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            selectCheckBox.isChecked = selectionMode && entry.id in selectedIds

            itemView.setOnClickListener {
                if (selectionMode) onToggleSelect(entry) else onClick(entry)
            }
            itemView.setOnLongClickListener {
                if (!selectionMode) onLongClick(entry)
                true
            }
        }
    }

    class NameBankDiffCallback : DiffUtil.ItemCallback<NameBankEntry>() {
        override fun areItemsTheSame(oldItem: NameBankEntry, newItem: NameBankEntry) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: NameBankEntry, newItem: NameBankEntry) = oldItem == newItem
    }
}
