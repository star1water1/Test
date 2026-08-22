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
 * 그래서 [operationId]로 묶고 **정리도 복원도 작업 단위**로 한다. 보관 한도는
 * 항목 수가 아니라 **작업 수**의 상한이며, 한 작업은 통째로 남거나 통째로 사라진다.
 * (한도 값 자체는 사용자가 정한다 — `TrashRetentionPolicy`. 여기 상수는 그 기본값이다.)
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

        /**
         * 필드 정의 — **두 갈래를 [operationKind]가 가른다.**
         *
         * - [KIND_EDIT_BACKUP] — 덮어쓰기 직전 백업 (B-89). 원본이 살아 있으므로 복원은
         *   부활이 아니라 되돌리기이고, 살아 있는 정의를 덮으므로 동의를 받는다(R-4).
         * - [KIND_DELETE] — 필드 하나를 **지운** 백업. 정의 자체가 사라졌으므로 복원은
         *   부활이고, 그 필드가 들고 있던 값·라이브러리 엔트리·상태변화는 크기가 자라므로
         *   [TYPE_FIELD_DATA] 이어붙임 행이 담는다.
         *
         * 세계관 삭제로 함께 사라지는 필드 정의는 [TYPE_UNIVERSE] 스냅샷이 담는다
         * (스냅샷은 겹치지 않고 이어붙는다 — R-8).
         */
        const val TYPE_FIELD_DEFINITION = "field_definition"

        /**
         * 지워진 필드가 들고 있던 데이터의 이어붙임 행 — 값·값 라이브러리 엔트리·상태변화.
         *
         * 한 행에 몰아넣지 않는 이유는 [TYPE_UNIVERSE_DATA]와 같다: 캐릭터 수백 명의 값과
         * 그 필드의 이력은 프로젝트 규모에 따라 자라고, payload가 CursorWindow 한도(2MB)를
         * 넘기면 **"휴지통에 보관됩니다"라고 안내한 그 백업을 영영 읽지 못한다**(R-10).
         *
         * 자기 정의([TYPE_FIELD_DEFINITION])보다 **나중에** 복원되므로 그때 붙을 정의가
         * 이미 서 있고, 값은 그 정의를 **자연키**로 다시 찾는다(R-1 — 새 id를 받기 때문).
         */
        const val TYPE_FIELD_DATA = "field_data"

        /**
         * 상태변화 이력 한 줄. 캐릭터에 매달리므로 **캐릭터보다 나중에** 복원된다.
         * (캐릭터 삭제·출생사망 사건 삭제로 함께 사라지는 이력은 각각 캐릭터·사건 스냅샷이
         * 담는다 — 이 타입은 이력만 개별로 지운 경로 전용이다.)
         */
        const val TYPE_STATE_CHANGE = "state_change"

        /**
         * 등급 체계 하나 (U-1). **체계만 개별로 지운 경로 전용이다** — 세계관 삭제로 함께
         * 사라지는 체계는 세계관 스냅샷이 담는다(스냅샷은 겹치지 않고 이어붙는다).
         * 삭제 시점의 참조 필드 목록을 함께 담아, 복원이 강등된 필드를 다시 잇는다.
         */
        const val TYPE_GRADE_SYSTEM = "grade_system"

        /**
         * 대결 축 하나 (B-104) — 축 본체와 층 B의 처분을 담는다.
         * **판은 여기 담지 않는다**([TYPE_DUEL_MATCHES]) — 한 축의 판이 수만 건이 될 수 있어
         * 한 행에 몰아넣으면 payload가 CursorWindow 한도를 넘겨 그 백업을 영영 읽지 못한다.
         *
         * 축만 개별로 지운 경로와 세계관 삭제가 **같은 타입을 쓴다** — 어느 쪽이든 축은
         * 통째로 사라지고 담을 것이 같기 때문이다(세계관 스냅샷은 축을 담지 않는다).
         */
        const val TYPE_DUEL_AXIS = "duel_axis"

        /**
         * 대결 판의 이어붙임 행 — 크기 예산 단위로 잘린다([TYPE_UNIVERSE_DATA]와 같은 규약).
         * 자기 축([DuelAxisSnapshot])보다 **나중에** 복원되므로 그때 붙을 축이 이미 있다.
         */
        const val TYPE_DUEL_MATCHES = "duel_matches"

        /** 삭제 백업 — 복원 = 부활. */
        const val KIND_DELETE = "delete"

        /** 파괴적 편집 직전 백업 — 원본이 살아 있으므로 복원 = 복제(B-2). */
        const val KIND_EDIT_BACKUP = "edit_backup"

        /** 구버전 행 작업 키의 접두사. SQL의 `'row:' || id`와 같은 형식이다. */
        const val LEGACY_KEY_PREFIX = "row:"

        /** 구버전(작업 식별자 없는) 행의 작업 키. */
        fun legacyOperationKey(id: Long): String = LEGACY_KEY_PREFIX + id

        /**
         * 휴지통 최대 보관 **작업** 수의 **기본값** — 초과 시 오래된 작업부터 통째로 영구 삭제.
         *
         * 항목 수가 아니라 작업 수인 이유는 B-14다. 항목 상한이면 캐릭터 100명짜리 세계관을
         * 지운 직후 아무 캐릭터나 하나 더 지우는 것만으로 그 백업이 71건 소각됐다.
         *
         * **실제로 쓰이는 값은 사용자 설정이다(B-74)** — 이 상수는 설정을 아직 정하지 않은
         * 사용자의 출발점일 뿐이고, 현행 값은 [TrashRetentionPolicy]가 든다. 정리 코드가 이
         * 상수를 직접 읽으면 설정이 그 경로에만 안 먹으므로 **읽지 말 것.**
         */
        const val DEFAULT_MAX_OPERATIONS = 30

        /** 보관 기한의 **기본값**(일) — 초과 시 자동 영구 삭제. 현행 값은 [TrashRetentionPolicy]. */
        const val DEFAULT_RETENTION_DAYS = 30

        /**
         * 복원 순서 — **낮을수록 먼저**. 하위 엔티티는 상위가 살아 있어야 붙을 자리가 있다.
         *
         * 세계관 → 세계관 부가 데이터 → 작품 → 세력 → 사건 → 캐릭터 → 상태변화.
     * 세력은 세계관 없이 존재할 수 없고(NOT NULL),
         * 캐릭터는 작품·세력·사건 전부를 참조하므로 그다음이며, 상태변화는 캐릭터에 매달리므로
         * 맨 뒤다. 이 순서를 지키면 한 작업을
         * 통째로 복원할 때 참조가 코드로 다시 이어진다(R-1).
         *
         * entityType에서 파생한다 — 컬럼으로 저장하면 타입과 어긋날 수 있고, 어긋나면
         * 복원 순서가 조용히 틀어져 참조가 유실된다.
         */
        fun restorePriority(entityType: String): Int = when (entityType) {
            TYPE_UNIVERSE -> 0
            // 필드 정의는 세계관이 있어야 붙고, 그 정의를 가리키는 값보다 먼저 있어야 한다.
            // (덮어쓰기 되돌리기 전용이라 실제로는 다른 타입과 한 작업에 섞이지 않지만,
            //  순서는 타입이 어디에 속하는지의 선언이므로 제자리에 둔다.)
            TYPE_FIELD_DEFINITION -> 1
            // 세계관 본체가 필드 정의를 만든 **뒤에** 그 정의를 가리키는 값들이 붙는다.
            TYPE_UNIVERSE_DATA -> 2
            // 지워진 필드의 값·엔트리·이력 — 자기 정의(1)가 선 뒤에 붙는다. 세계관 부가
            // 데이터와 번호가 같은 것은 무해하다: 둘은 서로 다른 작업에만 나타난다
            // (필드 삭제는 자기 작업 하나이고, 세계관 삭제는 필드를 통째로 담는다).
            TYPE_FIELD_DATA -> 2
            // 등급 체계는 세계관만 있으면 붙는다. 참조 필드 재연결은 자연키 조회라 순서 무관이나,
            // 다른 하위 엔티티보다 먼저 두어 "정의 계층 → 데이터 계층" 순서를 유지한다.
            TYPE_GRADE_SYSTEM -> 3
            TYPE_NOVEL -> 4
            TYPE_FACTION -> 5
            TYPE_EVENT -> 6
            TYPE_CHARACTER -> 7
            // 상태변화는 주인 캐릭터가 이미 살아 있어야 붙을 자리가 있다.
            TYPE_STATE_CHANGE -> 8
            // 대결(B-104)은 세계관만 있으면 붙는다 — 판이 참가자를 **코드로** 가리키므로
            // 캐릭터가 아직 안 돌아왔어도 유실이 아니다(그때는 고아로 세어 알리고, 캐릭터가
            // 복원되면 그대로 되살아난다). 그래서 맨 뒤에 두어 앞의 번호를 흔들지 않는다.
            TYPE_DUEL_AXIS -> 9
            // 판은 자기 축이 선 뒤에 붙는다.
            TYPE_DUEL_MATCHES -> 10
            else -> 11
        }
    }
}
