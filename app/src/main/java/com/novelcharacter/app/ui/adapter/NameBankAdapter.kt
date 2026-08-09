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
    private val onToggleSelect: (NameBankEntry) -> Unit = {},
    /** 사용 캐릭터 줄 탭 — 그 캐릭터로 이동한다 (B-124 ⓒ). */
    private val onOpenCharacter: (Long) -> Unit = {}
) : ListAdapter<NameBankEntry, NameBankAdapter.NameBankViewHolder>(NameBankDiffCallback()) {

    // 일괄 캐릭터 등록용 선택 모드 (FieldValueListFragment 선택 패턴)
    private var selectionMode = false
    private var selectedIds: Set<Long> = emptySet()

    /**
     * 사용 캐릭터 id → 이름 (B-124 ⓒ).
     *
     * **비어 있는 것과 없는 것을 가른다** — 이름표가 아직 안 실렸으면 종전대로 "사용됨"만
     * 찍고, 실렸는데 그 id가 없으면 *캐릭터를 찾을 수 없음*이다(외래키가 SET NULL이라
     * 정상적으로는 안 생기지만, 생겼을 때 조용히 "사용됨"으로 뭉뚱그리면 고칠 길이 없다).
     */
    private var usedByNames: Map<Long, String>? = null

    fun setSelectionState(mode: Boolean, selected: Set<Long>) {
        selectionMode = mode
        selectedIds = selected
        notifyDataSetChanged()
    }

    fun setUsedByNames(names: Map<Long, String>) {
        usedByNames = names
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
        private val usedByText: TextView = itemView.findViewById(R.id.usedByText)
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

            // 누가 쓰는가 — 종전에는 `usedByCharacterId`를 아무도 읽지 않아 이 연결이
            // 엑셀 시트에만 있었다(B-124 ⓒ). 선택 모드에서는 숨긴다 — 그때 탭은 선택이지 이동이 아니다.
            val names = usedByNames
            val usedById = entry.usedByCharacterId
            if (entry.isUsed && usedById != null && names != null && !selectionMode) {
                val who = names[usedById]
                usedByText.text = if (who != null) {
                    itemView.context.getString(R.string.name_bank_used_by_label, who)
                } else {
                    itemView.context.getString(R.string.name_bank_used_by_missing)
                }
                usedByText.visibility = View.VISIBLE
                usedByText.setOnClickListener { if (who != null) onOpenCharacter(usedById) }
            } else {
                usedByText.visibility = View.GONE
                usedByText.setOnClickListener(null)
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
