package com.novelcharacter.app.ai

/** Presentation only. No proposal, draft, provenance or request revision is changed here. */
object ReviewPresentation {
    data class Viewport(val anchor: String? = null, val offset: Int = 0, val scroll: Int = 0,
        val focus: String? = null, val start: Int = 0, val end: Int = 0,
        val expanded: Set<String> = emptySet())
    data class Progress(val indeterminate: Boolean, val text: String)
    fun progress(done: Int, total: Int) = if (total <= 1)
        Progress(true, "AI 응답 기다리는 중")
    else Progress(false, "${done.coerceIn(0, total)}/$total 요청 완료")
    fun canReset(current: String, latest: String, draft: String?) =
        current != latest || (draft != null && draft != latest)
}
