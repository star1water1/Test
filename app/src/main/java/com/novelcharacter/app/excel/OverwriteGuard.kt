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

    /**
     * 부모를 통째로 지울 때 **FK CASCADE로 함께 사라지는 자식 하나**(S22 덮어쓰기 가드).
     *
     * 덮어쓰기는 세계관·캐릭터를 `deleteAll`로 비운 뒤 파일로 다시 세운다. 그때 그 아래
     * 표(필드 정의 → 설정·값 라이브러리, 세력·소속, 대결 축·판, 관계·상태변화…)도 DB가
     * 함께 지운다 — **자식 시트를 지키는 가드가 '유지했습니다'라고 말한 뒤에도.**
     * 그래서 부모를 지우기 전에 자식마다 *지금 몇 건 있고 이 파일이 되살릴 수 있는가*를 묻는다.
     *
     * @param label 사용자에게 보일 이름(시트 이름과 같게 — 사용자가 그 시트를 찾아가야 한다)
     * @param existing 지금 DB에 있는 건수(부모를 지우면 사라질 몫)
     * @param source 그 자식의 시트가 복원 재료인가
     * @param missingColumns 시트는 있으나 **지금 값이 든 열**이 파일에 없는 것 — 열이 없으면
     *   다시 세운 행이 그 칸을 빈 값으로 받는다(기존 값으로 메울 행이 이미 지워졌다).
     * @param notSelected 이번 가져오기 항목에서 빠졌다 — 시트가 있어도 읽지 않으므로 복원 재료가 아니다.
     * @param keptOwner 임자(캐릭터·작품·사건)를 **남겨 둔** 값이다 — 부모가 지워지면 값만 사라지고
     *   임자 행은 그대로라, 파일로 다시 세울 자리가 없다. [existing]이 0이 아니면 막는다.
     */
    data class CascadeChild(
        val label: String,
        val existing: Int,
        val source: RestoreSource,
        val missingColumns: List<String> = emptyList(),
        val notSelected: Boolean = false,
        val keptOwner: Boolean = false
    )

    /** 부모 통삭제를 **막는** 자식들 — 지금 데이터가 있는데 이 파일로 되살릴 수 없는 것. */
    fun cascadeBlockers(children: List<CascadeChild>): List<CascadeChild> =
        children.filter { c ->
            c.existing > 0 && (
                c.notSelected || c.keptOwner ||
                    c.source != RestoreSource.HAS_ROWS || c.missingColumns.isNotEmpty()
                )
        }

    /**
     * 막은 사유를 한 줄로 — 무엇이 몇 건이고 **사용자가 무엇을 하면 되는가**가 갈리게 적는다
     * (시트 없음 → 다시 내보내기 · 행 없음 → 그 시트에 행 적기 · 열 없음 → 그 열 되살리기).
     */
    fun describeBlockers(blockers: List<CascadeChild>): String =
        blockers.joinToString(", ") { b ->
            val why = when {
                b.notSelected -> "가져올 항목에서 빠짐"
                b.keptOwner -> "임자를 남겨 둠"
                b.source == RestoreSource.MISSING -> "시트 없음"
                b.source == RestoreSource.EMPTY -> "데이터 행 없음"
                else -> b.missingColumns.joinToString("·") { "'$it'" } + " 열 없음"
            }
            "'${b.label}' ${b.existing}건($why)"
        }
}
