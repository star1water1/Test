package com.novelcharacter.app.excel

/**
 * 덮어쓰기 가져오기가 **무엇을 지워도 되는가**의 판정 — 순수 계층(B-88).
 *
 * 대원칙은 종전 그대로다: **백업이 복원할 수 없는 것은 지우지 않는다.**
 * 지우기는 휴지통을 거치지 않는 `deleteAll`이라 되돌릴 방법이 전혀 없다.
 *
 * **바뀐 것은 '복원할 수 있다'의 정의다.** 종전에는 *시트가 있는가*였는데,
 * 내보내기가 **빈 범주에도 시트를 만들게 되면서**(B-88 — 그래야 엑셀에서 새 종류를 적어
 * 넣을 수 있다) 그 정의로는 **헤더만 있는 빈 시트가 곧 '전부 지워라'가 된다.**
 * 그러면 엑셀에서 행을 실수로 다 지운 파일 하나가 그 종류를 통째로 없앤다.
 *
 * 그래서 판정을 **"데이터 행이 1개 이상인가"**로 내린다(사용자 판정 2026.08.02 — 선택지 ①:
 * *시트만 만들고 삭제 판정은 유지*). 결과적으로 **덮어쓰기가 지우는 범위는 B-88 전과 같다.**
 *
 * > **이 판정과 내보내기의 빈 시트 생성은 함께 움직여야 한다.**
 * > 한쪽만 바꾸면 빈 시트가 삭제 지시가 되거나(가드만 옛것), 엑셀 편집 경로가 안 열린다.
 */
enum class RestoreSource {
    /** 시트 자체가 없다 — 사용자가 할 일은 '다시 내보내기'다. */
    MISSING,

    /** 시트는 있으나 헤더뿐이다 — 사용자가 할 일은 '그 시트에 행을 적기'다. */
    EMPTY,

    /** 데이터 행이 있다 — 이것만이 복원 재료다. */
    HAS_ROWS
}

object OverwriteGuard {

    /**
     * @param lastRowNum 시트의 마지막 행 0-기반 인덱스(`ImportSheet.lastRowNum`·POI와 같은 계약).
     *   헤더가 0행이므로 이 값이 곧 **데이터 행 수**다 — 행이 없거나 헤더뿐이면 0이다.
     *   시트가 없으면 null.
     */
    fun classify(lastRowNum: Int?): RestoreSource = when {
        lastRowNum == null -> RestoreSource.MISSING
        lastRowNum >= 1 -> RestoreSource.HAS_ROWS
        else -> RestoreSource.EMPTY
    }

    /** 이 시트를 근거로 기존 데이터를 지워도 되는가. */
    fun canRestore(lastRowNum: Int?): Boolean = classify(lastRowNum) == RestoreSource.HAS_ROWS
}
