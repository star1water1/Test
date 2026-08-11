package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.FieldDefRef

/**
 * 해석 집계 — 코드로 다시 찾은 건수와 '근거가 id뿐인 해석'이 있었는지를 모은다.
 *
 * [pendingCodes]는 같은 작업으로 **함께 복원될 예정**인 대상의 **보류 키**다. 지금 DB에 없어도
 * 복원 순서상 그때는 존재하므로 유실로 세지 않는다 — 이것이 없으면 작업 전체 미리보기가
 * "세력을 되살릴 수 없다"처럼 사실과 다른 경고를 낸다.
 *
 * **이름과 달리 코드만 담기지 않는다** — [pendingKeyOf]가 만든 키가 담긴다(코드가 있으면
 * 코드, 코드로 가리킬 수 없는 참조는 `(타입, 옛 id)`). 이름은 코드뿐이던 시절 것이고,
 * 그 이름을 믿고 **코드만 심으면 구버전 참조자가 보류를 못 받아 거짓 경고가 남는다**(B-25).
 * 채우는 쪽은 `TrashRepository.collectOperationCodes` 하나다.
 */
class RestoreTally(legacy: Boolean, private val pendingCodes: Set<String>) {
    var relinked = 0
        private set
    var legacyGuess = legacy
        private set

    /**
     * 이 해석에 [PENDING_ID] 자리표시자가 섞였는가 — 섞였다면 그 계획은 **미리보기 전용**이며
     * 실제 쓰기에 쓰면 존재하지 않는 id(0)를 참조로 박는다. 계획이 이 값을 들고 다니고
     * apply 쪽이 거부한다(주석으로만 약속하면 언젠가 깨진다).
     */
    var previewOnly = false
        private set

    fun note(res: SnapshotRefResolver.Resolution, pendingKey: String?): SnapshotRefResolver.Resolution {
        if (res.origin == SnapshotRefResolver.Origin.CODE) relinked++
        if (res.isLegacyGuess) legacyGuess = true
        if (!res.found && pendingKey != null && pendingKey in pendingCodes) {
            // 같은 작업이 곧 되살릴 대상이다. id는 아직 모르므로 **미리보기에서만** 의미가 있고,
            // 실제 복원은 순서 덕분에 진짜 id를 얻는다. 0은 "유실 아님"의 표식이다.
            previewOnly = true
            return SnapshotRefResolver.Resolution(PENDING_ID, SnapshotRefResolver.Origin.CODE)
        }
        return res
    }

    /**
     * 필드 정의 해석의 [note] — **자연키의 세계관 코드를 보류 판정에 쓴다.**
     *
     * 필드 정의에는 code가 없어 오래도록 `note(res, null)`로 불렀는데, 그러면 보류 판정
     * (`code in pendingCodes`)이 **구조적으로 성립할 수 없다.** 세계관을 지우면 그 필드
     * 정의도 함께 사라지므로, '전체 복원' 미리보기(세계관이 아직 안 살아난 시점)에서는
     * 캐릭터·사건의 필드값과 값 라이브러리 엔트리가 **전부** "필드 정의를 찾을 수 없음"으로
     * 집계됐다 — 실제 복원은 세계관을 먼저 되살리므로 하나도 잃지 않는데도.
     * 필드값이 많은 세계관일수록 예고 숫자가 커지는, 규모에 비례하는 거짓 경고였다
     * (7장: 사실과 다른 경고는 무음보다 나쁘다).
     *
     * 세계관이 같은 작업으로 되살아나면 그 payload가 필드 정의 전량을 담고 있으므로
     * (`UniverseSnapshot.fieldDefinitions`) 정의도 함께 돌아온다. 그래도 예측은 예측이라,
     * 실제 결과가 예고를 넘으면 사후 고지가 잡는다([RestoreLossCounts.exceeds]).
     *
     * **보류는 '세계관 코드가 있는가'가 아니라 '자연키가 성립하는가'에 걸린다** (B-154).
     * 종전에는 `ref.universeCode`만 보고 보류를 줬는데, `entityType`·`key`가 비어 자연키가
     * 서지 않는 ref도 그 세계관이 같은 작업에 있으면 [PENDING_ID]를 받아 유실 집계에서 빠졌다.
     * 정작 실제 복원에서는 [SnapshotRefResolver.naturalKeyOf]가 그 ref를 떨어뜨리고
     * `buildFieldDefIndex`의 refs 루프도 건너뛰므로 — 세계관이 살아나 옛 id가 죽은 뒤에는
     * `LEGACY_MISSING`으로 **값이 버려진다.** 미리보기는 '유실 없음', 복원은 유실이었다.
     * 해석이 근거로 쓰지 못하는 것을 예고가 근거로 써서는 안 되므로 **판정을 해석과 같은
     * 한 줄에 건다** — 그 한 줄이 [SnapshotRefResolver.naturalKeyOf]다.
     *
     * 전역 구역 필드(자연키는 서고 세계관 코드는 null — B-119 확장)는 보류를 받지 않는다.
     * 받을 이유도 없다: 세계관 payload가 되살리는 대상이 아니다.
     */
    fun noteFieldDef(res: SnapshotRefResolver.Resolution, ref: FieldDefRef?): SnapshotRefResolver.Resolution =
        note(res, ref?.let { SnapshotRefResolver.naturalKeyOf(it) }?.universeCode)

    companion object {
        /** 미리보기 전용 — "곧 존재하게 될 대상"의 자리표시자. 실제 쓰기에는 도달하지 않는다. */
        const val PENDING_ID = 0L

        /**
         * 코드가 없는 참조의 보류 키 앞머리. 엔티티 코드는 `generateEntityCode`가 내는
         * 16자리 hex라 `#`도 `:`도 담지 못하므로 진짜 코드와 절대 겹치지 않는다.
         */
        private const val LEGACY_KEY_PREFIX = "#legacy:"

        /**
         * 이 참조의 **보류 키** — "같은 작업이 곧 되살릴 대상인가"를 묻는 식별자다 (B-25).
         *
         * 코드가 있으면 코드가 곧 키다. 코드가 없는 구버전 참조(v35 이전 사건의
         * `TimelineEvent.code`가 그 자리다)는 근거가 옛 id뿐인데, **그 id도 식별자다** —
         * 복원이 비어 있는 옛 id를 그대로 되살리기 때문이다(`apply*`의 `getXById(id) == null`
         * 갈래). 그래서 `(타입, 옛 id)`를 키로 승격한다.
         *
         * 이것이 없으면 묶음 미리보기가 **거짓 예고**를 낸다 — 같은 작업으로 지워진 code 없는
         * 사건을 참조하는 캐릭터가 "사건을 찾을 수 없음"으로 집계되는데, 실제 복원은
         * 사건이 먼저 살아나(restorePriority 6 < 7) id 폴백으로 그대로 이어진다.
         *
         * **키를 만드는 쪽과 심는 쪽이 같은 함수를 써야 한다** — 심는 쪽
         * (`TrashRepository.collectOperationCodes`)이 **id를 보존하는 타입에만** 이 키를
         * 심으므로, 문자열 모양이 갈리면 그 제한이 조용히 무너진다.
         */
        fun pendingKeyOf(entityType: String, oldId: Long, code: String?): String =
            code?.takeIf { it.isNotBlank() } ?: "$LEGACY_KEY_PREFIX$entityType:$oldId"
    }
}
