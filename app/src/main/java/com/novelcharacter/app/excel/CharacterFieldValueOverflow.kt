package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NovelFieldValue

/**
 * 오버플로 시트에 들어갈 값을 고르는 순수 로직 (Android 비의존 — 단위 테스트 대상).
 *
 * 어떤 시트든 **자기가 열로 만든 필드**만 담을 수 있고, 그 열 집합(covered) 밖의 값은 그대로 두면
 * 내보내기에서 사라진다. 판정 규칙을 내보내기 코드 안에 흩어 두면 "어떤 값이 담겼는가"의 단일
 * 소스가 사라지므로 여기 한 곳에 모은다.
 *
 * **셋이 같은 규칙을 쓴다**(2026.08.11, B-65 — 사용자 확정 15번 ㄴ1 *"캐릭터처럼 별도 시트도 만듦"*):
 * 캐릭터 · 작품 · 사건. 규칙을 시트마다 따로 적으면 반드시 드리프트한다(7장 규약) — 그래서
 * 실제 판정은 [FieldValueOverflow] 하나가 하고 아래 셋은 이름만 다른 입구다.
 */
object FieldValueOverflow {

    /** 계산 필드는 수식으로 산출되므로 저장 대상이 아니다 — 가져오기와 대칭. */
    const val TYPE_CALCULATED = "CALCULATED"

    /**
     * [values] 중 그 시트가 열로 담지 못한 것만 골라낸다.
     *
     * @param coveredFieldIds 그 엔티티의 시트가 열로 만든 필드 id 집합. 채우는 쪽과 여기가 갈리면
     *   같은 값이 두 시트에 겹쳐 나가거나 어느 시트에도 안 나간다 — 그래서 채우는 자리가 단일 소스다.
     * @param fieldsById 필드 정의 조회 — 정의가 사라진 고아값은 복원할 정체성이 없으므로 제외한다
     */
    fun <V> select(
        values: List<V>,
        coveredFieldIds: Set<Long>,
        fieldsById: Map<Long, FieldDefinition>,
        fieldIdOf: (V) -> Long,
        valueOf: (V) -> String
    ): List<Pair<V, FieldDefinition>> =
        values.mapNotNull { v ->
            val fieldId = fieldIdOf(v)
            if (fieldId in coveredFieldIds) return@mapNotNull null
            val fd = fieldsById[fieldId] ?: return@mapNotNull null
            if (fd.type == TYPE_CALCULATED) return@mapNotNull null
            if (valueOf(v).isBlank()) return@mapNotNull null
            v to fd
        }
}

/**
 * '캐릭터 필드값' 오버플로 시트의 선별.
 *
 * 캐릭터 시트는 그 시트 세계관의 필드만 열로 만든다. 그 열 집합(covered) 밖의 값은 캐릭터 시트가
 * 표현할 수 없으므로 내보내기에서 그대로 사라진다.
 */
object CharacterFieldValueOverflow {

    /** 계산 필드는 수식으로 산출되므로 저장 대상이 아니다 — 가져오기(importCharacterRows)와 대칭. */
    const val TYPE_CALCULATED = FieldValueOverflow.TYPE_CALCULATED

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
        FieldValueOverflow.select(values, coveredFieldIds, fieldsById, { it.fieldDefinitionId }, { it.value })
}

/**
 * '작품 필드값' 오버플로 시트의 선별 (B-65).
 *
 * 작품 시트는 캐릭터 시트와 달리 **전 구역의 작품 필드를 열로 싣는다.** 그래서 이 시트로 넘어오는
 * 것은 *다른 세계관의 값*이 아니라(그쪽은 이제 열로 되돌아온다 — 확정 15번 ㄱ1) **열이 아예 서지
 * 못한 필드의 값**이다: 같은 이름의 필드가 한 구역에 둘 있으면 한정자를 붙여도 두 머리가 글자까지
 * 같아져 열이 하나만 선다([EntityFieldHeaders.plan]).
 *
 * **그래서 이 시트는 보통 비어 있고, 비면 만들지 않는다.** 그것이 정상이며 결함이 아니다 —
 * 이 시트의 값어치는 *채워질 때*가 아니라 **어떤 값도 조용히 사라지지 않는다는 보장**에 있다.
 */
object NovelFieldValueOverflow {

    fun select(
        values: List<NovelFieldValue>,
        coveredFieldIds: Set<Long>,
        fieldsById: Map<Long, FieldDefinition>
    ): List<Pair<NovelFieldValue, FieldDefinition>> =
        FieldValueOverflow.select(values, coveredFieldIds, fieldsById, { it.fieldDefinitionId }, { it.value })
}

/** '사건 필드값' 오버플로 시트의 선별 (B-65) — 근거는 [NovelFieldValueOverflow]와 같다. */
object EventFieldValueOverflow {

    fun select(
        values: List<EventFieldValue>,
        coveredFieldIds: Set<Long>,
        fieldsById: Map<Long, FieldDefinition>
    ): List<Pair<EventFieldValue, FieldDefinition>> =
        FieldValueOverflow.select(values, coveredFieldIds, fieldsById, { it.fieldDefinitionId }, { it.value })
}
