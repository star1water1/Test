package com.novelcharacter.app.util

/**
 * 엑셀 가져오기·복원 미리보기의 **진행 셈** — 화면에 무엇을 얼마로 보일지 정한다.
 *
 * **순수 계층에 둔 이유는 경계가 여기 다 모이기 때문이다.** `ExcelImportService`는 Room에
 * 묶여 순수 하네스가 원리적으로 닿지 못하는데(같은 사유로 `check_preview_row_queries.sh`
 * 계열이 검사로 서 있다), 이 셈의 규칙 셋은 Room과 아무 상관이 없다:
 *
 * 1. **단조 증가** — 막대는 뒤로 가지 않는다. 한 함수가 같은 시트를 두 번 도는 자리가 있어
 *    (2패스) 시트 안 중간 보고의 셈이 0에서 다시 시작하는데, 그때 막대가 뒤로 가면
 *    *틀린 고지*다.
 * 2. **분모를 넘지 않는다** — 분모는 파일의 모든 시트를 센 값이라 넘칠 일이 없어야 하지만,
 *    넘으면 100%를 넘는 막대가 되므로 셈이 그 자리에서 막는다.
 * 3. **끝맺음은 알릴 것이 있을 때만** — 분모는 파일의 **모든** 시트를 세는데 회차는
 *    사용자가 고른 범주만 돈다. 그래서 다 끝나도 막대가 60%에 서 있는 파일이 있었다.
 *    끝난 자리에서 100%로 굳히는 것은 거짓이 아니다(**정말로 끝났다**). 다만 **한 줄도
 *    안 돈 회차는 굳히지 않는다** — 이름표조차 없으면 알릴 구간이 없었다는 뜻이고,
 *    그때 100%를 적으면 *하지 않은 일*을 했다고 말하는 것이 된다.
 *
 * @param totalRows 분모(행). 0 이하로 들어오면 1로 본다 — 0으로 나눌 자리를 만들지 않는다.
 */
class ImportProgressTally(totalRows: Int) {

    val totalRows: Int = if (totalRows > 0) totalRows else 1

    /** 마지막으로 알린 구간 이름. 한 번도 알리지 않았으면 빈 문자열이다. */
    var lastPhase: String = ""
        private set

    /** 마지막으로 알린 행 수. */
    var lastShownRows: Int = 0
        private set

    /**
     * 알릴 행 수를 정하고 기록한다 — 단조 증가하고 분모를 넘지 않는다.
     *
     * [phase]가 비어 있으면 이름표를 갱신하지 않는다(빈 이름으로 덮지 않는다).
     */
    fun show(phase: String, rows: Int): Int {
        val shown = rows.coerceAtLeast(lastShownRows).coerceAtMost(totalRows)
        lastShownRows = shown
        if (phase.isNotBlank()) lastPhase = phase
        return shown
    }

    /**
     * 시트 안에서 [rowsDoneInSheet]번째 행을 지날 때 중간 보고를 낼 차례인가.
     *
     * 주기를 두는 이유는 게시 비용이다 — 행마다 내면 메인 스레드로 던지는 게시가
     * 그 자체로 일이 된다. 첫 행에서는 내지 않는다(직전 시트의 끝맺음과 겹친다).
     */
    fun shouldTick(rowsDoneInSheet: Int): Boolean =
        rowsDoneInSheet > 0 && rowsDoneInSheet % TICK_ROWS == 0

    /**
     * 끝맺음 — 막대를 분모까지 올린다. **알릴 것이 없으면 null이다.**
     */
    fun settle(): Int? {
        if (lastPhase.isBlank()) return null
        return show(lastPhase, totalRows)
    }

    companion object {
        /** 시트 안 진행 보고 주기(행) — 촘촘할수록 막대는 매끄럽지만 게시가 일이 된다 */
        const val TICK_ROWS = 64
    }
}
