package com.novelcharacter.app.ui.field

import androidx.annotation.StringRes
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 필드가 **어느 종류의 것인가**를 사람 말로 — 캐릭터 · 사건 · 작품.
 *
 * ## 왜 모으는가
 *
 * 이 판정을 손으로 적은 자리가 다섯이었고 **그중 하나가 종류 하나를 빠뜨렸다**(프리셋 필드
 * 편집 목록이 사건만 처리해 작품 필드가 캐릭터 필드와 똑같이 보였다). 저장 쪽은 작품 축을
 * 들이면서 셋으로 늘었는데 표시 쪽 하나가 둘에 남은 모양이고, R-29가 이름 붙인
 * *"열거는 그 자체가 다음 실수의 예약"*이 정확히 그것이다.
 *
 * **캐릭터가 접두를 안 받는 것이 규칙이다** — 이 앱의 기본 축이라 접두를 붙이면 모든 줄에
 * 같은 말이 붙어 정보가 되지 못한다. 그래서 접두 물음은 [prefixResOrNull]로 갈라 둔다.
 */
object FieldTargetLabel {

    /** 배지·목록 어휘 — 어느 종류든 이름이 있다. */
    @StringRes
    fun resOf(entityType: String): Int = when (entityType) {
        FieldDefinition.ENTITY_EVENT -> R.string.field_target_event
        FieldDefinition.ENTITY_NOVEL -> R.string.field_target_novel
        else -> R.string.field_target_character
    }

    /** 접두 관용구 — 캐릭터(기본 축)는 붙이지 않으므로 `null`. */
    @StringRes
    fun prefixResOrNull(entityType: String): Int? =
        if (entityType == FieldDefinition.ENTITY_CHARACTER) null else resOf(entityType)
}
