package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.FieldDefRef

/**
 * 해석 집계 — 코드로 다시 찾은 건수와 '근거가 id뿐인 해석'이 있었는지를 모은다.
 *
 * [pendingCodes]는 같은 작업으로 **함께 복원될 예정**인 코드다. 지금 DB에 없어도 복원
 * 순서상 그때는 존재하므로 유실로 세지 않는다 — 이것이 없으면 작업 전체 미리보기가
 * "세력을 되살릴 수 없다"처럼 사실과 다른 경고를 낸다.
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

    fun note(res: SnapshotRefResolver.Resolution, code: String?): SnapshotRefResolver.Resolution {
        if (res.origin == SnapshotRefResolver.Origin.CODE) relinked++
        if (res.isLegacyGuess) legacyGuess = true
        if (!res.found && code != null && code in pendingCodes) {
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
     */
    fun noteFieldDef(res: SnapshotRefResolver.Resolution, ref: FieldDefRef?): SnapshotRefResolver.Resolution =
        note(res, ref?.universeCode?.takeIf { it.isNotBlank() })

    companion object {
        /** 미리보기 전용 — "곧 존재하게 될 대상"의 자리표시자. 실제 쓰기에는 도달하지 않는다. */
        const val PENDING_ID = 0L
    }
}
