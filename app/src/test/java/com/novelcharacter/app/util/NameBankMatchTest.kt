package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.NameBankEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-124 ⓑ — 저장 시 자동 대조의 판정 잠금.
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 넷이다:**
 * ① *"빼앗지 않는다"* — 무너지면 남이 쓰는 이름을 새 저장이 조용히 가져가고, 빼앗긴 쪽은
 *   화면 어디에도 그 사실이 안 뜬다(B-3이 세운 규약을 이 입구가 되돌리는 자리).
 * ② *"고른 것이 막히면 갈아치우지 않는다"* — 동명 엔트리가 둘일 때 고른 것 대신 다른 것을
 *   걸면 **사용자가 지시하지 않은 결과**가 조용히 남는다.
 * ③ *"이름이 바뀌면 옛 링크를 푼다"* — 안 풀면 은행이 *없는 이름을 그 캐릭터가 쓴다*고 말한다.
 * ④ *"정규화가 SQL과 한 벌"* — 순수 규칙이 넓어지면 조회가 못 준 후보를 판정만 일치시킨다.
 */
class NameBankMatchTest {

    private fun entry(
        id: Long,
        name: String,
        used: Boolean = false,
        usedBy: Long? = null,
        gender: String = ""
    ) = NameBankEntry(
        id = id, name = name, gender = gender, isUsed = used, usedByCharacterId = usedBy,
        createdAt = 0L, code = "code$id"
    )

    // ── ④ 정규화 ──

    @Test
    fun `정규화는 앞뒤 공백을 걷고 ASCII 대소문자를 접는다`() {
        assertEquals("aria", NameBankMatch.normalize("  Aria "))
        assertEquals("aria", NameBankMatch.normalize("ARIA"))
        assertTrue(NameBankMatch.sameName("Aria", " aria "))
    }

    @Test
    fun `내부 공백은 접지 않는다 — 다른 이름이다`() {
        assertFalse(NameBankMatch.sameName("홍 길동", "홍길동"))
    }

    @Test
    fun `비ASCII 대소문자는 일부러 접지 않는다 — SQL NOCASE와 한 벌이라서`() {
        // 코틀린 lowercase()를 쓰면 통과하는 자리다. 통과하면 순수 규칙이 조회보다 넓어져,
        // DAO가 후보를 주지 않는데 판정만 "일치"라고 믿는 어긋남이 생긴다.
        assertFalse(NameBankMatch.sameName("АНЯ", "аня"))
    }

    // ── ① 빼앗기 금지 ──

    @Test
    fun `남이 쓰는 동명 엔트리는 자동으로 걸지 않는다`() {
        val plan = NameBankMatch.planOnSave(
            savedName = "아리아", characterId = 7L, requestedEntryId = null,
            candidates = listOf(entry(1, "아리아", used = true, usedBy = 99L)),
            currentlyLinked = emptyList()
        )
        assertNull(plan.linkEntryId)
        assertTrue(plan.isNoop)
    }

    @Test
    fun `남이 쓰는 것을 건너뛰고 미사용 동명을 고른다`() {
        val plan = NameBankMatch.planOnSave(
            "아리아", 7L, null,
            candidates = listOf(entry(1, "아리아", used = true, usedBy = 99L), entry(2, "아리아")),
            currentlyLinked = emptyList()
        )
        assertEquals(2L, plan.linkEntryId)
    }

    @Test
    fun `내가 쓰던 엔트리는 남의 것이 아니다 — 재저장이 자기 링크를 잃지 않는다`() {
        val mine = entry(1, "아리아", used = true, usedBy = 7L)
        val plan = NameBankMatch.planOnSave(
            "아리아", 7L, null, candidates = listOf(mine), currentlyLinked = listOf(mine)
        )
        assertTrue(plan.isNoop) // 이미 물고 있다 — 다시 걸지도, 고지하지도 않는다
    }

    // ── ② 고른 것의 처분 ──

    @Test
    fun `고른 엔트리가 있으면 동명 미사용이 먼저 있어도 고른 것을 건다`() {
        val plan = NameBankMatch.planOnSave(
            "아리아", 7L, requestedEntryId = 5L,
            candidates = listOf(entry(2, "아리아"), entry(5, "아리아")),
            currentlyLinked = emptyList()
        )
        assertEquals(5L, plan.linkEntryId)
    }

    @Test
    fun `고른 것으로 갈아탈 때 이름이 같은 옛 링크도 푼다 — 한 캐릭터가 둘을 물지 않는다`() {
        val held = entry(1, "아리아", used = true, usedBy = 7L)
        val picked = entry(2, "아리아")
        val plan = NameBankMatch.planOnSave(
            "아리아", 7L, requestedEntryId = 2L,
            candidates = listOf(held, picked), currentlyLinked = listOf(held)
        )
        assertEquals(2L, plan.linkEntryId)
        assertEquals(listOf(1L), plan.releaseEntryIds)
    }

    @Test
    fun `고른 엔트리가 남의 것이 됐으면 다른 동명으로 갈아치우지 않고 말한다`() {
        val plan = NameBankMatch.planOnSave(
            "아리아", 7L, requestedEntryId = 5L,
            candidates = listOf(entry(2, "아리아"), entry(5, "아리아", used = true, usedBy = 99L)),
            currentlyLinked = emptyList()
        )
        assertNull(plan.linkEntryId)
        assertTrue(plan.requestedTaken)
    }

    @Test
    fun `고른 엔트리가 사라졌으면 말한다`() {
        val plan = NameBankMatch.planOnSave(
            "아리아", 7L, requestedEntryId = 5L,
            candidates = listOf(entry(2, "아리아")), currentlyLinked = emptyList()
        )
        assertNull(plan.linkEntryId)
        assertTrue(plan.requestedTaken)
    }

    @Test
    fun `고른 엔트리의 이름이 폼의 이름과 다르면 걸지 않는다`() {
        // 고른 뒤 이름 칸을 고쳐 쓴 경우 — 은행이 *다른 이름을 그 캐릭터가 쓴다*고 말하면 안 된다.
        val plan = NameBankMatch.planOnSave(
            "다른이름", 7L, requestedEntryId = 5L,
            candidates = emptyList(), currentlyLinked = emptyList()
        )
        assertNull(plan.linkEntryId)
        assertTrue(plan.requestedTaken)
    }

    // ── ③ 이름이 바뀌면 푼다 ──

    @Test
    fun `이름이 바뀌면 옛 링크를 푼다`() {
        val old = entry(1, "옛이름", used = true, usedBy = 7L)
        val plan = NameBankMatch.planOnSave(
            "새이름", 7L, null, candidates = listOf(entry(2, "새이름")), currentlyLinked = listOf(old)
        )
        assertEquals(listOf(1L), plan.releaseEntryIds)
        assertEquals(2L, plan.linkEntryId)
    }

    @Test
    fun `이름을 비운 저장도 옛 링크를 푼다`() {
        val old = entry(1, "옛이름", used = true, usedBy = 7L)
        val plan = NameBankMatch.planOnSave("   ", 7L, null, emptyList(), listOf(old))
        assertEquals(listOf(1L), plan.releaseEntryIds)
        assertNull(plan.linkEntryId)
    }

    @Test
    fun `이름이 그대로면 풀지 않는다`() {
        val mine = entry(1, "아리아", used = true, usedBy = 7L)
        val plan = NameBankMatch.planOnSave("아리아", 7L, null, listOf(mine), listOf(mine))
        assertTrue(plan.releaseEntryIds.isEmpty())
    }

    @Test
    fun `여러 개를 물고 있었으면 이름이 맞는 것만 남기고 나머지를 푼다`() {
        val keep = entry(1, "아리아", used = true, usedBy = 7L)
        val stale = entry(2, "옛이름", used = true, usedBy = 7L)
        val plan = NameBankMatch.planOnSave("아리아", 7L, null, listOf(keep), listOf(keep, stale))
        assertEquals(listOf(2L), plan.releaseEntryIds)
        assertNull(plan.linkEntryId)
    }

    // ── 결정성 ──

    @Test
    fun `동명 미사용이 여럿이면 먼저 등록된 것을 고른다 — 목록 순서에 맡기지 않는다`() {
        val shuffled = listOf(entry(9, "아리아"), entry(3, "아리아"), entry(6, "아리아"))
        assertEquals(3L, NameBankMatch.planOnSave("아리아", 7L, null, shuffled, emptyList()).linkEntryId)
        assertEquals(
            3L,
            NameBankMatch.planOnSave("아리아", 7L, null, shuffled.reversed(), emptyList()).linkEntryId
        )
    }

    @Test
    fun `조회가 넓게 줘도 이름이 다르면 거른다`() {
        val plan = NameBankMatch.planOnSave(
            "아리아", 7L, null, candidates = listOf(entry(1, "아리아나")), currentlyLinked = emptyList()
        )
        assertNull(plan.linkEntryId)
    }
}
