package com.novelcharacter.app.excel

/**
 * 가져오기 결과의 **오류·경고 상세 본문** — 화면의 '상세 보기' 창과 화면 밖 보관함
 * (`util/TransferNoticeRelay`)이 **같은 함수**로 짓는다.
 *
 * 종전에는 이 본문이 상세 창 안에만 인라인으로 있어, 화면 밖으로 끝난 가져오기는 건수만
 * 남고 상세가 통째로 사라졌다 — 그런데 고지 문구는 *"앱에서 상세를 확인하세요"*라고 적어
 * **없는 것을 보라고 말했다**(거짓 고지). 본문을 순수로 꺼내 보관함에도 실으면서 그 약속이
 * 참이 된다. 두 자리가 각자 지으면 한쪽만 고쳐지는 날이 온다 — 그래서 한 벌이다.
 *
 * 상한(30/20)은 종전 상세 창의 것 그대로다(B-91 — 긴 본문은 스크롤 창이 감싼다).
 */
object TransferResultText {

    /** 이 수 이하면 전부 적는다. */
    const val MAX_DETAIL_ITEMS = 30

    /** [MAX_DETAIL_ITEMS]를 넘으면 이 수까지만 적고 "외 N건"으로 줄인다. */
    const val MAX_SHOWN_ITEMS = 20

    /**
     * 오류는 번호로, 경고는 글머리표로 — 종전 상세 창의 모양 그대로다.
     * 둘 다 없으면 빈 문자열이다(부르는 쪽이 그때는 상세 자체를 싣지 않는다).
     */
    fun detailBody(errors: List<String>, warnings: List<String>): String {
        val sb = StringBuilder()
        if (errors.isNotEmpty()) {
            sb.appendLine("── 오류 (${errors.size}건) ──")
            if (errors.size <= MAX_DETAIL_ITEMS) {
                errors.forEachIndexed { i, err -> sb.appendLine("${i + 1}. $err") }
            } else {
                errors.take(MAX_SHOWN_ITEMS).forEachIndexed { i, err -> sb.appendLine("${i + 1}. $err") }
                sb.appendLine("... 외 ${errors.size - MAX_SHOWN_ITEMS}건")
            }
        }
        if (warnings.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("── 경고 (${warnings.size}건) ──")
            if (warnings.size <= MAX_DETAIL_ITEMS) {
                warnings.forEach { sb.appendLine("• $it") }
            } else {
                warnings.take(MAX_SHOWN_ITEMS).forEach { sb.appendLine("• $it") }
                sb.appendLine("... 외 ${warnings.size - MAX_SHOWN_ITEMS}건")
            }
        }
        return sb.toString()
    }
}
