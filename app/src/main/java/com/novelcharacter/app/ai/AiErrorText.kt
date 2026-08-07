package com.novelcharacter.app.ai

/**
 * 오류 안내문의 **조립 규칙** — 순수 판정(JVM 테스트 대상).
 *
 * [AiErrorMessages]에서 갈라 둔 이유: 이 조립이 지키는 약속은 Context가 필요 없는 사실인데,
 * 한 함수에 문자열 조회와 묶여 있으면 **어떤 로컬 검증도 읽지 못한다**(이 저장소의 자동 검증은
 * 화면 계층과 Context 의존 코드를 통째로 뺀다). 규칙을 여기로 빼면 시험이 잠글 수 있다.
 */
object AiErrorText {

    /**
     * 프로바이더 줄 → 분류 문구(+HTTP 코드) → 제공사 원문 순으로 붙인다.
     *
     * **프로바이더 줄이 맨 앞인 것이 B-150의 요점이다.** 프로바이더가 둘 이상일 때 사용자가
     * 먼저 묻는 것은 *무엇이 잘못됐는가*가 아니라 **어느 프로바이더로 나갔는가**다 —
     * 활성이 한도에 걸린 옛 것에 남아 있어도 종전 문구는 글자 그대로 같았고, 그래서
     * 사용자는 새 키가 안 쓰이고 있다는 것을 알아낼 방법이 없었다(2026.08.07 보고의 경로).
     * 맨 뒤에 붙이면 길이가 들쭉날쭉한 제공사 원문에 밀려 사실상 안 보인다.
     *
     * 빈 문자열·공백만 있는 값은 없는 것으로 본다 — 빈 줄만 남기면 문구가 어색해진다.
     */
    fun compose(base: String, httpCode: Int?, detail: String?, providerLine: String?): String {
        val withCode = httpCode?.let { "$base (HTTP $it)" } ?: base
        val head = providerLine?.takeIf { it.isNotBlank() }?.let { "$it\n$withCode" } ?: withCode
        return detail?.takeIf { it.isNotBlank() }?.let { "$head\n\n$it" } ?: head
    }
}
