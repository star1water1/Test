package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * '통계에 포함' 판정의 단일 소스 계약 (S-14 · B-35).
 *
 * 규칙은 하나다 — **자동 선택은 설정을 따르고, 명시적 선택은 존중한다.**
 * 앱이 스스로 필드를 고르는 목록(패턴·인사이트·레거시 분포·순위 목록)에서는 끈 필드가
 * 나타나지 않고, 사용자가 고른 필드는 그 설정과 무관하게 계산된다(고를 수 있는데 빈 결과가
 * 나오는 조용한 실패를 만들지 않기 위해서다).
 */
class StatsFieldPolicyTest {

    private fun field(id: Long, key: String, universeId: Long, enabled: Boolean, type: String = "TEXT") =
        FieldDefinition(
            id = id, universeId = universeId, key = key, name = key, type = type,
            config = """{"stats":{"enabled":$enabled}}""",
            entityType = FieldDefinition.ENTITY_CHARACTER
        )

    @Test
    fun `설정이 없으면 분석 대상이다`() {
        val fd = FieldDefinition(
            id = 1, universeId = 1, key = "job", name = "직업", type = "TEXT", config = "{}",
            entityType = FieldDefinition.ENTITY_CHARACTER
        )
        assertTrue(StatsFieldPolicy.isAnalyzable(fd))
    }

    @Test
    fun `끈 필드는 자동 목록에서 빠진다`() {
        val defs = listOf(field(1, "job", 1, true), field(2, "memo", 1, false))
        assertEquals(listOf(1L), StatsFieldPolicy.analyzable(defs).map { it.id })
        assertFalse(StatsFieldPolicy.isAnalyzable(defs[1]))
    }

    @Test
    fun `그룹 확장은 고른 def를 항상 첫 원소로 둔다`() {
        // 첫 원소가 파싱 기준 def라는 계약(R-15)을 지켜야 차트와 같은 값 공간이 된다.
        val defs = listOf(field(1, "job", 1, true), field(2, "job", 2, true))
        assertEquals(listOf(2L, 1L), StatsFieldPolicy.expandGroup(defs, 2).map { it.id })
    }

    @Test
    fun `자동으로 딸려오는 형제만 설정을 따른다`() {
        val defs = listOf(field(1, "job", 1, true), field(2, "job", 2, false))
        // 세계관 B의 '직업'을 통계에서 뺐다 → A를 고르면 B는 합산되지 않는다.
        assertEquals(listOf(1L), StatsFieldPolicy.expandGroup(defs, 1).map { it.id })
        // 그런데 B 자체를 고르면(다른 화면·구버전 상태) 계산은 한다 — 조용한 빈 표 금지.
        // 이때도 켜진 형제(A)는 평소처럼 합류한다. 고른 def가 언제나 첫 원소다.
        assertEquals(listOf(2L, 1L), StatsFieldPolicy.expandGroup(defs, 2).map { it.id })
    }

    @Test
    fun `타입이 다르면 같은 key여도 다른 그룹이다`() {
        val defs = listOf(field(1, "age", 1, true, "NUMBER"), field(2, "age", 2, true, "TEXT"))
        assertEquals(listOf(1L), StatsFieldPolicy.expandGroup(defs, 1).map { it.id })
    }

    @Test
    fun `없는 id는 빈 목록 — 못 찾음과 없음을 구분한다`() {
        val defs = listOf(field(1, "job", 1, true))
        assertTrue(StatsFieldPolicy.expandGroup(defs, 999).isEmpty())
    }

    @Test
    fun `캐시는 같은 판정을 준다`() {
        val defs = listOf(field(1, "job", 1, true), field(2, "memo", 1, false))
        val cache = StatsFieldPolicy.ConfigCache()
        assertEquals(
            StatsFieldPolicy.analyzable(defs).map { it.id },
            cache.analyzable(defs).map { it.id }
        )
        assertTrue(cache.of(defs[0]).enabled)
        assertFalse(cache.of(defs[1]).enabled)
    }
}
