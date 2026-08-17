package com.novelcharacter.app.util

/**
 * `(소유자, 필드) → 값`의 **지금 상태**를 들고 있는 겹 (B-187).
 *
 * 담는 것이 값 문자열뿐이라 캐릭터·작품·사건 셋이 같은 것을 쓴다
 * (행 전체를 들어야 하는 캐릭터 가져오기 쪽은 [CharacterValueLedger]가 그대로 맡는다 —
 * 그쪽은 `update`에 넘길 **행**이 필요하고 이쪽은 *무엇으로 바뀌는가*만 필요하다).
 *
 * ## 두 쪽이 쓴다
 *
 * - **가져오기**(작품·연표의 필드 열): 처분을 [FieldValueCellPlan]으로 정하려면 기존 값이
 *   있어야 하는데, 그 두 자리는 `replaceForFields`로 **묻지 않고 통째로 갈아끼우고** 있었다.
 *   그래서 *비움*이 몇 건인지 아무도 세지 않았고(오버플로 시트 쪽은 세는데), **아무것도
 *   바뀌지 않는 행에서도 삭제+삽입이 그대로 돌았다.**
 * - **복원 미리보기**: 파일이 예고하는 신규·변경·동일·비움을 세려면 같은 겹이 필요하다.
 *   **미리보기는 쓰지 않지만 [put]·[remove]는 부른다** — 같은 파일의 **뒤 행**이 앞 행의
 *   쓰기를 봐야 가져오기와 답이 같기 때문이다(규약 R-33 ⑦ · B-233).
 *
 * ## 왜 소유자 → 필드의 두 겹인가
 *
 * `Pair<Long, Long>` 하나로 두면 조회마다 객체가 하나 뜬다. 이 겹을 묻는 횟수는
 * **필드값 칸 수**이고 목표 규모에서 그것이 캐릭터만 36,510이다
 * (`docs/scalability_performance_2026-07.md` 2장 — 게다가 채움률 21.7%라 그 수는 하한이다).
 * 두 겹이면 바깥 키는 `Long` 하나라 상자 하나로 끝난다.
 */
class FieldValueOverlay private constructor(
    private val byOwner: HashMap<Long, HashMap<Long, String>>,
    /** 실을 때의 행 수 — 미리보기의 '현재 DB' 총계가 이 값이다(같은 읽기가 둘을 먹인다). */
    val loadedRows: Int
) {

    /** `(소유자, 필드)`의 지금 값. 없으면 `null` — **빈 문자열과 다르다**(빈 값이 저장된 것). */
    fun get(ownerId: Long, fieldId: Long): String? = byOwner[ownerId]?.get(fieldId)

    /** 값을 썼다고 기록한다(신규·변경 양쪽). */
    fun put(ownerId: Long, fieldId: Long, value: String) {
        byOwner.getOrPut(ownerId) { HashMap() }[fieldId] = value
    }

    /** 값을 지웠다고 기록한다. */
    fun remove(ownerId: Long, fieldId: Long) {
        byOwner[ownerId]?.remove(fieldId)
    }

    companion object {
        /**
         * 표를 **한 번** 읽어 겹을 짓는다.
         *
         * @param rows 표의 행 전부. 행마다 `(소유자 id, 필드 id, 값)`을 집는 [keyOf]가 붙는다 —
         *   세 표의 열 이름이 서로 달라(`characterId`·`novelId`·`eventId`) 그 자리만 갈린다.
         */
        fun <T> of(rows: List<T>, keyOf: (T) -> Triple<Long, Long, String>): FieldValueOverlay {
            val byOwner = HashMap<Long, HashMap<Long, String>>()
            for (row in rows) {
                val (ownerId, fieldId, value) = keyOf(row)
                byOwner.getOrPut(ownerId) { HashMap() }[fieldId] = value
            }
            return FieldValueOverlay(byOwner, rows.size)
        }
    }
}
