package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.dao.NovelFieldValueDao
import com.novelcharacter.app.data.model.NovelFieldValue

/**
 * 작품 편집 폼이 제출한 필드값과 DB에 이미 있는 값을 합치는 규칙 (확-3) — 순수 로직.
 * [EventFieldValueMerge](S-6) · [CharacterFieldValueMerge](N2)의 작품판이며 **규칙이 같다**.
 *
 * ## 왜 커버 집합인가 (R-5)
 * 작품 편집 폼도 *그 작품의 세계관에 속한 작품 필드*만, 그것도 **비동기 로딩이 끝난 뒤에만**
 * 렌더한다. 세계관이 없는 작품을 열어 제목만 고치고 저장하거나, 필드 로딩 전에 저장하면
 * 폼을 '전체 진실'로 다루는 순간 기존 값이 전량 무음 삭제된다 — 사건판이 S-6에서 겪은 그대로다.
 *
 * - 커버된 필드: 폼이 진실이다. 폼에 없으면(사용자가 비웠으면) 삭제한다.
 * - 커버되지 않은 필드: 폼이 판단할 근거가 없으므로 **기존 값을 그대로 둔다.**
 * - 커버 밖 필드값을 폼이 제출하면 그 값이 기존 값을 대체한다(캐릭터·사건판과 같은 방어 계약).
 *
 * 커버 집합이 공집합이면(세계관 미해결·필드 섹션 로딩 미완) 전량 보존이다.
 */
object NovelFieldValueMerge {

    /**
     * 폼 제출 한 벌: 값과 커버 집합은 반드시 함께 다닌다.
     *
     * @param values 폼이 제출한 값 (빈 값은 이미 제외되어 들어온다 = "비움 의도")
     * @param coveredFieldDefinitionIds 폼이 실제로 렌더한 필드 정의 id 집합.
     *   렌더 대상이 아닌 CALCULATED 작품 필드도 **조회된 정의라면 포함한다** —
     *   계산 필드 정의를 가리키는 잔여 저장 행이 있으면 저장 시 함께 정리되고,
     *   매 저장마다 "보관했습니다"를 반복하는 거짓 고지를 막는다(사건판과 같은 규칙).
     */
    data class Submission(
        val values: List<NovelFieldValue>,
        val coveredFieldDefinitionIds: Set<Long>
    )

    /** 병합 후 DB에 남는 값의 순수 모델 — 보존분(커버 밖, 폼 미제출) + 폼 제출분. */
    fun resultingValues(
        formValues: List<NovelFieldValue>,
        coveredFieldDefinitionIds: Set<Long>,
        existingValues: List<NovelFieldValue>
    ): List<NovelFieldValue> {
        val formIds = formValues.mapTo(HashSet()) { it.fieldDefinitionId }
        val result = ArrayList<NovelFieldValue>(formValues.size + existingValues.size)
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
        formValues: List<NovelFieldValue>,
        coveredFieldDefinitionIds: Set<Long>,
        existingValues: List<NovelFieldValue>
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
        dao: NovelFieldValueDao,
        novelId: Long,
        submission: Submission
    ): Int {
        val existing = dao.getValuesByNovelList(novelId)
        val values = submission.values.map { it.copy(novelId = novelId) }
        dao.replaceForFields(novelId, submission.coveredFieldDefinitionIds.toList(), values)
        return preservedCount(values, submission.coveredFieldDefinitionIds, existing)
    }
}
