package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.databinding.ItemFieldDefinitionBinding

class FieldDefinitionAdapter(
    private val onClick: (FieldDefinition) -> Unit,
    private val onLongClick: (FieldDefinition) -> Unit
) : ListAdapter<FieldDefinition, FieldDefinitionAdapter.FieldViewHolder>(FieldDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FieldViewHolder {
        val binding = ItemFieldDefinitionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FieldViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FieldViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun getFieldAt(position: Int): FieldDefinition = getItem(position)

    inner class FieldViewHolder(
        private val binding: ItemFieldDefinitionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(field: FieldDefinition) {
            binding.fieldName.text = field.name
            binding.fieldKey.text = field.key

            val fieldType = try { FieldType.valueOf(field.type) } catch (e: Exception) { null }
            binding.fieldTypeBadge.text = fieldType?.label ?: field.type

            binding.fieldGroup.text = field.groupName

            binding.root.setOnClickListener { onClick(field) }
            binding.root.setOnLongClickListener {
                onLongClick(field)
                true
            }
        }
    }

    class FieldDiffCallback : DiffUtil.ItemCallback<FieldDefinition>() {
        override fun areItemsTheSame(oldItem: FieldDefinition, newItem: FieldDefinition) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: FieldDefinition, newItem: FieldDefinition) = oldItem == newItem
    }
}
