package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * **필드 설명** — 사용자가 쓰는, 이 필드가 무엇인지에 대한 설명 (P-A A-2).
 * `FieldDefinition.config` JSON의 `"description"` 키에 저장된다.
 * 예: "마나친화 — 이 세계의 마나를 몸에 받아들이는 정도. 0~100 수치이며, 60 이상은 마법사 계열만 나온다."
 *
 * 혼동하기 쉬운 이웃과 다르다(합치지 않는다):
 * - [FieldValueEntry.description]은 **값 하나**의 설명(값 라이브러리) — 대상이 필드가 아니라 값이다.
 * - 인앱 도움말(helpKey)은 **앱 기능**의 설명(개발자 작성, strings.xml) — 출처가 사용자가 아니다.
 * - TextInputLayout.helperText는 항상 보이는 한 줄 힌트 — 설명은 길고, 눌러야 보인다.
 *
 * AI 경로에서는 이 설명이 그 필드가 뜻하는 바의 **정의이자 계약**으로 전달된다(시스템 규칙 14).
 * 프롬프트 적재량 설정의 대상이 아니다 — 설정이 끄는 것은 토큰이지 정확성이 아니다.
 */
object FieldDescription {
    const val CONFIG_KEY = "description"

    /** 저장 상한(입력창 카운터 기준). 프롬프트에 싣는 상한(300)은 AI 계층이 따로 갖는다. */
    const val MAX_CHARS = 1000

    /** 키 없음·손상 JSON = 빈 문자열(설명 없음). */
    fun fromConfig(configJson: String): String = try {
        JSONObject(configJson).optString(CONFIG_KEY, "")
    } catch (_: Exception) {
        ""
    }

    /** 빈 문자열이면 키를 제거한다. 저장 상한 초과분은 자른다. 손상 JSON은 원문 유지. */
    fun applyToConfig(existing: String, text: String): String = try {
        val json = if (existing.isBlank()) JSONObject() else JSONObject(existing)
        val trimmed = text.trim().take(MAX_CHARS)
        if (trimmed.isEmpty()) json.remove(CONFIG_KEY) else json.put(CONFIG_KEY, trimmed)
        json.toString()
    } catch (_: Exception) {
        existing
    }
}
