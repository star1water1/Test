package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AI 응답의 관대 JSON 추출 — 코드펜스·서두 잡담·중첩 구조·파손 입력. */
class AiJsonExtractorTest {

    @Test
    fun plainJson() {
        val obj = AiJsonExtractor.extractObject("""{"a":1}""")
        assertEquals(1, obj?.getInt("a"))
    }

    @Test
    fun codeFencedJson() {
        val text = "```json\n{\"merges\":[{\"canonical\":\"서울\"}]}\n```"
        val obj = AiJsonExtractor.extractObject(text)
        assertEquals("서울", obj?.getJSONArray("merges")?.getJSONObject(0)?.getString("canonical"))
    }

    @Test
    fun chatterAroundJson() {
        val text = "다음과 같이 정리했습니다.\n{\"categories\":[{\"value\":\"청염\",\"category\":\"불\"}]}\n도움이 되었기를!"
        val obj = AiJsonExtractor.extractObject(text)
        assertEquals("불", obj?.getJSONArray("categories")?.getJSONObject(0)?.getString("category"))
    }

    @Test
    fun nestedBracesAndStringsWithBraces() {
        val text = """{"a":{"b":"중괄호 } 포함 문자열","c":[{"d":2}]}}"""
        val obj = AiJsonExtractor.extractObject(text)
        assertEquals("중괄호 } 포함 문자열", obj?.getJSONObject("a")?.getString("b"))
    }

    @Test
    fun escapedQuoteInString() {
        val text = """{"a":"인용 \" 부호"}"""
        assertEquals("인용 \" 부호", AiJsonExtractor.extractObject(text)?.getString("a"))
    }

    @Test
    fun brokenJson_returnsNull() {
        assertNull(AiJsonExtractor.extractObject("{\"a\": 1"))          // 미종결
        assertNull(AiJsonExtractor.extractObject("응답이 없습니다"))      // JSON 없음
        assertNull(AiJsonExtractor.extractObject(""))
    }
}
