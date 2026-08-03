package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프리셋 데이터의 자체 정합성.
 *
 * `AiPresets`가 `R`을 참조하므로 순수 JVM 하네스(tools/run_jvm_tests.sh)로는 돌릴 수 없고,
 * CI의 `testDebugUnitTest`에서 검증된다. 순수 로직 테스트는 `AiModelSuggestionsTest`에 있다.
 */
class AiPresetsConsistencyTest {

    @Test
    fun `기본 모델은 추천 목록 안에 있다`() {
        // 기본값이 추천 칩에 없으면 편집 화면이 어떤 칩도 체크되지 않은 채로 열려,
        // 사용자가 무엇이 선택돼 있는지 알 수 없다.
        for (preset in AiPresets.ALL) {
            if (preset.defaultModel.isBlank()) continue
            assertTrue(
                "${preset.id}: 기본 모델 '${preset.defaultModel}'이 추천 목록에 없다",
                preset.defaultModel in preset.suggestedModels
            )
        }
    }

    @Test
    fun `추천 목록에 중복이나 빈 값이 없다`() {
        for (preset in AiPresets.ALL) {
            val models = preset.suggestedModels
            assertTrue("${preset.id}: 빈 모델명", models.none { it.isBlank() })
            assertTrue("${preset.id}: 중복 모델명", models.size == models.distinct().size)
        }
    }

    @Test
    fun `프리셋 id는 유일하다`() {
        // id는 저장된 설정이 "어느 프리셋에서 나왔는가"를 가리키는 키다(발급 가이드 재표시용).
        val ids = AiPresets.ALL.map { it.id }
        assertEquals("중복된 프리셋 id", ids.size, ids.distinct().size)
    }

    @Test
    fun `Groq와 xAI(Grok)은 서로 다른 프리셋으로 구별된다`() {
        // **실사용에서 실제로 걸린 함정이다**(2026.08.03 보고). 이름이 한 글자 차이라
        // 한국어로는 둘 다 "그록"으로 읽히고, 사용자가 xAI 키를 Groq 프리셋에 붙여 넣어
        // 인증이 실패했다. 둘이 갈라져 있다는 것과, **화면과 가이드가 서로를 가리킨다는 것**을
        // 계약으로 고정한다 — 프리셋만 늘리고 안내를 빠뜨리면 함정이 그대로 남는다.
        val groq = AiPresets.byId("groq")
        val xai = AiPresets.byId("xai")
        assertNotNull("Groq 프리셋이 있어야 한다", groq)
        assertNotNull("xAI(Grok) 프리셋이 있어야 한다", xai)
        requireNotNull(groq); requireNotNull(xai)

        assertNotEquals("두 프리셋의 주소가 같으면 구별이 무의미하다", groq.baseUrl, xai.baseUrl)
        assertNotEquals("키 발급처가 갈려 있어야 한다", groq.consoleUrl, xai.consoleUrl)
        assertTrue("Groq은 api.groq.com을 본다", groq.baseUrl.contains("api.groq.com"))
        assertTrue("xAI는 api.x.ai를 본다", xai.baseUrl.contains("api.x.ai"))

        // 표시명만으로 고를 수 있어야 한다 — 목록에서 보이는 것이 이것뿐이다.
        assertTrue("xAI 표시명이 Grok을 말해야 한다", xai.displayName.contains("Grok"))
        assertNotEquals(groq.displayName, xai.displayName)
    }

    @Test
    fun `키 발급 콘솔과 모델 문서 주소는 https다`() {
        for (preset in AiPresets.ALL) {
            listOf(preset.consoleUrl, preset.modelDocsUrl)
                .filter { it.isNotBlank() }
                .forEach { assertTrue("${preset.id}: $it", it.startsWith("https://")) }
        }
    }
}
