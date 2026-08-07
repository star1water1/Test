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
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemFieldDefinitionBinding

class FieldDefinitionAdapter(
    private val onClick: (FieldDefinition) -> Unit,
    private val onLongClick: (FieldDefinition) -> Unit,
    /**
     * A-1: AI 추천 대상 — 상태 버튼을 누르면 호출된다(B-80으로 3단이 되어 스위치가 아니다).
     * 메뉴 표시·저장은 화면이 맡는다 — 어댑터는 앵커만 넘긴다.
     */
    private val onAiModeClick: (FieldDefinition, View) -> Unit,
    /** A-2: 필드 설명 ⓘ — 설명이 있는 필드에만 노출된다 */
    private val onInfoClick: (FieldDefinition) -> Unit
) : ListAdapter<FieldDefinition, FieldDefinitionAdapter.FieldViewHolder>(FieldDiffCallback()) {

    /** 드래그는 핸들 전용 — 세계관·작품·캐릭터·연표 목록과 같은 배선 (isLongPressDragEnabled=false 짝) */
    var itemTouchHelper: ItemTouchHelper? = null

    companion object {
        /**
         * 목록 행의 짧은 라벨. 편집 다이얼로그의 [FieldAiPolicy.SuggestMode.label]은 설명이 붙은
         * 긴 문장이라 좁은 행에 들어가지 않는다 — 같은 상태를 길이만 달리 부른다.
         */
        fun shortLabelRes(mode: FieldAiPolicy.SuggestMode): Int = when (mode) {
            FieldAiPolicy.SuggestMode.ALL -> R.string.field_ai_mode_all_short
            FieldAiPolicy.SuggestMode.MANUAL_ONLY -> R.string.field_ai_mode_manual_short
            FieldAiPolicy.SuggestMode.OFF -> R.string.field_ai_mode_off_short
        }
    }

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

            // 전역 기본 필드에서 심긴 필드(B-119) — 표식은 config의 키 하나이고, 그것을
            // 읽는 규칙은 DefaultFieldRef가 단일 소스다.
            binding.fieldDefaultBadge.visibility =
                if (com.novelcharacter.app.data.model.DefaultFieldRef.isLinked(field.config)) View.VISIBLE
                else View.GONE

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

            // AI 추천 토글 (A-1). 캐릭터 필드에만 AI 추천 경로가 있다 — 사건·작품 필드에
            // 아무 일도 하지 않는 스위치를 두지 않는다(R-24: 성립하지 않는 조합의 설정은
            // 보이지 않는다). **조건은 '캐릭터인가'로 쓴다** — 종류를 하나씩 빼는 형태로 쓰면
            // 종류가 늘 때마다 여기가 뒤처지고, 뒤처진 결과가 '아무 일도 안 하는 스위치'다.
            if (field.entityType != FieldDefinition.ENTITY_CHARACTER) {
                binding.btnAiMode.setOnClickListener(null)
                binding.btnAiMode.visibility = View.GONE
            } else {
                binding.btnAiMode.visibility = View.VISIBLE
                // B-80: 3단이라 스위치가 아니라 상태 라벨 + 메뉴다. 상태를 글자로 띄우므로
                // 재바인딩에서 리스너가 먼저 불리는 문제(스위치의 setChecked)가 애초에 없다.
                binding.btnAiMode.setText(shortLabelRes(FieldAiPolicy.suggestMode(field.config)))
                binding.btnAiMode.setOnClickListener { anchor -> onAiModeClick(field, anchor) }
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
