package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FactionRelationship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세력 관계 매칭·변경 규칙 — 가져오기와 복원 미리보기가 공유하는 단일 소스(B-87).
 *
 * 회귀 대상: 미리보기 분석에 '동일'을 셀 자리가 없어(unchanged에 상수 0) (세력1, 세력2, 유형)만
 * 맞으면 **전부 '변경'**으로 세었다. 아무것도 고치지 않은 내보내기 파일을 그대로 다시 넣어도
 * "세력 관계 변경 N"이라 말해 왕복 멱등 확인(A7)에서 사용자가 어긋남으로 읽는 자리다 —
 * 실제 가져오기는 같은 값을 다시 쓸 뿐 바뀌는 것이 없다.
 */
class FactionRelationshipMatcherTest {

    private fun relation(
        id: Long,
        factionId1: Long = 1,
        factionId2: Long = 2,
        relationType: String = "적대",
        description: String = "",
        intensity: Int = 5,
        isBidirectional: Boolean = true,
        displayOrder: Int = 0,
        code: String? = "code$id"
    ) = FactionRelationship(
        id = id,
        factionId1 = factionId1,
        factionId2 = factionId2,
        relationType = relationType,
        description = description,
        intensity = intensity,
        isBidirectional = isBidirectional,
        displayOrder = displayOrder,
        createdAt = 1_000L,
        code = code
    )

    private fun keyMap(vararg rels: FactionRelationship) =
        rels.associateBy { FactionRelationshipMatcher.key(it.factionId1, it.factionId2, it.relationType) }

    private fun codeMap(vararg rels: FactionRelationship) =
        rels.mapNotNull { r -> r.code?.let { it to r } }.toMap()

    /** 내보낸 행을 그대로 되읽었을 때의 RowValues (엑셀 왕복의 정상 경로) */
    private fun rowOf(r: FactionRelationship) = FactionRelationshipMatcher.RowValues(
        description = r.description,
        intensity = r.intensity,
        isBidirectional = r.isBidirectional,
        displayOrder = r.displayOrder
    )

    private fun indexOf(vararg rels: FactionRelationship) = rels.associateBy {
        FactionRelationshipMatcher.key(it.factionId1, it.factionId2, it.relationType)
    }

    // ── 이번 결함의 재현 ──

    @Test
    fun `고치지 않은 왕복은 전부 동일로 판정된다`() {
        val a = relation(id = 1, description = "국경 분쟁", intensity = 8, isBidirectional = true, displayOrder = 2)
        val b = relation(id = 2, factionId1 = 3, factionId2 = 4, relationType = "동맹", intensity = 3, isBidirectional = false)
        val byKey = indexOf(a, b)

        var newCount = 0
        var updateCount = 0
        var unchangedCount = 0
        for (r in listOf(a, b)) {
            val hit = FactionRelationshipMatcher.match(byKey, r.factionId1, r.factionId2, r.relationType)
            if (hit == null) {
                newCount++
            } else if (FactionRelationshipMatcher.changes(hit, rowOf(r))) {
                updateCount++
            } else {
                unchangedCount++
            }
        }
        assertEquals(0, newCount)
        assertEquals("아무것도 고치지 않았는데 '변경'이라 말하면 A7이 성립하지 않는다", 0, updateCount)
        assertEquals(2, unchangedCount)
    }

    @Test
    fun `매칭 키가 같아도 비교 대상이 다르면 변경이다`() {
        val existing = relation(id = 1, description = "국경 분쟁", intensity = 8)
        // 키((세력1, 세력2, 유형))는 그대로고 설명만 바뀌었다 — 종전 규칙은 이것을 구분하지 못했다
        assertTrue(FactionRelationshipMatcher.changes(existing, rowOf(existing).copy(description = "휴전")))
        assertTrue(FactionRelationshipMatcher.changes(existing, rowOf(existing).copy(intensity = 9)))
        assertTrue(FactionRelationshipMatcher.changes(existing, rowOf(existing).copy(isBidirectional = false)))
        assertTrue(FactionRelationshipMatcher.changes(existing, rowOf(existing).copy(displayOrder = 7)))
    }

    // ── match: 정방향·역방향 ──

    @Test
    fun `세력1과 세력2가 뒤바뀐 행도 같은 관계로 찾는다`() {
        val stored = relation(id = 5, factionId1 = 10, factionId2 = 20, relationType = "동맹")
        val byKey = indexOf(stored)
        assertSame(
            "역방향을 못 찾으면 재가져오기마다 뒤집힌 중복 행이 쌓인다",
            stored,
            FactionRelationshipMatcher.match(byKey, 20, 10, "동맹")
        )
    }

    @Test
    fun `유형이 다르면 다른 관계다`() {
        val byKey = indexOf(relation(id = 5, factionId1 = 10, factionId2 = 20, relationType = "동맹"))
        assertNull(FactionRelationshipMatcher.match(byKey, 10, 20, "적대"))
    }

    @Test
    fun `기존에 없으면 신규다`() {
        assertNull(FactionRelationshipMatcher.match(emptyMap(), 1, 2, "적대"))
    }

    // ── apply: 열이 없으면 기존값 유지 (F1-A) ──

    @Test
    fun `시트에 없는 열은 기존값을 유지한다`() {
        val existing = relation(id = 3, description = "국경 분쟁", intensity = 8, isBidirectional = false, displayOrder = 4)
        val row = FactionRelationshipMatcher.RowValues(description = "", intensity = 5, isBidirectional = true, displayOrder = 0)
        val presence = FactionRelationshipMatcher.ColumnPresence(
            description = false, intensity = false, isBidirectional = false, displayOrder = false
        )
        val applied = FactionRelationshipMatcher.apply(existing, row, presence)
        assertEquals("열이 없는데 설명이 지워지면 무음 손실이다", "국경 분쟁", applied.description)
        assertEquals(8, applied.intensity)
        assertFalse(applied.isBidirectional)
        assertEquals(4, applied.displayOrder)
        assertFalse("열이 없으면 바뀌는 것이 없으므로 '동일'이다", FactionRelationshipMatcher.changes(existing, row, presence))
    }

    @Test
    fun `열은 있고 칸이 비었으면 지운다`() {
        val existing = relation(id = 3, description = "국경 분쟁")
        val row = rowOf(existing).copy(description = "")
        val applied = FactionRelationshipMatcher.apply(existing, row)
        assertEquals("빈칸은 삭제 규약이다", "", applied.description)
        assertTrue(FactionRelationshipMatcher.changes(existing, row))
    }

    @Test
    fun `생성일과 id는 갱신 대상이 아니다`() {
        val existing = relation(id = 42, description = "가")
        val applied = FactionRelationshipMatcher.apply(existing, rowOf(existing).copy(description = "나"))
        assertEquals(42L, applied.id)
        assertEquals("안정 식별자를 조용히 바꾸지 않는다", 1_000L, applied.createdAt)
    }

    /**
     * **빈칸·해석 불가는 표시 순서를 덮지 않는다** (2026.08.23).
     *
     * 종전 `RowValues.displayOrder`는 `Int = 0`이라 빈칸이 곧 `0`이었고, 열 머리만 남아 있으면
     * [FactionRelationshipMatcher.ColumnPresence.displayOrder]가 참이므로 **기존 순서가 0으로
     * 덮였다.** 형제 열두 시트는 전부 `Int?` + `?: existing`이었는데 이 시트만 그 밖이었다.
     */
    @Test
    fun `표시순서 빈칸은 기존 순서를 지킨다`() {
        val existing = FactionRelationship(
            id = 1L, factionId1 = 1L, factionId2 = 2L, relationType = "동맹", displayOrder = 7
        )
        val row = FactionRelationshipMatcher.RowValues(
            description = existing.description, intensity = existing.intensity,
            isBidirectional = existing.isBidirectional, displayOrder = null
        )
        val applied = FactionRelationshipMatcher.apply(existing, row)
        assertEquals(7, applied.displayOrder)
        // 바뀐 것이 없으므로 '변경'으로도 세지 않는다.
        assertFalse(FactionRelationshipMatcher.changes(existing, row))
    }

    @Test
    fun `표시순서를 적으면 그 값으로 바뀐다`() {
        val existing = FactionRelationship(
            id = 1L, factionId1 = 1L, factionId2 = 2L, relationType = "동맹", displayOrder = 7
        )
        val row = FactionRelationshipMatcher.RowValues(
            description = existing.description, intensity = existing.intensity,
            isBidirectional = existing.isBidirectional, displayOrder = 0
        )
        assertEquals(0, FactionRelationshipMatcher.apply(existing, row).displayOrder)
        assertTrue(FactionRelationshipMatcher.changes(existing, row))
    }
    // ── 코드 우선 매칭 (v58, 2026.08.25) ──

    /**
     * **이 판이 고친 결함 그 자체다.** 자연키에 관계 유형이 들어 있어, 코드가 없던 종전에는
     * 시트에서 유형을 고치는 순간 그 행이 *다른 관계*가 됐다 — 새 관계가 생기고 옛 관계가
     * 그대로 남았다(사용자 파일의 `2 ↔ 1`이 '동맹'과 '동' 두 행).
     */
    @Test
    fun `코드가 같으면 유형을 고쳐도 같은 관계다`() {
        val existing = relation(id = 7, relationType = "동맹", code = "REL7")
        val match = FactionRelationshipMatcher.matchRow(
            keyMap(existing), codeMap(existing), "REL7", 1, 2, "동"
        )
        assertSame(existing, match.existing)
        assertNull(match.codeOfOtherPair)
    }

    /** 코드 열이 없는 옛 파일은 종전 그대로 — 자연키로 맞춘다. */
    @Test
    fun `코드가 비면 자연키로 맞춘다`() {
        val existing = relation(id = 7, relationType = "동맹")
        val match = FactionRelationshipMatcher.matchRow(
            keyMap(existing), codeMap(existing), "", 1, 2, "동맹"
        )
        assertSame(existing, match.existing)
        assertTrue(match.canReuseFileCode)
    }

    /** 세력1·세력2가 뒤바뀐 행도 같은 쌍이다 — 코드 조건이 그 성질을 깨뜨리면 안 된다. */
    @Test
    fun `뒤집힌 쌍도 코드로 같은 관계다`() {
        val existing = relation(id = 7, factionId1 = 1, factionId2 = 2, relationType = "동맹", code = "REL7")
        val match = FactionRelationshipMatcher.matchRow(
            keyMap(existing), codeMap(existing), "REL7", 2, 1, "적대"
        )
        assertSame(existing, match.existing)
    }

    /**
     * '코드' 열은 회색이라 **행을 복사하면 남의 코드가 따라온다.** 그 코드를 따르면 남의
     * 관계가 이 행의 값으로 덮이고 이 행이 말한 관계는 만들어지지 않는다 — 둘 다 말이 없다.
     */
    @Test
    fun `다른 쌍을 가리키는 코드는 따르지 않는다`() {
        val other = relation(id = 7, factionId1 = 1, factionId2 = 2, relationType = "동맹", code = "REL7")
        val match = FactionRelationshipMatcher.matchRow(
            keyMap(other), codeMap(other), "REL7", 3, 4, "적대"
        )
        assertNull(match.existing)
        assertSame(other, match.codeOfOtherPair)
        // 유니크 열이라 남이 든 코드를 새 행에 재사용할 수 없다.
        assertFalse(match.canReuseFileCode)
    }

    /** 파일의 코드가 이 기기에 없으면(다른 기기에서 옮겨 온 파일) 자연키로 떨어진다. */
    @Test
    fun `모르는 코드는 자연키 폴백이다`() {
        val existing = relation(id = 7, relationType = "동맹", code = "REL7")
        val match = FactionRelationshipMatcher.matchRow(
            keyMap(existing), codeMap(existing), "없는코드", 1, 2, "동맹"
        )
        assertSame(existing, match.existing)
        assertNull(match.codeOfOtherPair)
        assertTrue(match.canReuseFileCode)
    }

}
