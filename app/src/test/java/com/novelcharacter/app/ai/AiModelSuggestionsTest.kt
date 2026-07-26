package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 추천 모델 칩의 목록 구성 규칙.
 *
 * 고정하는 계약: **프리셋 스냅샷이 낡아도 새 모델이 칩에 도달한다.** 이것이 "왜 최신 모델을
 * 추천하지 않느냐"의 해법이므로, 큐레이션 순서를 지키면서도 서버에만 있는 모델이 반드시
 * 남아야 한다.
 */
class AiModelSuggestionsTest {

    @Test
    fun `프리셋이 낡아도 서버에만 있는 새 모델이 칩에 들어온다`() {
        val curated = listOf("claude-opus-4-8", "claude-sonnet-5")   // 낡은 스냅샷
        val live = listOf("claude-opus-5", "claude-fable-5", "claude-sonnet-5", "claude-haiku-4-5")
        val ranked = AiModelSuggestions.rank(live, curated)
        assertTrue("새 플래그십이 빠지면 안 된다", "claude-opus-5" in ranked)
        assertTrue("claude-fable-5" in ranked)
        assertTrue("서버에 없는 낡은 이름은 제외한다", "claude-opus-4-8" !in ranked)
    }

    @Test
    fun `서버에도 있는 큐레이션 모델이 먼저 온다`() {
        val curated = listOf("claude-sonnet-5", "claude-haiku-4-5")
        val live = listOf("claude-opus-5", "claude-haiku-4-5", "claude-sonnet-5")
        // 큐레이션 순서(사람이 고른 순서)를 유지하고, 서버에만 있는 것은 뒤에 붙인다.
        assertEquals(
            listOf("claude-sonnet-5", "claude-haiku-4-5", "claude-opus-5"),
            AiModelSuggestions.rank(live, curated)
        )
    }

    @Test
    fun `서버에만 있는 모델은 서버 순서를 유지한다`() {
        // Anthropic의 목록 조회는 최신순으로 준다 — 그 순서가 곧 유용한 순서다.
        val live = listOf("newest", "middle", "oldest")
        assertEquals(live, AiModelSuggestions.rank(live, emptyList()))
    }

    @Test
    fun `서버 목록이 비면 큐레이션으로 폴백한다`() {
        // 키가 없거나 조회 실패 — 칩이 통째로 사라지면 원탭 선택이라는 목적이 없어진다.
        val curated = listOf("a", "b")
        assertEquals(curated, AiModelSuggestions.rank(emptyList(), curated))
    }

    @Test
    fun `둘 다 비면 빈 목록이다`() {
        assertEquals(emptyList<String>(), AiModelSuggestions.rank(emptyList(), emptyList()))
    }

    @Test
    fun `상한을 넘지 않는다`() {
        val live = (1..50).map { "model-$it" }
        val ranked = AiModelSuggestions.rank(live, emptyList())
        assertEquals(AiModelSuggestions.CHIP_LIMIT, ranked.size)
        // 상한이 있어도 큐레이션은 자리를 확보한다
        val curated = listOf("model-50")
        assertEquals("model-50", AiModelSuggestions.rank(live, curated).first())
    }

    @Test
    fun `중복과 공백은 정리한다`() {
        val ranked = AiModelSuggestions.rank(
            live = listOf(" gpt-5.1 ", "gpt-5.1", "", "   ", "gpt-5-mini"),
            curated = listOf("gpt-5.1", "gpt-5.1")
        )
        assertEquals(listOf("gpt-5.1", "gpt-5-mini"), ranked)
    }

    @Test
    fun `상한이 0 이하면 빈 목록이다`() {
        assertEquals(emptyList<String>(), AiModelSuggestions.rank(listOf("a"), listOf("a"), limit = 0))
    }
}
