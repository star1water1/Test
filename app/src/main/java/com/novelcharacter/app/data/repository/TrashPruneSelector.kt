package com.novelcharacter.app.data.repository

/**
 * 휴지통 보관 한도 정리의 **선택 로직** — 순수 함수 (N1).
 *
 * 정리는 "방금 만든 백업을 태우지 않는다"는 규약(4-6 / R-3)을 지켜야 하는데, 그 판정이
 * DAO 호출 사이에 흩어져 있으면 실행 검증이 불가능하다. 선택만 떼어내 고정한다.
 *
 * 규칙: 오래된 순으로 훑되 **보호 대상은 건너뛰고**, 초과분만큼만 지운다.
 * 보호 대상이 오래된 쪽에 섞여 있어도 초과분을 채우려면 그만큼 더 읽어야 하므로,
 * 조회 한도는 `초과분 + 보호 수`다([fetchLimit]).
 */
object TrashPruneSelector {

    /**
     * 오래된 순 조회에 쓸 한도.
     *
     * 보호 대상이 전부 가장 오래된 쪽에 몰려 있는 최악의 경우에도 비보호 후보를
     * [overflow]개 확보할 수 있어야 한다.
     */
    fun fetchLimit(overflow: Int, protectedCount: Int): Int = overflow + protectedCount

    /**
     * 실제로 지울 대상.
     *
     * @param oldestFirst 오래된 순(deletedAt ASC)으로 조회한 후보 id
     * @param protectedIds 이 작업이 방금 만든 스냅샷 — 절대 지우지 않는다
     * @param overflow 한도 초과 개수. 0 이하면 아무것도 지우지 않는다.
     */
    fun selectOverflow(
        oldestFirst: List<Long>,
        protectedIds: Set<Long>,
        overflow: Int
    ): List<Long> {
        if (overflow <= 0) return emptyList()
        return oldestFirst.asSequence()
            .filter { it !in protectedIds }
            .take(overflow)
            .toList()
    }
}
