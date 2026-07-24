package com.novelcharacter.app.ai

import org.json.JSONObject

/**
 * AI 응답 텍스트에서 JSON 객체를 관대하게 추출하는 공용 유틸 (순수 JVM — 단위 테스트 대상).
 *
 * 모델은 스키마를 지시받아도 코드펜스·서두/말미 잡담을 붙이곤 한다.
 * 거부 대신 유연한 수용: 펜스 제거 → 첫 '{'부터 괄호 균형 추출 → 파싱.
 * 실패 시 null (호출측이 사용자에게 실패를 표면화할 책임 — 조용히 버리지 않는다).
 *
 * 필드 데이터 라이브러리 AI 정리가 첫 사용처이며, 이후 AI 기능(필드 채우기 제안 등)이 공용한다.
 */
object AiJsonExtractor {

    fun extractObject(text: String): JSONObject? {
        val stripped = stripCodeFences(text)
        val candidate = balancedJsonSlice(stripped) ?: return null
        return try {
            JSONObject(candidate)
        } catch (_: Exception) {
            null
        }
    }

    private fun stripCodeFences(text: String): String {
        var t = text.trim()
        // ```json ... ``` 또는 ``` ... ``` — 첫 펜스 블록 내부를 우선 사용
        val fenceStart = t.indexOf("```")
        if (fenceStart >= 0) {
            val afterFence = t.indexOf('\n', fenceStart)
            if (afterFence >= 0) {
                val fenceEnd = t.indexOf("```", afterFence)
                if (fenceEnd > afterFence) {
                    t = t.substring(afterFence + 1, fenceEnd)
                }
            }
        }
        return t.trim()
    }

    /** 첫 '{'부터 문자열 리터럴을 존중하며 괄호 균형이 맞는 조각을 잘라낸다 */
    private fun balancedJsonSlice(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            when {
                escaped -> escaped = false
                inString && ch == '\\' -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
