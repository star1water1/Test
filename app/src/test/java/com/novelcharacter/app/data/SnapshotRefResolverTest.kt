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
            idByNatural = mapOf(natural to 12L),
            liveIds = setOf(12L)
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
            idByNatural = mapOf(natural to 340L),
            liveIds = emptySet()
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
            idByNatural = mapOf(other to 12L),
            liveIds = setOf(12L)
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
            idByNatural = mapOf(eventField to 12L),
            liveIds = setOf(12L)
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
            idByNatural = mapOf(natural to 12L),
            liveIds = setOf(12L)
        )
        assertEquals(12L, res.id)
        assertEquals(Origin.LEGACY_ID, res.origin)
        assertTrue(res.isLegacyGuess)
    }

    @Test
    fun `자연키가 불완전하고 옛 id도 없으면 생략한다`() {
        val partial = FieldDefRef(universeCode = "U1", entityType = null, key = "mana")
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = partial,
            naturalById = emptyMap(),
            idByNatural = mapOf(natural to 340L),
            liveIds = emptySet()
        )
        assertNull(res.id)
        assertEquals(Origin.LEGACY_MISSING, res.origin)
    }

    // ── 전역 구역 필드 (B-119 확장 · B-133) ──
    //
    // 전역 필드는 `universeId IS NULL`이라 세계관 코드가 없다. 쓰기(`fieldDefRef`)와
    // 색인(`buildFieldDefIndex`)은 그 짝을 이미 알고 `universeCode = null`로 주고받는데,
    // 해석만 그것을 "코드가 없다 = 좁혀지지 않는다"로 읽어 근거에서 떨어뜨리고 있었다.
    // **null은 전역의 표기이지 유실이 아니다** — 유실이면 ref 자체가 만들어지지 않는다.

    private val globalRef = FieldDefRef(universeCode = null, entityType = "character", key = "mana")
    private val globalNatural = FieldDefNaturalKey(null, "character", "mana")

    @Test
    fun `전역 필드는 세계관 코드 없이도 자연키가 성립한다`() {
        // 종전에는 이 자리가 LEGACY_ID였다 — 대상이 멀쩡히 확인되는데도 '근거가 id뿐'이라
        // 표시돼 정상 복원에 거짓 경고가 붙었다(7장: 사실과 다른 경고는 무음보다 나쁘다).
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = globalRef,
            naturalById = mapOf(12L to globalNatural),
            idByNatural = mapOf(globalNatural to 12L),
            liveIds = setOf(12L)
        )
        assertEquals(12L, res.id)
        assertEquals(Origin.ID_CONFIRMED, res.origin)
        assertFalse(res.isLegacyGuess)
    }

    @Test
    fun `전역 필드 id가 재발급돼도 자연키로 되찾는다`() {
        // 템플릿 삭제 → 재생성 → 다시 심기를 거치면 전역 그림자가 새 id를 받는다.
        // 종전에는 자연키를 아예 보지 않아 **값이 전량 생략**됐다(옛 id가 죽었으므로).
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = globalRef,
            naturalById = emptyMap(),
            idByNatural = mapOf(globalNatural to 340L),
            liveIds = emptySet()
        )
        assertEquals(340L, res.id)
        assertEquals(Origin.CODE, res.origin)
    }

    @Test
    fun `전역 필드와 같은 key의 세계관 필드는 서로 다른 필드다`() {
        // 전역이 `null` 자리를 쓰므로 세계관 필드와 절대 겹치지 않아야 한다 —
        // 겹치면 전역 값이 남의 세계관 필드에 붙는다(오배정 > 생략).
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = globalRef,
            naturalById = mapOf(12L to natural),
            idByNatural = mapOf(natural to 12L),
            liveIds = setOf(12L)
        )
        assertNull(res.id)
        assertEquals(Origin.MISSING, res.origin)
    }

    @Test
    fun `세계관 필드 ref는 전역 필드로 떨어지지 않는다`() {
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = ref,
            naturalById = mapOf(12L to globalNatural),
            idByNatural = mapOf(globalNatural to 12L),
            liveIds = setOf(12L)
        )
        assertNull(res.id)
        assertEquals(Origin.MISSING, res.origin)
    }

    @Test
    fun `세계관 코드가 빈 문자열인 ref는 전역이 아니라 구버전처럼 다룬다`() {
        // 빈 문자열은 **세계관 소속인데 코드를 못 얻은 것**이다(세계관 삭제 경로가
        // `universe.code`를 그대로 싣는다). 전역(null)으로 승격하면 남의 값을 전역 필드에
        // 붙이게 되므로, 좁혀지지 않는 것은 종전대로 근거에서 뺀다(규약 4-3).
        val blank = FieldDefRef(universeCode = "", entityType = "character", key = "mana")
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = blank,
            naturalById = mapOf(12L to globalNatural),
            idByNatural = mapOf(globalNatural to 99L),
            liveIds = setOf(12L)
        )
        assertEquals(12L, res.id)
        assertEquals(Origin.LEGACY_ID, res.origin)
        assertTrue(res.isLegacyGuess)
    }

    // ── 자연키 판정의 단일 소스 (B-133) ──
    //
    // 해석과 색인 만들기(`TrashRepository.buildFieldDefIndex`)가 같은 질문을 각자 적고 있었다.
    // 갈리면 **해석이 찾을 자리를 색인이 안 채우거나(값 유실), 색인이 엉뚱한 자리를 채운다
    // (오배정).** 그래서 판정을 공개 함수 하나로 모았고, 그 계약을 여기서 직접 잰다 —
    // `resolveFieldDef` 너머로만 재면 색인 쪽이 규칙을 어겨도 이 시험이 아무 말을 하지 않는다.

    @Test
    fun `자연키 판정 — 전역은 성립하고 빈 코드는 성립하지 않는다`() {
        assertEquals(
            globalNatural,
            SnapshotRefResolver.naturalKeyOf(globalRef)
        )
        assertEquals(
            natural,
            SnapshotRefResolver.naturalKeyOf(ref)
        )
        assertNull(
            "빈 세계관 코드는 좁혀지지 않는다 — 전역으로 승격하면 오배정이다",
            SnapshotRefResolver.naturalKeyOf(
                FieldDefRef(universeCode = "", entityType = "character", key = "mana")
            )
        )
        assertNull(
            SnapshotRefResolver.naturalKeyOf(
                FieldDefRef(universeCode = null, entityType = null, key = "mana")
            )
        )
        assertNull(
            SnapshotRefResolver.naturalKeyOf(
                FieldDefRef(universeCode = null, entityType = "character", key = "")
            )
        )
    }

    @Test
    fun `DB 행의 자연키도 같은 규칙을 쓴다 — 전역만 null 자리를 갖는다`() {
        // 색인 쪽(`buildFieldDefIndex`)이 이 규칙을 어겨도 순수 JVM 시험이 원리적으로 못 본다 —
        // 그 함수는 Room에 매달려 있다. 그래서 판정을 ref 쪽 옆에 두고 여기서 직접 잰다.
        assertEquals(
            globalNatural,
            SnapshotRefResolver.naturalKeyOfRow(null, null, "character", "mana")
        )
        assertEquals(
            "전역 행은 우연히 딸려 온 세계관 코드를 무시한다",
            globalNatural,
            SnapshotRefResolver.naturalKeyOfRow(null, "U1", "character", "mana")
        )
        assertEquals(
            natural,
            SnapshotRefResolver.naturalKeyOfRow(7L, "U1", "character", "mana")
        )
        assertNull(
            "세계관 소속인데 코드를 못 얻으면 자연키가 없다 — null을 주면 전역과 겹친다",
            SnapshotRefResolver.naturalKeyOfRow(7L, null, "character", "mana")
        )
        assertNull(
            SnapshotRefResolver.naturalKeyOfRow(7L, "", "character", "mana")
        )
    }

    @Test
    fun `ref 쪽과 DB 행 쪽의 자연키가 같은 대상에서 맞물린다`() {
        // 두 쪽이 갈리면 해석이 찾을 자리를 색인이 안 채우거나(값 유실) 엉뚱한 자리를
        // 채운다(오배정). 같은 대상을 넣어 **같은 키가 나오는지**를 직접 잰다 —
        // 각자 따로 재면 둘이 함께 어긋나도 통과한다.
        assertEquals(
            SnapshotRefResolver.naturalKeyOf(globalRef),
            SnapshotRefResolver.naturalKeyOfRow(null, null, "character", "mana")
        )
        assertEquals(
            SnapshotRefResolver.naturalKeyOf(ref),
            SnapshotRefResolver.naturalKeyOfRow(7L, "U1", "character", "mana")
        )
    }

    @Test
    fun `자연키가 불완전해도 살아 있는 id는 자연키 없이 확인한다`() {
        // `liveIds`가 `naturalById`와 갈린 이유 — 자연키를 못 만드는 행(세계관 코드가
        // 비었다)도 **살아 있기는 하다.** 종전처럼 색인 하나로 겸하면, 그 행을 색인에서
        // 빼는 순간 구버전 payload가 멀쩡한 대상을 '없음'으로 보고 값을 버린다.
        val partial = FieldDefRef(universeCode = null, entityType = null, key = "mana")
        val res = SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = partial,
            naturalById = emptyMap(),
            idByNatural = emptyMap(),
            liveIds = setOf(12L)
        )
        assertEquals(12L, res.id)
        assertEquals(Origin.LEGACY_ID, res.origin)
    }
}
