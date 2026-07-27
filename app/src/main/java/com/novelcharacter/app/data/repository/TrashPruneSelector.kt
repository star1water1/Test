package com.novelcharacter.app.data.repository

/**
 * 휴지통 보관 한도 정리의 **선택 로직** — 순수 함수 (N1 → B-1/B-14).
 *
 * 정리는 두 규약을 동시에 지켜야 한다:
 * - **R-3**: 이 작업이 방금 만든 백업을 태우지 않는다.
 * - **B-14**: 한 삭제 작업이 만든 백업은 통째로 남거나 통째로 사라진다.
 *   반쪽으로 남으면 "세계관은 되살아나는데 캐릭터 71명은 없는" 백업이 되는데,
 *   그것은 복원이 아니라 조용한 유실이다.
 *
 * 그래서 선택의 단위가 **행이 아니라 작업**이다. 판정이 DAO 호출 사이에 흩어져 있으면
 * 실행 검증이 불가능하므로 선택만 떼어내 고정한다.
 */
object TrashPruneSelector {

    /**
     * 정리 후보 — (작업, 종류) 하나.
     * @param editBackup 편집 직전 백업 갈래인가 — purge가 종류까지 맞는 행만 지우므로(R-12)
     *   정리 계획도 같은 축으로 세워야 한다. 종류를 모르면 순수 편집 백업 작업이
     *   "뽑히고도 0행 삭제"로 영원히 남는다(S-4).
     */
    data class Operation(
        val key: String,
        val newestAt: Long,
        val itemCount: Int,
        val editBackup: Boolean = false
    )

    /** 정리 실행 대상 — [purge 시 editBackup 인자까지 함께 전달할 것]. */
    data class PurgeTarget(val key: String, val editBackup: Boolean)

    /**
     * 기한·개수 정리 계획 — **종류별로 독립한 풀**.
     *
     * 삭제 백업과 편집 백업을 한 풀에 섞으면 어느 한쪽의 폭주가 다른 쪽의 복구 경로를
     * 밀어낸다(편집 백업 300건이 사용자가 지운 것의 백업 30건을 전멸시키는 식).
     * 표시([TrashGrouping])·영구 삭제(purgeOperation)와 같은 (작업, 종류) 축을 쓴다.
     *
     * @param maxOperationsPerKind 종류별 보관 한도 (작업 수)
     */
    fun plan(
        operations: List<Operation>,
        threshold: Long,
        protectedKeys: Set<String>,
        maxOperationsPerKind: Int
    ): List<PurgeTarget> {
        val out = mutableListOf<PurgeTarget>()
        for (edit in listOf(false, true)) {
            val ofKind = operations.filter { it.editBackup == edit }
            if (ofKind.isEmpty()) continue
            val expired = selectExpired(ofKind, threshold, protectedKeys).toSet()
            out.addAll(expired.map { PurgeTarget(it, edit) })
            val remaining = ofKind.filter { it.key !in expired }
            out.addAll(selectOverflow(remaining, protectedKeys, maxOperationsPerKind).map { PurgeTarget(it, edit) })
        }
        return out
    }

    /**
     * 기한 초과로 지울 작업.
     *
     * 작업의 시각은 **가장 최근** 스냅샷 기준이다 — 오래 걸린 대량 삭제의 첫 스냅샷만 보고
     * 판정하면 만들어지자마자 절반이 기한 초과가 된다.
     *
     * @param protectedKeys 이 작업 인스턴스가 방금 만든 작업 — 절대 지우지 않는다
     */
    fun selectExpired(
        operations: List<Operation>,
        threshold: Long,
        protectedKeys: Set<String>
    ): List<String> = operations
        .filter { it.key !in protectedKeys && it.newestAt < threshold }
        .map { it.key }

    /**
     * 개수 초과로 지울 작업 — 오래된 순으로 훑되 **보호 대상은 건너뛴다**.
     *
     * 한도는 **작업 수**의 상한이다(항목 수가 아니다 — B-14). 보호된 작업도 한도 계산에는
     * 들어가므로, 보호 때문에 지우지 못한 초과분은 *다음* 삭제의 정리에서 소진된다.
     *
     * @param oldestFirst 오래된 작업부터(newestAt ASC) 정렬된 목록. [selectExpired]로 이미
     *   지운 작업은 제외하고 넘길 것 — 넣어도 결과는 같지만 한도 계산이 실제보다 빡빡해진다.
     * @param maxOperations 보관 한도. 0 이하면 보호되지 않은 모든 작업이 대상이다.
     */
    fun selectOverflow(
        oldestFirst: List<Operation>,
        protectedKeys: Set<String>,
        maxOperations: Int
    ): List<String> {
        val overflow = oldestFirst.size - maxOperations
        if (overflow <= 0) return emptyList()
        return oldestFirst.asSequence()
            .map { it.key }
            .filter { it !in protectedKeys }
            .take(overflow)
            .toList()
    }
}
