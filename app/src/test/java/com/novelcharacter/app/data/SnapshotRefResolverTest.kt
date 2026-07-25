package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.FieldDefNaturalKey
import com.novelcharacter.app.data.model.FieldDefRef
import com.novelcharacter.app.data.repository.SnapshotRefResolver
import com.novelcharacter.app.data.repository.SnapshotRefResolver.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴지통 스냅샷 참조 해석 사다리 (N1).
 *
 * 이 테스트가 고정하는 계약:
 *  - 코드가 대상을 정하고 id는 확인 수단이다 (4-4 규약)
 *  - 안정 식별자가 있는데 대상이 없으면 **id로 폴백하지 않는다** (오배정 > 생략)
 *  - 구버전 payload는 종전과 동일하게 동작한다 (하위호환)
 */
class SnapshotRefResolverTest {

    // ── code 기반 엔티티 (캐릭터·세력·사건·작품) ──

    @Test
    fun `옛 id와 코드가 모두 일치하면 옛 id를 그대로 쓴다`() {
        val res = SnapshotRefResolver.resolveByCode(
            oldId = 7L,
            snapshotCode = "AAA",
            codeById = mapOf(7L to "AAA"),
            idByCode = mapOf("AAA" to 7L),
            liveIds = setOf(7L)
        )
        assertEquals(7L, res.id)
        assertEquals(Origin.ID_CONFIRMED, res.origin)
        assertFalse(res.isLegacyGuess)
    }

    @Test
    fun `덮어쓰기로 id가 재발급돼도 코드로 다시 찾는다`() {
        // 옛 id 7은 사라졌고 같은 코드가 id 91로 다시 들어왔다 (엑셀 덮어쓰기 임포트)
        val res = SnapshotRefResolver.resolveByCode(
            oldId = 7L,
            snapshotCode = "AAA",
            codeById = mapOf(91L to "AAA"),
            idByCode = mapOf("AAA" to 91L),
            liveIds = setOf(91L)
        )
        assertEquals(91L, res.id)
        assertEquals(Origin.CODE, res.origin)
    }

    @Test
    fun `옛 id를 다른 엔티티가 물려받았으면 그 id로 폴백하지 않는다`() {
        // 테이블 재생성 마이그레이션으로 sqlite_sequence가 되돌아가 id 7을 다른 대상이 쓰고 있다.
        // 종전 구현의 `getById(7) != null`은 이 경우 **엉뚱한 대상**을 통과시켰다.
        val res = SnapshotRefResolver.resolveByCode(
            oldId = 7L,
            snapshotCode = "AAA",
            codeById = mapOf(7L to "ZZZ"),
            idByCode = emptyMap(),
            liveIds = setOf(7L)
        )
        assertNull(res.id)
        assertEquals(Origin.MISSING, res.origin)
    }

    @Test
    fun `대상이 정말 사라졌으면 찾지 못했다고 보고한다`() {
        val res = SnapshotRefResolver.resolveByCode(
            oldId = 7L,
            snapshotCode = "AAA",
            codeById = emptyMap(),
            idByCode = emptyMap(),
            liveIds = emptySet()
        )
        assertNull(res.id)
        assertEquals(Origin.MISSING, res.origin)
    }

    @Test
    fun `구버전 payload는 옛 id가 살아 있으면 종전대로 동작한다`() {
        val res = SnapshotRefResolver.resolveByCode(
            oldId = 7L,
            snapshotCode = null,
            codeById = mapOf(7L to "AAA"),
            idByCode = mapOf("AAA" to 7L),
            liveIds = setOf(7L)
        )
        assertEquals(7L, res.id)
        assertEquals(Origin.LEGACY_ID, res.origin)
        assertTrue("근거가 id뿐임을 고지할 수 있어야 한다", res.isLegacyGuess)
    }

    @Test
    fun `구버전 payload에서 옛 id가 없으면 생략한다`() {
        val res = SnapshotRefResolver.resolveByCode(
            oldId = 7L,
            snapshotCode = "",   // 빈 문자열도 '근거 없음'으로 본다
            codeById = emptyMap(),
            idByCode = emptyMap(),
            liveIds = emptySet()
        )
        assertNull(res.id)
        assertEquals(Origin.LEGACY_MISSING, res.origin)
    }

    @Test
    fun `code가 null인 레거시 행은 liveIds로만 생존을 판정한다`() {
        // 사건·관계변화의 code는 nullable이라 codeById에 항목이 없을 수 있다.
        val res = SnapshotRefResolver.resolveByCode(
            oldId = 3L,
            snapshotCode = null,
            codeById = emptyMap(),
            idByCode = emptyMap(),
            liveIds = setOf(3L)
        )
        assertEquals(3L, res.id)
        assertEquals(Origin.LEGACY_ID, res.origin)
    }

    // ── 필드 정의 (code가 없어 자연키로 해석) ──

    private val ref = FieldDefRef(universeCode = "U1", entityType = "character", key = "mana")
    private val natural = FieldDefNaturalKey("U1", "character", "mana")

    @Test
    fun `필드 정의도 옛 id와 자연키가 일치하면 그대로 쓴다`() {
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = ref,
            naturalById = mapOf(12L to natural),
            idByNatural = mapOf(natural to 12L)
        )
        assertEquals(12L, res.id)
        assertEquals(Origin.ID_CONFIRMED, res.origin)
    }

    @Test
    fun `세계관을 덮어써 필드 정의 id가 전부 바뀌어도 자연키로 되찾는다`() {
        // 세계관 삭제 → FK CASCADE로 필드 정의 소멸 → 백업에서 재생성되며 id가 새로 발급된 상황.
        // 이것이 N1에서 "복원해도 필드값이 하나도 살아나지 않는다"의 원인이었다.
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = ref,
            naturalById = emptyMap(),
            idByNatural = mapOf(natural to 340L)
        )
        assertEquals(340L, res.id)
        assertEquals(Origin.CODE, res.origin)
    }

    @Test
    fun `같은 key라도 세계관이 다르면 다른 필드다`() {
        val other = FieldDefNaturalKey("U2", "character", "mana")
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = ref,
            naturalById = mapOf(12L to other),
            idByNatural = mapOf(other to 12L)
        )
        assertNull(res.id)
        assertEquals(Origin.MISSING, res.origin)
    }

    @Test
    fun `같은 key라도 대상(entityType)이 다르면 다른 필드다`() {
        val eventField = FieldDefNaturalKey("U1", "event", "mana")
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = ref,
            naturalById = mapOf(12L to eventField),
            idByNatural = mapOf(eventField to 12L)
        )
        assertNull(res.id)
        assertEquals(Origin.MISSING, res.origin)
    }

    @Test
    fun `ref가 없는 구버전 payload는 id 생존만 본다`() {
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = null,
            naturalById = mapOf(12L to natural),
            idByNatural = mapOf(natural to 12L)
        )
        assertEquals(12L, res.id)
        assertEquals(Origin.LEGACY_ID, res.origin)
        assertTrue(res.isLegacyGuess)
    }

    @Test
    fun `자연키가 불완전한 ref는 근거로 쓰지 않고 구버전처럼 다룬다`() {
        // key만 있고 세계관 코드가 없으면 어느 세계관의 필드인지 좁혀지지 않는다 —
        // 근거 없이 고르지 않는다(규약 4-3: 좁혀지지 않으면 확정하지 않는다).
        val partial = FieldDefRef(universeCode = null, entityType = "character", key = "mana")
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = partial,
            naturalById = mapOf(12L to natural),
            idByNatural = mapOf(natural to 12L)
        )
        assertEquals(12L, res.id)
        assertEquals(Origin.LEGACY_ID, res.origin)
    }

    @Test
    fun `자연키가 불완전하고 옛 id도 없으면 생략한다`() {
        val partial = FieldDefRef(universeCode = "U1", entityType = null, key = "mana")
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = partial,
            naturalById = emptyMap(),
            idByNatural = mapOf(natural to 340L)
        )
        assertNull(res.id)
        assertEquals(Origin.LEGACY_MISSING, res.origin)
    }
}
