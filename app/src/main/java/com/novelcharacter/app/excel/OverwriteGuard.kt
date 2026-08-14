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
 * 그래서 판정을 **"내용이 있는 데이터 행이 1개 이상인가"**로 내린다(사용자 판정 2026.08.02 —
 * 선택지 ①: *시트만 만들고 삭제 판정은 유지*). 결과적으로 **덮어쓰기가 지우는 범위는 B-88 전과 같다.**
 *
 * > **판정 입력이 물리 행 번호(lastRowNum)가 아닌 이유:** 내보내기는 데이터 행의 읽기 전용
 * > 칸에 스타일을 입혀 내보낸다. 엑셀에서 행의 **내용만 지우면(Delete)** 스타일만 남은 행이
 * > 물리 행으로 잔존해 lastRowNum이 그대로다 — 행 번호 기준 판정은 그런 '사실상 빈 시트'를
 * > HAS_ROWS로 오판해 빈 시트 가드가 뚫린다. 그래서 **셀 내용을 실제로 본다**([hasDataRow]).
 *
 * > **이 판정과 내보내기의 빈 시트 생성은 함께 움직여야 한다.**
 * > 한쪽만 바꾸면 빈 시트가 삭제 지시가 되거나(가드만 옛것), 엑셀 편집 경로가 안 열린다.
 */
enum class RestoreSource {
    /** 시트 자체가 없다 — 사용자가 할 일은 '다시 내보내기'다. */
    MISSING,

    /** 시트는 있으나 내용 있는 데이터 행이 없다 — 사용자가 할 일은 '그 시트에 행을 적기'다. */
    EMPTY,

    /** 내용 있는 데이터 행이 있다 — 이것만이 복원 재료다. */
    HAS_ROWS
}

object OverwriteGuard {

    /**
     * @param hasDataRow 시트에 **내용이 있는** 데이터 행이 1개 이상인가([hasDataRow]로 판정).
     *   시트가 없으면 null.
     */
    fun classify(hasDataRow: Boolean?): RestoreSource = when (hasDataRow) {
        null -> RestoreSource.MISSING
        false -> RestoreSource.EMPTY
        true -> RestoreSource.HAS_ROWS
    }

    /** 이 시트를 근거로 기존 데이터를 지워도 되는가. */
    fun canRestore(hasDataRow: Boolean?): Boolean = classify(hasDataRow) == RestoreSource.HAS_ROWS

    /**
     * '내용 있는 데이터 행'이 있는가 — **첫 발견 즉시 멈춘다.**
     * [rowTexts]는 데이터 행(헤더 제외)마다 셀 텍스트를 내는 lazy Sequence여야 한다 —
     * 그래야 행이 많은 정상 파일에서 첫 행 첫 셀만 보고 끝난다.
     */
    fun hasDataRow(rowTexts: Sequence<Sequence<String>>): Boolean =
        rowTexts.any { row -> row.any { it.isNotBlank() } }
}
