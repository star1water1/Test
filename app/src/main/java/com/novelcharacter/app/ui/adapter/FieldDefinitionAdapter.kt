package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.databinding.ItemFieldDefinitionBinding

class FieldDefinitionAdapter(
    private val onClick: (FieldDefinition) -> Unit,
    private val onLongClick: (FieldDefinition) -> Unit
) : ListAdapter<FieldDefinition, FieldDefinitionAdapter.FieldViewHolder>(FieldDiffCallback()) {

    /** 드래그는 핸들 전용 — 세계관·작품·캐릭터·연표 목록과 같은 배선 (isLongPressDragEnabled=false 짝) */
    var itemTouchHelper: ItemTouchHelper? = null

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

            val semanticRole = SemanticRole.fromConfig(field.config)
            if (semanticRole != null) {
                binding.fieldSemanticBadge.text = semanticRole.label
                binding.fieldSemanticBadge.visibility = View.VISIBLE
            } else {
                binding.fieldSemanticBadge.visibility = View.GONE
            }

            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(this)
                }
                false
            }

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
