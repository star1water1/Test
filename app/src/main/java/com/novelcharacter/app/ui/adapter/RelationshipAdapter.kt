package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R

data class RelationshipDisplayItem(
    val relationshipId: Long,
    val otherCharacterName: String,
    val otherCharacterId: Long,
    val relationshipType: String,
    val description: String
)

class RelationshipAdapter(
    private val onClick: (RelationshipDisplayItem) -> Unit,
    private val onLongClick: (RelationshipDisplayItem) -> Unit
) : ListAdapter<RelationshipDisplayItem, RelationshipAdapter.RelationshipViewHolder>(RelationshipDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RelationshipViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_relationship, parent, false)
        return RelationshipViewHolder(view)
    }

    override fun onBindViewHolder(holder: RelationshipViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RelationshipViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val characterName: TextView = itemView.findViewById(R.id.relationCharacterName)
        private val relationType: TextView = itemView.findViewById(R.id.relationType)
        private val relationDescription: TextView = itemView.findViewById(R.id.relationDescription)

        fun bind(item: RelationshipDisplayItem) {
            characterName.text = item.otherCharacterName
            relationType.text = item.relationshipType
            if (item.description.isNotBlank()) {
                relationDescription.visibility = View.VISIBLE
                relationDescription.text = item.description
            } else {
                relationDescription.visibility = View.GONE
            }
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    class RelationshipDiffCallback : DiffUtil.ItemCallback<RelationshipDisplayItem>() {
        override fun areItemsTheSame(oldItem: RelationshipDisplayItem, newItem: RelationshipDisplayItem) =
            oldItem.relationshipId == newItem.relationshipId
        override fun areContentsTheSame(oldItem: RelationshipDisplayItem, newItem: RelationshipDisplayItem) =
            oldItem == newItem
    }
}
