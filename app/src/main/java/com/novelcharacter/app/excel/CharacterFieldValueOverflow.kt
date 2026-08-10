package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * '캐릭터 필드값' 오버플로 시트에 들어갈 값을 고르는 순수 로직 (Android 비의존 — 단위 테스트 대상).
 *
 * 캐릭터 시트는 그 시트 세계관의 필드만 열로 만든다. 그 열 집합(covered) 밖의 값은 캐릭터 시트가
 * 표현할 수 없으므로 내보내기에서 그대로 사라진다. 판정 규칙을 내보내기 코드 안에 흩어 두면
 * "어떤 값이 담겼는가"의 단일 소스가 사라지므로 여기 한 곳에 모은다.
 */
object CharacterFieldValueOverflow {

    /** 계산 필드는 수식으로 산출되므로 저장 대상이 아니다 — 가져오기(importCharacterRows)와 대칭. */
    const val TYPE_CALCULATED = "CALCULATED"

    /**
     * [values] 중 캐릭터 시트가 열로 담지 못한 것만 골라낸다.
     *
     * @param coveredFieldIds 그 캐릭터의 시트가 열로 만든 필드 id 집합. 세계관 캐릭터는 그
     *   세계관의 필드, **미분류 캐릭터는 전역 구역의 필드**다 (B-149 — 종전에는 빈 집합이라
     *   무소속의 전역 필드 값이 전부 오버플로로 나갔다). 채우는 쪽과 여기가 갈리면 같은 값이
     *   두 시트에 겹쳐 나가거나 어느 시트에도 안 나간다 — 그래서 채우는 자리가 단일 소스다.
     * @param fieldsById 필드 정의 조회 — 정의가 사라진 고아값은 복원할 정체성이 없으므로 제외한다
     */
    fun select(
        values: List<CharacterFieldValue>,
        coveredFieldIds: Set<Long>,
        fieldsById: Map<Long, FieldDefinition>
    ): List<Pair<CharacterFieldValue, FieldDefinition>> =
        values.mapNotNull { v ->
            if (v.fieldDefinitionId in coveredFieldIds) return@mapNotNull null
            val fd = fieldsById[v.fieldDefinitionId] ?: return@mapNotNull null
            if (fd.type == TYPE_CALCULATED) return@mapNotNull null
            if (v.value.isBlank()) return@mapNotNull null
            v to fd
        }
}
