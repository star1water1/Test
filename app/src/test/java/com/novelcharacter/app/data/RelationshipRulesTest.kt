package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.repository.RelationshipRules
import com.novelcharacter.app.data.repository.RelationshipRules.Rejection
import com.novelcharacter.app.data.repository.RelationshipRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 관계 편집의 공유 판정 — **두 창구가 같은 답을 받는 자리**(R-46).
 *
 * 종전에 이 판정은 `ai/NaturalBatchRelationshipEdits`의 사적 함수여서 시험이 AI 경로를
 * 통째로 세워야 닿을 수 있었다. 꺼낸 값이 이 파일이다.
 */
class RelationshipRulesTest {

    private fun rel(
        id: Long = 0,
        end1: Long = 1,
        end2: Long = 2,
        type: String = "연인",
        description: String = "",
        intensity: Int = 5,
        bidirectional: Boolean = true,
        factionId: Long? = null
    ) = CharacterRelationship(
        id = id,
        characterId1 = end1,
        characterId2 = end2,
        relationshipType = type,
        description = description,
        intensity = intensity,
        isBidirectional = bidirectional,
        factionId = factionId
    )

    private val types = listOf("연인", "동료", "적")

    // ───────── 강도 ─────────

    @Test
    fun intensity_boundsAreInclusive() {
        assertTrue(RelationshipRules.isValidIntensity(1))
        assertTrue(RelationshipRules.isValidIntensity(10))
        assertFalse(RelationshipRules.isValidIntensity(0))
        assertFalse(RelationshipRules.isValidIntensity(11))
        assertFalse(RelationshipRules.isValidIntensity(-1))
    }

    /** 상수가 범위와 어긋나면 화면 문구가 거짓이 된다 — 문구가 이 둘을 읽어 적는다. */
    @Test
    fun intensity_constantsMatchTheRange() {
        assertEquals(1, RelationshipRules.MIN_INTENSITY)
        assertEquals(10, RelationshipRules.MAX_INTENSITY)
    }

    // ───────── 자동 관계 ─────────

    @Test
    fun automatic_isDecidedByFactionId() {
        assertFalse(RelationshipRules.isAutomatic(rel()))
        assertTrue(RelationshipRules.isAutomatic(rel(factionId = 7)))
    }

    // ───────── 양 끝 ─────────

    @Test
    fun otherEnd_picksTheOppositeSide() {
        val row = rel(end1 = 10, end2 = 20)
        assertEquals(20, RelationshipRules.otherEndOf(row, 10))
        assertEquals(10, RelationshipRules.otherEndOf(row, 20))
    }

    @Test
    fun touches_seesBothEnds() {
        val row = rel(end1 = 10, end2 = 20)
        assertTrue(RelationshipRules.touches(row, 10))
        assertTrue(RelationshipRules.touches(row, 20))
        assertFalse(RelationshipRules.touches(row, 30))
    }

    // ───────── 겹침 ─────────

    @Test
    fun overlaps_sameDirectionSameType() {
        assertTrue(RelationshipRules.overlaps(rel(end1 = 1, end2 = 2), rel(end1 = 1, end2 = 2)))
    }

    @Test
    fun overlaps_differentTypeDoesNot() {
        assertFalse(
            RelationshipRules.overlaps(
                rel(end1 = 1, end2 = 2, type = "연인"),
                rel(end1 = 1, end2 = 2, type = "동료")
            )
        )
    }

    /**
     * **끝이 뒤바뀐 짝은 한쪽이라도 양방향일 때만 겹친다.**
     *
     * 이것이 이 판정이 코드에 있어야 하는 이유다 — Room의 유일 인덱스는
     * `(characterId1, characterId2, relationshipType)`이라 뒤바뀐 짝을 **다른 행으로 본다.**
     */
    @Test
    fun overlaps_reversedNeedsBidirectional() {
        val forward = rel(end1 = 1, end2 = 2, bidirectional = false)
        val backward = rel(end1 = 2, end2 = 1, bidirectional = false)
        assertFalse(RelationshipRules.overlaps(forward, backward))

        assertTrue(RelationshipRules.overlaps(forward.copy(isBidirectional = true), backward))
        assertTrue(RelationshipRules.overlaps(forward, backward.copy(isBidirectional = true)))
    }

    @Test
    fun overlaps_unrelatedPairsDoNot() {
        assertFalse(RelationshipRules.overlaps(rel(end1 = 1, end2 = 2), rel(end1 = 1, end2 = 3)))
    }

    // ───────── 같은 뜻 ─────────

    @Test
    fun sameMeaning_needsDescriptionAndIntensityToo() {
        val left = rel(description = "소꿉친구", intensity = 7)
        assertTrue(RelationshipRules.sameMeaning(left, left.copy(id = 99)))
        assertFalse(RelationshipRules.sameMeaning(left, left.copy(intensity = 8)))
        assertFalse(RelationshipRules.sameMeaning(left, left.copy(description = "다른 설명")))
        assertFalse(RelationshipRules.sameMeaning(left, left.copy(isBidirectional = false)))
    }

    /** 겹치지 않으면 같은 뜻일 수 없다 — 다른 짝을 내용만으로 같다고 하면 안 된다. */
    @Test
    fun sameMeaning_requiresOverlapFirst() {
        assertFalse(
            RelationshipRules.sameMeaning(rel(end1 = 1, end2 = 2), rel(end1 = 1, end2 = 3))
        )
    }

    // ───────── 겹침 찾기 ─────────

    @Test
    fun findOverlap_returnsTheBlockingRow() {
        val blocking = rel(id = 5, end1 = 1, end2 = 2, type = "연인")
        val others = listOf(rel(id = 4, end1 = 1, end2 = 3, type = "연인"), blocking)
        assertSame(blocking, RelationshipRules.findOverlap(others, rel(end1 = 1, end2 = 2)))
    }

    @Test
    fun findOverlap_nullWhenClear() {
        assertNull(RelationshipRules.findOverlap(emptyList(), rel()))
        assertNull(
            RelationshipRules.findOverlap(listOf(rel(id = 4, type = "동료")), rel(type = "연인"))
        )
    }

    // ───────── 조립된 판정 ─────────

    @Test
    fun validate_allowsACleanRow() {
        assertEquals(
            Verdict.Allowed,
            RelationshipRules.validate(rel(), existing = null, others = emptyList(), allowedTypes = types)
        )
    }

    /**
     * **자동 관계를 가장 먼저 본다.**
     *
     * 유형과 강도를 먼저 따지면 사용자가 그것을 고쳐 다시 보내고 **그래도 막힌다.**
     * 그래서 유형·강도까지 어긋난 요청에도 자동 관계 사유가 먼저 나와야 한다.
     */
    @Test
    fun validate_automaticRowWinsOverEverythingElse() {
        val existing = rel(id = 3, factionId = 9, type = "연인")
        val verdict = RelationshipRules.validate(
            proposed = rel(id = 3, type = "없는유형", intensity = 99, factionId = 9),
            existing = existing,
            others = emptyList(),
            allowedTypes = types
        )
        assertEquals(Verdict.Rejected(Rejection.AUTOMATIC_ROW, existing), verdict)
    }

    @Test
    fun validate_selfReference() {
        assertEquals(
            Verdict.Rejected(Rejection.SELF_REFERENCE),
            RelationshipRules.validate(
                rel(end1 = 4, end2 = 4), null, emptyList(), types
            )
        )
    }

    @Test
    fun validate_unknownType() {
        assertEquals(
            Verdict.Rejected(Rejection.UNKNOWN_TYPE),
            RelationshipRules.validate(rel(type = "없는유형"), null, emptyList(), types)
        )
    }

    @Test
    fun validate_badIntensity() {
        assertEquals(
            Verdict.Rejected(Rejection.BAD_INTENSITY),
            RelationshipRules.validate(rel(intensity = 0), null, emptyList(), types)
        )
        assertEquals(
            Verdict.Rejected(Rejection.BAD_INTENSITY),
            RelationshipRules.validate(rel(intensity = 11), null, emptyList(), types)
        )
    }

    /** 자동 관계가 막은 것과 수동 겹침은 **고칠 자리가 다르다** — 사유도 갈라야 한다. */
    @Test
    fun validate_automaticOverlapIsItsOwnReason() {
        val blocking = rel(id = 8, end1 = 1, end2 = 2, type = "동료", factionId = 4)
        assertEquals(
            Verdict.Rejected(Rejection.AUTOMATIC_OVERLAP, blocking),
            RelationshipRules.validate(
                rel(end1 = 1, end2 = 2, type = "동료"), null, listOf(blocking), types
            )
        )
    }

    @Test
    fun validate_duplicateWhenNothingWouldChange() {
        val blocking = rel(id = 8, end1 = 1, end2 = 2, type = "연인", description = "같음", intensity = 6)
        assertEquals(
            Verdict.Rejected(Rejection.DUPLICATE, blocking),
            RelationshipRules.validate(
                rel(end1 = 1, end2 = 2, type = "연인", description = "같음", intensity = 6),
                null, listOf(blocking), types
            )
        )
    }

    @Test
    fun validate_overlapWhenTheContentDiffers() {
        val blocking = rel(id = 8, end1 = 1, end2 = 2, type = "연인", intensity = 6)
        assertEquals(
            Verdict.Rejected(Rejection.OVERLAP, blocking),
            RelationshipRules.validate(
                rel(end1 = 1, end2 = 2, type = "연인", intensity = 9),
                null, listOf(blocking), types
            )
        )
    }

    /**
     * 고치는 중일 때 **자기 자신은 호출부가 뺀다.**
     *
     * 빼지 않으면 강도만 고친 저장이 *"겹치는 관계가 있다"*로 막힌다 — 그 계약을 시험으로
     * 적어 둔다(서명만으로는 드러나지 않는다).
     */
    @Test
    fun validate_editingAllowsItselfWhenExcluded() {
        val existing = rel(id = 8, end1 = 1, end2 = 2, type = "연인", intensity = 6)
        assertEquals(
            Verdict.Allowed,
            RelationshipRules.validate(
                existing.copy(intensity = 9), existing, emptyList(), types
            )
        )
    }
}
