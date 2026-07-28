package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldDescription
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.databinding.ItemFieldDefinitionBinding

class FieldDefinitionAdapter(
    private val onClick: (FieldDefinition) -> Unit,
    private val onLongClick: (FieldDefinition) -> Unit,
    /** A-1: AI 추천 토글 — 1탭 즉시 저장(성공 무통보). 편집 다이얼로그의 스위치와 같은 소스를 쓴다 */
    private val onAiToggle: (FieldDefinition, Boolean) -> Unit,
    /** A-2: 필드 설명 ⓘ — 설명이 있는 필드에만 노출된다 */
    private val onInfoClick: (FieldDefinition) -> Unit
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

            // 필드 설명 ⓘ (A-2) — 설명이 없는 필드에는 아이콘을 만들지 않는다
            val hasDescription = FieldDescription.fromConfig(field.config).isNotBlank()
            binding.btnFieldInfo.visibility = if (hasDescription) View.VISIBLE else View.GONE
            binding.btnFieldInfo.setOnClickListener { onInfoClick(field) }

            // AI 추천 토글 (A-1). 사건 필드에는 AI 추천 경로 자체가 없다 — 아무 일도 하지 않는
            // 스위치를 두지 않는다(R-24: 성립하지 않는 조합의 설정은 보이지 않는다).
            if (field.entityType == FieldDefinition.ENTITY_EVENT) {
                binding.switchAiSuggest.setOnCheckedChangeListener(null)
                binding.switchAiSuggest.visibility = View.GONE
            } else {
                binding.switchAiSuggest.visibility = View.VISIBLE
                // 재바인딩 시 setChecked가 이전 행의 리스너를 부르지 않도록 먼저 해제한다
                binding.switchAiSuggest.setOnCheckedChangeListener(null)
                binding.switchAiSuggest.isChecked = FieldAiPolicy.isSuggestEnabled(field.config)
                binding.switchAiSuggest.setOnCheckedChangeListener { _, checked ->
                    if (checked != FieldAiPolicy.isSuggestEnabled(field.config)) {
                        onAiToggle(field, checked)
                    }
                }
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
