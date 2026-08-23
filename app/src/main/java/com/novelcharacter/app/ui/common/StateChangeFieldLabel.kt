package com.novelcharacter.app.ui.common

import android.content.Context
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 상태변화의 `fieldKey` → **사람이 읽는 이름**. 이 앱에서 그 번역이 사는 유일한 자리다.
 *
 * **왜 모았나.** 목록 행은 특수 키 넷을 번역해 *'출생'*이라 보여 주는데, 그 행을 눌러 열리는
 * 수정·삭제 창은 `"${change.fieldKey} → ${change.newValue}"`라 **내부 키를 날것으로**
 * 보여 줬다(`__birth`·`hair_color`). 같은 데이터가 한 걸음 만에 다른 이름으로 불린 것이다.
 * 게다가 목록 행조차 커스텀 필드는 번역하지 못했다 — 필드 정의를 안 봤기 때문이다.
 *
 * [fields]를 주면 커스텀 필드의 이름까지 붙는다. 안 주면 특수 키만 번역하고 나머지는
 * 키 그대로다(호출부가 필드 목록을 손에 쥐지 못한 자리 — 목록 어댑터가 그렇다).
 */
object StateChangeFieldLabel {

    fun of(
        context: Context,
        fieldKey: String,
        fields: List<FieldDefinition> = emptyList()
    ): String = when (fieldKey) {
        CharacterStateChange.KEY_BIRTH -> context.getString(R.string.birth)
        CharacterStateChange.KEY_DEATH -> context.getString(R.string.death)
        CharacterStateChange.KEY_ALIVE -> context.getString(R.string.alive_status)
        CharacterStateChange.KEY_AGE -> context.getString(R.string.age)
        else -> fields.firstOrNull { it.key == fieldKey }?.name ?: fieldKey
    }
}
