package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.CharacterFieldValue

/**
 * 편집 폼이 제출한 필드값과 DB에 이미 있는 값을 합치는 규칙 (N2) — 순수 로직.
 *
 * ## 왜 필요한가
 * `replaceAllByCharacter`는 **"폼이 그 캐릭터 필드값의 전체 진실"**이라는 가정 위에 서 있었다.
 * 그런데 폼은 언제나 *현재 선택된 작품의 세계관에 속한 캐릭터 필드*만 렌더한다. 그래서
 * 작품을 '없음'으로 바꾸면(폼이 필드를 0개 렌더) 저장이 필드값을 **전량 무음 삭제**했다 —
 * 같은 조작을 일괄 편집으로 하면 전량 보존되는데 결과가 정반대였다.
 *
 * ## 규칙
 * 폼의 권한은 **폼이 실제로 렌더한 필드 정의 집합**까지다.
 *
 * - 커버된 필드: 폼이 진실이다. 폼에 없으면(사용자가 비웠으면) 삭제한다.
 * - 커버되지 않은 필드: 폼이 판단할 근거가 없으므로 **기존 값을 그대로 둔다.**
 *
 * 이 규칙 하나로 아래가 모두 덮인다.
 * - 작품 → '없음' / 세계관 없는 작품으로 이동 (폼 커버 = 공집합 → 전량 보존)
 * - 세계관을 옮기지 않는 평범한 저장에서 사건(entityType=event) 필드정의를 가리키는 값
 * - 폼이 렌더하지 못한 어떤 잔여 값이든
 *
 * 보존된 값은 FK상 완전히 유효하다 — 작품 변경은 field_definitions를 건드리지 않으므로
 * 정의는 그대로 살아 있고, 캐릭터를 그 세계관 작품에 되돌리면 값이 다시 보인다.
 * 엑셀 '캐릭터 필드값' 시트가 담는 상태가 정확히 이것이다([800]).
 */
object CharacterFieldValueMerge {

    /**
     * @param formValues  폼이 제출한 값 (빈 값은 이미 제외되어 들어온다 = "비움 의도")
     * @param coveredFieldDefinitionIds 폼이 실제로 렌더한 필드 정의 id 집합
     * @param existingValues DB에 저장돼 있던 값
     * @return `replaceAllByCharacter`에 넘길 최종 집합
     */
    fun merge(
        formValues: List<CharacterFieldValue>,
        coveredFieldDefinitionIds: Set<Long>,
        existingValues: List<CharacterFieldValue>
    ): List<CharacterFieldValue> {
        val result = ArrayList<CharacterFieldValue>(formValues.size + existingValues.size)
        val taken = HashSet<Long>(formValues.size + existingValues.size)
        for (v in formValues) {
            if (!taken.add(v.fieldDefinitionId)) continue   // (캐릭터, 필드정의) 유니크 방어
            result.add(v)
        }
        for (v in existingValues) {
            // 커버된 필드인데 폼에 없다 = 사용자가 비웠다 → 보존하지 않는다.
            if (v.fieldDefinitionId in coveredFieldDefinitionIds) continue
            if (!taken.add(v.fieldDefinitionId)) continue
            // id는 새로 발급받게 둔다 — 호출부가 전량 삭제 후 재삽입하는 구조다.
            result.add(v.copy(id = 0))
        }
        return result
    }

    /**
     * 폼이 커버하지 않아 **보존되는** 값의 개수. 사용자에게 "지금 화면에 보이지 않지만
     * 남아 있는 값"을 알리는 데 쓴다 — 유실은 막았으니 이제 '존재를 알 수 없는 데이터'가
     * 되지 않게 해야 한다(원칙 04).
     */
    fun preservedCount(
        formValues: List<CharacterFieldValue>,
        coveredFieldDefinitionIds: Set<Long>,
        existingValues: List<CharacterFieldValue>
    ): Int {
        val formIds = formValues.mapTo(HashSet()) { it.fieldDefinitionId }
        return existingValues.count {
            it.fieldDefinitionId !in coveredFieldDefinitionIds && it.fieldDefinitionId !in formIds
        }
    }
}
