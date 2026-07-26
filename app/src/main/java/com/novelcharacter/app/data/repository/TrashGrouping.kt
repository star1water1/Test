package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.dao.TrashSnapshotSummary
import com.novelcharacter.app.data.model.TrashSnapshot

/**
 * 휴지통 목록을 **삭제 작업 단위**로 접는 순수 로직 (B-1/B-14).
 *
 * UI가 아니라 여기 있는 이유: 이 정렬이 곧 **복원 순서**다. 그룹의 뿌리 항목이 잘못
 * 뽑히면 머리글이 엉뚱한 이름을 말하고, 항목 순서가 틀리면 '전체 복원'이 하위 엔티티를
 * 먼저 되살려 참조가 유실된다. Fragment 안에 두면 실행 검증이 불가능해 그 두 가지가
 * 조용히 깨진다 — 문구 조립만 UI에 남긴다.
 */
object TrashGrouping {

    /**
     * 한 번의 삭제가 만든 묶음.
     *
     * @param items 복원 순서(세계관 → 작품 → 세력 → 사건 → 캐릭터)대로 정렬돼 있다.
     * @param root 이 삭제의 주어 — 머리글 문구는 여기서 만든다. 복원 순서가 가장 이른 항목이며,
     *   같으면 id가 작은 쪽(먼저 만들어진 쪽)이다.
     */
    data class Group(
        val opKey: String,
        val items: List<TrashSnapshotSummary>,
        val newestAt: Long
    ) {
        val root: TrashSnapshotSummary get() = items.first()
        val size: Int get() = items.size

        /**
         * 파괴적 편집 직전 백업 묶음인가 (B-2). 이 묶음의 원본은 **살아 있다**.
         * 하나라도 편집 백업이면 묶음 전체를 그렇게 다룬다 — 섞이는 경로는 없지만,
         * 섞였을 때 안전한 쪽(복제 위험을 알리는 쪽)으로 기운다.
         */
        val isEditBackup: Boolean get() = items.any { it.isEditBackup }

        /**
         * 묶음 머리글(= '전체 복원'·'묶음 영구 삭제')을 내줄 것인가.
         *
         * 항목이 하나면 같은 내용을 두 줄로 반복하게 되므로 만들지 않는다.
         * **편집 직전 백업도 만들지 않는다** — 지워진 적 없는 항목에 "이 삭제로 지워진 N개를
         * 복원할까요?"라고 물으면 거짓이고, 누르면 원클릭으로 N개가 복제된다.
         * 개별 행은 그대로 남아 항목마다 '편집 직전 백업' 경고를 거쳐 복원할 수 있다.
         */
        val needsHeader: Boolean get() = items.size > 1 && !isEditBackup
    }

    private val ITEM_ORDER = compareBy<TrashSnapshotSummary>(
        { TrashSnapshot.restorePriority(it.entityType) },
        { it.id }
    )

    /**
     * 스냅샷을 작업별로 묶고 **최신 삭제부터** 돌려준다.
     * 구버전 행(operationId 없음)은 자기 자신만의 묶음이 되어 종전과 같은 평평한 목록이 된다.
     */
    fun group(snapshots: List<TrashSnapshotSummary>): List<Group> {
        if (snapshots.isEmpty()) return emptyList()
        return snapshots.groupBy { it.operationKey }
            .map { (key, items) ->
                val sorted = items.sortedWith(ITEM_ORDER)
                Group(opKey = key, items = sorted, newestAt = sorted.maxOf { it.deletedAt })
            }
            // 같은 시각이면 opKey로 갈라 목록 순서가 흔들리지 않게 한다(관찰 갱신마다 순서가
            // 바뀌면 사용자가 누르려던 버튼이 다른 항목의 것으로 바뀐다).
            .sortedWith(compareByDescending<Group> { it.newestAt }.thenBy { it.opKey })
    }
}
