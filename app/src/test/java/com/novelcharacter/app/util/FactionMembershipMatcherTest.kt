package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FactionMembership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세력 소속 매칭 규칙 — 가져오기와 복원 미리보기가 공유하는 단일 소스.
 *
 * 회귀 대상(실기기 확인 A7에서 사용자가 발견): 아무것도 고치지 않은 내보내기 파일을 그대로
 * 다시 넣었는데 미리보기가 '세력 소속 신규 1 / 백업에 없음 1'을 표시했다. 원인은 분석 쪽이
 * 활성 소속만 보는 옛 규칙에 남아 **탈퇴 이력 행을 영원히 매칭하지 못한** 것이었다.
 */
class FactionMembershipMatcherTest {

    private fun membership(
        id: Long,
        joinYear: Int? = null,
        leaveYear: Int? = null,
        leaveType: String? = null,
        departedRelationType: String? = null,
        departedIntensity: Int? = null,
        createdAt: Long = 1_000L
    ) = FactionMembership(
        id = id,
        factionId = 7,
        characterId = 3,
        joinYear = joinYear,
        leaveYear = leaveYear,
        leaveType = leaveType,
        departedRelationType = departedRelationType,
        departedIntensity = departedIntensity,
        createdAt = createdAt
    )

    /** 내보낸 행을 그대로 되읽었을 때의 RowValues (엑셀 왕복의 정상 경로) */
    private fun rowOf(m: FactionMembership) = FactionMembershipMatcher.RowValues(
        joinYear = m.joinYear,
        leaveYear = m.leaveYear,
        leaveType = m.leaveType,
        departedRelationType = m.departedRelationType,
        departedIntensity = m.departedIntensity,
        createdAt = m.createdAt
    )

    // ── 이번 결함의 재현 ──

    @Test
    fun `탈퇴 이력도 생성일로 매칭된다 - 활성만 보던 규칙의 회귀`() {
        val departed = membership(
            id = 11,
            joinYear = 100,
            leaveYear = 120,
            leaveType = FactionMembership.LEAVE_DEPARTED,
            departedRelationType = "적대",
            departedIntensity = 4,
            createdAt = 1_753_800_000_000L
        )
        val matched = FactionMembershipMatcher.match(listOf(departed), rowOf(departed))
        assertSame("탈퇴 이력이 매칭되지 않으면 매 왕복마다 중복 행이 생긴다", departed, matched)
    }

    @Test
    fun `고치지 않은 왕복은 활성도 탈퇴도 전부 동일로 판정된다`() {
        val active = membership(id = 1, joinYear = 100, createdAt = 1_700_000_000_000L)
        val departed = membership(
            id = 2, joinYear = 50, leaveYear = 80,
            leaveType = FactionMembership.LEAVE_REMOVED, createdAt = 1_700_000_000_001L
        )
        var newCount = 0
        var updateCount = 0
        var unchangedCount = 0
        val taken = mutableSetOf<Long>()
        for (row in listOf(rowOf(active), rowOf(departed))) {
            val candidates = listOf(active, departed).filter { it.id !in taken }
            val hit = FactionMembershipMatcher.match(candidates, row)
            if (hit == null) {
                newCount++
            } else {
                taken.add(hit.id)
                if (FactionMembershipMatcher.changes(hit, row)) updateCount++ else unchangedCount++
            }
        }
        assertEquals("신규가 하나라도 있으면 미리보기가 삭제를 예고한다", 0, newCount)
        assertEquals(0, updateCount)
        assertEquals(2, unchangedCount)
    }

    // ── 계층 ① 생성일 ──

    @Test
    fun `생성일이 같으면 자연키가 달라도 그 이력을 고른다`() {
        val target = membership(id = 5, joinYear = 10, createdAt = 900L)
        val other = membership(id = 6, joinYear = 99, createdAt = 901L)
        val row = FactionMembershipMatcher.RowValues(joinYear = 99, createdAt = 900L)
        assertSame(target, FactionMembershipMatcher.match(listOf(target, other), row))
    }

    // ── 계층 ② 자연키 ──

    @Test
    fun `생성일 열이 없으면 가입·탈퇴연도와 탈퇴유형으로 매칭한다`() {
        val a = membership(id = 1, joinYear = 10, leaveYear = 20, leaveType = FactionMembership.LEAVE_DEPARTED)
        val b = membership(id = 2, joinYear = 30)
        val row = FactionMembershipMatcher.RowValues(
            joinYear = 10, leaveYear = 20, leaveType = FactionMembership.LEAVE_DEPARTED, createdAt = null
        )
        assertSame(a, FactionMembershipMatcher.match(listOf(a, b), row))
    }

    @Test
    fun `같은 쌍에 이력이 둘이면 각 행이 서로 다른 이력을 가져간다`() {
        val first = membership(id = 1, joinYear = 10, leaveYear = 20, leaveType = FactionMembership.LEAVE_DEPARTED, createdAt = 100L)
        val second = membership(id = 2, joinYear = 30, createdAt = 200L)
        val taken = mutableSetOf<Long>()
        val hits = listOf(rowOf(first), rowOf(second)).map { row ->
            val hit = FactionMembershipMatcher.match(listOf(first, second).filter { it.id !in taken }, row)
            hit?.also { taken.add(it.id) }
        }
        assertEquals(listOf(1L, 2L), hits.map { it?.id })
    }

    // ── 계층 ③ 유일 후보 = 상태 전이 ──

    @Test
    fun `후보가 하나뿐이면 값이 달라도 상태 전이로 본다`() {
        val active = membership(id = 9, joinYear = 10, createdAt = 500L)
        val row = FactionMembershipMatcher.RowValues(
            joinYear = 10, leaveYear = 40, leaveType = FactionMembership.LEAVE_DEPARTED, createdAt = 777L
        )
        val hit = FactionMembershipMatcher.match(listOf(active), row)
        assertSame(active, hit)
        assertTrue("활성 → 탈퇴는 변경으로 세어야 한다", FactionMembershipMatcher.changes(active, row))
    }

    @Test
    fun `후보가 둘 이상이고 어느 계층에도 안 걸리면 신규다`() {
        val a = membership(id = 1, joinYear = 10, createdAt = 100L)
        val b = membership(id = 2, joinYear = 20, createdAt = 200L)
        val row = FactionMembershipMatcher.RowValues(joinYear = 77, createdAt = 999L)
        assertNull(FactionMembershipMatcher.match(listOf(a, b), row))
    }

    @Test
    fun `후보가 없으면 신규다`() {
        val row = FactionMembershipMatcher.RowValues(joinYear = 1, createdAt = 1L)
        assertNull(FactionMembershipMatcher.match(emptyList(), row))
    }

    // ── apply: 열이 없으면 기존값 유지 (F1-A) ──

    @Test
    fun `시트에 없는 열은 기존값을 유지한다`() {
        val existing = membership(
            id = 3, joinYear = 10, leaveYear = 20,
            leaveType = FactionMembership.LEAVE_DEPARTED,
            departedRelationType = "적대", departedIntensity = 5, createdAt = 300L
        )
        val row = FactionMembershipMatcher.RowValues(joinYear = 11)
        val presence = FactionMembershipMatcher.ColumnPresence(
            joinYear = true, leaveYear = false, leaveType = false,
            departedRelationType = false, departedIntensity = false, createdAt = false
        )
        val applied = FactionMembershipMatcher.apply(existing, row, presence)
        assertEquals(11, applied.joinYear)
        assertEquals("열이 없는데 예정 탈퇴가 지워지면 안 된다", 20, applied.leaveYear)
        assertEquals(FactionMembership.LEAVE_DEPARTED, applied.leaveType)
        assertEquals("적대", applied.departedRelationType)
        assertEquals(5, applied.departedIntensity)
        assertEquals(300L, applied.createdAt)
    }

    @Test
    fun `열은 있고 칸이 비었으면 지운다`() {
        val existing = membership(id = 3, joinYear = 10, leaveYear = 20, leaveType = FactionMembership.LEAVE_DEPARTED, createdAt = 300L)
        val row = FactionMembershipMatcher.RowValues(joinYear = 10, createdAt = 300L)
        val applied = FactionMembershipMatcher.apply(existing, row)
        assertNull("빈칸은 삭제 규약이다", applied.leaveYear)
        assertNull(applied.leaveType)
    }

    @Test
    fun `생성일을 읽지 못하면 기존 생성일을 유지한다`() {
        val existing = membership(id = 4, joinYear = 10, createdAt = 424_242L)
        val row = FactionMembershipMatcher.RowValues(joinYear = 10, createdAt = null)
        val applied = FactionMembershipMatcher.apply(existing, row)
        assertEquals("해석 불가한 생성일에 현재 시각을 찍으면 안정 식별자가 흔들린다", 424_242L, applied.createdAt)
        assertFalse(FactionMembershipMatcher.changes(existing, row))
    }

    @Test
    fun `값이 하나라도 다르면 변경으로 판정한다`() {
        val existing = membership(id = 4, joinYear = 10, departedIntensity = 3, createdAt = 500L)
        val row = rowOf(existing).copy(departedIntensity = 4)
        assertTrue(FactionMembershipMatcher.changes(existing, row))
    }
}
