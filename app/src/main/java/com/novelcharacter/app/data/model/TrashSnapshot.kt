package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 휴지통 스냅샷 (B-7 → B-1).
 *
 * 삭제 시점에 엔티티와 그 연관 데이터 전체를 JSON으로 직렬화해 보관한다.
 * FK가 없는 독립 테이블이므로 원본 삭제(CASCADE)와 무관하게 살아남고,
 * 소프트 삭제(deletedAt 컬럼)와 달리 기존 조회 쿼리에 영향을 주지 않는다.
 *
 * 이미지 파일은 스냅샷이 살아 있는 동안 디스크에 유지되며,
 * 스냅샷 영구 삭제/정리 시점에 함께 삭제된다.
 *
 * ## 작업 단위 (B-1 / B-14)
 * 한 번의 삭제가 만드는 스냅샷은 하나가 아니다 — 세계관 하나를 지우면 캐릭터 수백 개,
 * 세력·사건·작품 스냅샷이 **함께** 생긴다. 이것을 개별 항목으로 다루면 보관 한도가
 * 그 묶음을 중간에서 잘라 "세계관은 되살아나는데 캐릭터 71명은 없는" 반쪽 백업이 된다.
 * 그래서 [operationId]로 묶고 **정리도 복원도 작업 단위**로 한다. 한도 [MAX_OPERATIONS]는
 * 항목 수가 아니라 **작업 수**의 상한이며, 한 작업은 통째로 남거나 통째로 사라진다.
 *
 * 구버전 행은 operationId가 null이다 — 그때는 행 하나가 곧 하나의 작업이며(`row:<id>`),
 * 종전과 동일하게 동작한다.
 */
@Entity(
    tableName = "trash_snapshots",
    indices = [Index("deletedAt"), Index("operationId")]
)
data class TrashSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** [TYPE_CHARACTER] 등 — 어떤 엔티티의 스냅샷인가 */
    val entityType: String,
    val entityName: String,          // 목록 표시용 이름
    val payload: String,             // 엔티티+연관 데이터 JSON (CharacterSnapshot 등)
    val imagePaths: String = "[]",   // 보류 중인 이미지 파일 경로 JSON 배열
    val deletedAt: Long = System.currentTimeMillis(),
    /**
     * 이 스냅샷을 만든 **삭제 작업**의 식별자. 같은 삭제가 만든 스냅샷은 같은 값을 갖는다.
     * 구버전 행은 null이며 그때는 행 하나가 곧 하나의 작업이다([operationKey]).
     *
     * 표시용 이름을 함께 저장하지 않는 이유: 그룹 머리글은 **묶음의 뿌리 항목**
     * (=[restorePriority]가 가장 낮은 항목)의 타입·이름과 개수로 온전히 만들 수 있다.
     * 문구를 컬럼에 굳혀 두면 strings.xml과 어긋나고, 저장소 계층이 사용자 문구를 갖게 된다.
     */
    val operationId: String? = null,
    /**
     * 이 작업이 **삭제**인가 **파괴적 편집 직전 백업**인가 ([KIND_DELETE]/[KIND_EDIT_BACKUP]).
     *
     * 둘을 구분하지 않으면 편집 백업 묶음이 "…삭제 · 항목 12개" 머리글과 '전체 복원' 버튼을
     * 달고 나타난다. 그 캐릭터들은 지워진 적이 없으므로 복원은 되돌리기가 아니라 **복제**이고,
     * 안내 문구도 거짓이 된다. 구버전 행은 null이며 그때는 종전대로 삭제로 본다
     * (실제로 v43 이전에는 편집 백업도 개별 행이라 묶음이 만들어지지 않았다).
     */
    val operationKind: String? = null
) {
    /**
     * 정리·복원이 묶음으로 다루는 키. operationId가 없는 구버전 행은 자기 자신만의 작업이 된다.
     * **SQL 쪽 표현(`COALESCE(operationId, 'row:' || id)`)과 반드시 같은 문자열이어야 한다.**
     */
    val operationKey: String get() = operationId ?: legacyOperationKey(id)

    /** 편집 직전 백업 묶음인가 — 묶음 머리글·전체 복원을 내주면 안 되는 종류다. */
    val isEditBackup: Boolean get() = operationKind == KIND_EDIT_BACKUP

    companion object {
        const val TYPE_CHARACTER = "character"
        const val TYPE_UNIVERSE = "universe"

        /**
         * 세계관 스냅샷의 이어붙임 행 — 값 라이브러리·고아 필드값처럼 **크기가 자라는** 부분.
         * 한 행에 몰아넣으면 payload가 CursorWindow 한도를 넘겨 백업을 읽을 수 없게 된다.
         */
        const val TYPE_UNIVERSE_DATA = "universe_data"
        const val TYPE_NOVEL = "novel"
        const val TYPE_FACTION = "faction"
        const val TYPE_EVENT = "event"

        /** 삭제 백업 — 복원 = 부활. */
        const val KIND_DELETE = "delete"

        /** 파괴적 편집 직전 백업 — 원본이 살아 있으므로 복원 = 복제(B-2). */
        const val KIND_EDIT_BACKUP = "edit_backup"

        /** 구버전 행 작업 키의 접두사. SQL의 `'row:' || id`와 같은 형식이다. */
        const val LEGACY_KEY_PREFIX = "row:"

        /** 구버전(작업 식별자 없는) 행의 작업 키. */
        fun legacyOperationKey(id: Long): String = LEGACY_KEY_PREFIX + id

        /**
         * 휴지통 최대 보관 **작업** 수 — 초과 시 오래된 작업부터 통째로 영구 삭제.
         *
         * 항목 수가 아니라 작업 수인 이유는 B-14다. 항목 상한이면 캐릭터 100명짜리 세계관을
         * 지운 직후 아무 캐릭터나 하나 더 지우는 것만으로 그 백업이 71건 소각됐다.
         */
        const val MAX_OPERATIONS = 30

        /** 보관 기한 (30일) — 초과 시 자동 영구 삭제 */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * 복원 순서 — **낮을수록 먼저**. 하위 엔티티는 상위가 살아 있어야 붙을 자리가 있다.
         *
         * 세계관 → 세계관 부가 데이터 → 작품 → 세력 → 사건 → 캐릭터.
     * 세력은 세계관 없이 존재할 수 없고(NOT NULL),
         * 캐릭터는 작품·세력·사건 전부를 참조하므로 마지막이다. 이 순서를 지키면 한 작업을
         * 통째로 복원할 때 참조가 코드로 다시 이어진다(R-1).
         *
         * entityType에서 파생한다 — 컬럼으로 저장하면 타입과 어긋날 수 있고, 어긋나면
         * 복원 순서가 조용히 틀어져 참조가 유실된다.
         */
        fun restorePriority(entityType: String): Int = when (entityType) {
            TYPE_UNIVERSE -> 0
            // 세계관 본체가 필드 정의를 만든 **뒤에** 그 정의를 가리키는 값들이 붙는다.
            TYPE_UNIVERSE_DATA -> 1
            TYPE_NOVEL -> 2
            TYPE_FACTION -> 3
            TYPE_EVENT -> 4
            TYPE_CHARACTER -> 5
            else -> 6
        }
    }
}
