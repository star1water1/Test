package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.dao.EventFieldValueDao
import com.novelcharacter.app.data.model.EventFieldValue

/**
 * 사건 편집 폼이 제출한 필드값과 DB에 이미 있는 값을 합치는 규칙 (S-6) — 순수 로직.
 * [CharacterFieldValueMerge](N2)의 사건판이다.
 *
 * ## 왜 필요한가
 * 종전 저장 경로(사건 단위 전량 교체 `replaceAllByEvent`, S-6에서 제거)는
 * **"폼이 그 사건 필드값의 전체 진실"**이라는 가정 위에 서 있었다.
 * 그런데 폼은 *선택된 작품의 세계관에 속한 사건 필드*만, 그것도 **비동기 로딩이 끝난 뒤에만**
 * 렌더한다. 그래서 작품 연결이 끊긴 사건을 열어 설명만 고치고 저장하거나, 필드 로딩이
 * 끝나기 전에 저장하면 기존 필드값이 **전량 무음 삭제**됐다.
 *
 * ## 규칙 (R-5: 폼의 권한은 폼이 실제로 렌더한 것까지)
 * - 커버된 필드: 폼이 진실이다. 폼에 없으면(사용자가 비웠으면) 삭제한다.
 * - 커버되지 않은 필드: 폼이 판단할 근거가 없으므로 **기존 값을 그대로 둔다.**
 * - 커버 밖 필드값을 폼이 제출하면 그 값이 기존 값을 대체한다(캐릭터판과 같은 방어 계약 —
 *   폼이 실제로 알고 낸 값을 버리지 않는다).
 *
 * 커버 집합이 공집합이면(세계관 미해결·필드 섹션 로딩 미완·회전 직후) 전량 보존이다.
 *
 * ## 캐릭터판과 다른 점
 * 캐릭터판은 메모리에서 합친 결과를 `replaceAllByCharacter`(전량 삭제+재삽입)에 넘기지만,
 * 사건판은 [EventFieldValueDao.replaceForFields](커버 필드만 삭제) + REPLACE 삽입이 같은
 * 결과를 DB 연산으로 만든다. [resultingValues]가 그 결과의 순수 모델이고,
 * `EventFieldValueDaoReplaceTest`가 DAO 연산과 이 모델의 일치를 고정한다.
 */
object EventFieldValueMerge {

    /**
     * 폼 제출 한 벌: 값과 커버 집합은 반드시 함께 다닌다.
     *
     * @param values 폼이 제출한 값 (빈 값은 이미 제외되어 들어온다 = "비움 의도")
     * @param coveredFieldDefinitionIds 폼이 실제로 렌더한 필드 정의 id 집합.
     *   렌더 대상이 아닌 CALCULATED 사건 필드도 **조회된 정의라면 포함한다** —
     *   계산 필드 정의를 가리키는 잔여 저장 행이 있으면 저장 시 함께 정리되고,
     *   매 저장마다 "보관했습니다"를 반복하는 거짓 고지를 막는다(캐릭터판 폼 빌더와 같은 규칙).
     */
    data class Submission(
        val values: List<EventFieldValue>,
        val coveredFieldDefinitionIds: Set<Long>
    )

    /** 병합 후 DB에 남는 값의 순수 모델 — 보존분(커버 밖, 폼 미제출) + 폼 제출분. */
    fun resultingValues(
        formValues: List<EventFieldValue>,
        coveredFieldDefinitionIds: Set<Long>,
        existingValues: List<EventFieldValue>
    ): List<EventFieldValue> {
        val formIds = formValues.mapTo(HashSet()) { it.fieldDefinitionId }
        val result = ArrayList<EventFieldValue>(formValues.size + existingValues.size)
        for (v in existingValues) {
            if (v.fieldDefinitionId in coveredFieldDefinitionIds) continue  // 폼이 진실 — 폼에 없으면 삭제
            if (v.fieldDefinitionId in formIds) continue                    // 커버 밖 제출값이 대체
            result.add(v)
        }
        result.addAll(formValues)
        return result
    }

    /**
     * 폼이 커버하지 않아 **보존되는** 값의 개수. 유실은 막았으니 이제 '존재를 알 수 없는
     * 데이터'가 되지 않게 사용자에게 알리는 데 쓴다(원칙 04).
     */
    fun preservedCount(
        formValues: List<EventFieldValue>,
        coveredFieldDefinitionIds: Set<Long>,
        existingValues: List<EventFieldValue>
    ): Int {
        val formIds = formValues.mapTo(HashSet()) { it.fieldDefinitionId }
        return existingValues.count {
            it.fieldDefinitionId !in coveredFieldDefinitionIds && it.fieldDefinitionId !in formIds
        }
    }

    /**
     * 폼 제출을 커버 집합 범위로 DB에 반영한다. **트랜잭션 안에서 부를 것** —
     * 보존 개수는 반영 직전의 existing 스냅샷으로 세야 고지 건수가 어긋나지 않는다.
     *
     * @return 커버 밖에 남긴(보존된) 기존 값 개수. 0보다 크면 호출부가 사용자에게 고지한다.
     */
    suspend fun saveWithinCover(
        dao: EventFieldValueDao,
        eventId: Long,
        submission: Submission
    ): Int {
        val existing = dao.getValuesByEventList(eventId)
        val values = submission.values.map { it.copy(eventId = eventId) }
        dao.replaceForFields(eventId, submission.coveredFieldDefinitionIds.toList(), values)
        return preservedCount(values, submission.coveredFieldDefinitionIds, existing)
    }
}
