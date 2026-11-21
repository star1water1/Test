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

            // 모르는 타입은 저장된 글자를 그대로 보인다 — 사용자가 무엇이 잘못됐는지 볼 수 있어야 한다.
            // **`valueOf` + try/catch를 쓰지 않는다** (B-55): 그쪽은 모르는 타입마다 예외를 지어
            // 스택 트레이스를 채우는데, 여기는 목록이 스크롤될 때마다 도는 자리다.
            binding.fieldTypeBadge.text = field.fieldType?.label ?: field.type

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

            // **핸들을 누르는 동안 행의 롱프레스를 끈다** (2026.11.07 워크스루 S2 관찰).
            //
            // 종전에는 핸들을 0.5초 넘게 누른 뒤 끌면 **순서도 바뀌고 롱프레스 메뉴도 함께**
            // 떴다. 까닭은 이 목록만 **핸들이 늘 보이고 행 롱클릭도 늘 살아 있는** 구성이기
            // 때문이다 — 형제 목록(세계관·작품·캐릭터·연표)은 핸들을 재배치 모드에서만 띄우고
            // 그 모드에서 행 롱클릭을 끄므로 두 조작이 겹칠 자리가 애초에 없다. 관찰은 이것을
            // *"다섯 목록 공통"*으로 적었지만 실제로는 이 한 자리다(2026.11.21 전수 대조).
            //
            // **끌기 경로는 손대지 않는다.** 핸들이 터치를 삼키게 하는 쪽(`true` 반환)이 더
            // 짧지만, 그러면 `ItemTouchHelper`가 이어지는 이벤트를 받는 경로가 달라진다 —
            // 기기에서 확인할 수 없는 변경이라 고르지 않았다. 대신 **롱프레스 타이머가 서지
            // 못하게** 한다: 이 리스너는 행의 `onTouchEvent`보다 **먼저** 불리므로(자식이
            // 소비하지 않으면 그다음에 부모가 받는다), 여기서 끄면 그 DOWN은 타이머를 걸지
            // 못한다. 되돌리기는 `post`로 — DOWN 전달이 끝난 다음 턴이라 안전하고, 탭만 하고
            // 끌지 않은 경우에도 롱클릭이 꺼진 채 남지 않는다.
            binding.dragHandle.setOnTouchListener { handle, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    binding.root.isLongClickable = false
                    handle.post { binding.root.isLongClickable = true }
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
