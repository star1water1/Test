package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.FieldDefNaturalKey
import com.novelcharacter.app.data.model.FieldDefRef
import com.novelcharacter.app.data.repository.RestoreTally
import com.novelcharacter.app.data.repository.SnapshotRefResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 복원 계획의 **보류 판정** — "같은 작업이 곧 되살릴 대상은 유실이 아니다".
 *
 * 이 판정이 틀리면 복원 전 확인 다이얼로그가 **규모에 비례하는 거짓 경고**를 낸다.
 * 실제로 그랬다: 필드 정의는 code가 없어 `note(res, null)`로 불렸고, null 코드는
 * `pendingCodes`에 있을 수 없으므로 보류 판정이 **구조적으로 성립하지 않았다.**
 * 세계관을 지우면 그 필드 정의도 함께 사라지니, '전체 복원' 미리보기는 캐릭터·사건의
 * 필드값과 값 라이브러리 엔트리를 **전부** "필드 정의를 찾을 수 없음"으로 예고했다 —
 * 정작 복원은 세계관을 먼저 되살려 하나도 잃지 않는데도. 값이 많은 세계관일수록
 * 경고 숫자가 커져, 사용자가 볼 때는 "많은 값이 복원에서 제외된다"로 읽혔다.
 *
 * 그래서 [RestoreTally]를 저장소에서 떼어 내 실제로 실행 검증한다.
 */
class RestoreTallyTest {

    private val ref = FieldDefRef(universeCode = "UNI-1", entityType = "character", key = "mana")

    /** 세계관이 지워져 그 필드 정의를 현행 DB에서 찾을 수 없는 상태(미리보기 시점). */
    private fun missingFieldDef(): SnapshotRefResolver.Resolution =
        SnapshotRefResolver.resolveFieldDef(
            oldId = 12L,
            ref = ref,
            naturalById = emptyMap(),
            idByNatural = emptyMap(),
            liveIds = emptySet()
        )

    @Test
    fun `세계관이 같은 작업으로 복원될 예정이면 필드 정의 유실로 세지 않는다`() {
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-1"))
        val res = tally.noteFieldDef(missingFieldDef(), ref)

        // 유실이 아니다 — 복원 순서상 세계관이 먼저 살아나 정의가 다시 생긴다.
        assertTrue("보류 대상은 '찾음'으로 다뤄야 유실 집계에서 빠진다", res.found)
        assertEquals(RestoreTally.PENDING_ID, res.id)
        // 자리표시자가 섞인 계획은 절대 쓰기에 쓰면 안 된다 — apply 쪽이 이 깃발로 거부한다.
        assertTrue(tally.previewOnly)
    }

    @Test
    fun `세계관이 복원 예정에 없으면 사실대로 유실로 센다`() {
        // 거짓 경고를 없앤다고 진짜 유실까지 감추면 그게 무음 유실이다.
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-9"))
        val res = tally.noteFieldDef(missingFieldDef(), ref)

        assertFalse(res.found)
        assertNull(res.id)
        assertFalse("실제 유실은 미리보기 전용 계획을 만들지 않는다", tally.previewOnly)
    }

    @Test
    fun `자연키가 성립하지 않는 구버전 ref는 보류로 봐주지 않는다`() {
        // 세계관 코드가 빈 문자열이면 어느 세계관의 정의인지 알 수 없다 — 근거 없이 "괜찮다"고
        // 말하면 그것이야말로 사실과 다른 안내다.
        // **빈 문자열을 쓰는 이유(B-133):** 종전에는 `universeCode = null`로 이 상태를 표현했는데,
        // 이제 null은 **전역 구역**의 표기라 자연키가 성립한다. 이 시험이 재려는 것은 그쪽이
        // 아니라 *"좁혀지지 않는 ref"*이고, 그 모양은 이제 빈 문자열이다(세계관 삭제 경로가
        // `universe.code`를 빈 값 검사 없이 싣는 자리). 전역 쪽은 아래 시험이 따로 잰다.
        val bare = FieldDefRef(universeCode = "", entityType = "character", key = "mana")
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-1"))
        val res = tally.noteFieldDef(
            SnapshotRefResolver.resolveFieldDef(12L, bare, emptyMap(), emptyMap(), emptySet()),
            bare
        )

        assertFalse(res.found)
        assertFalse(tally.previewOnly)
    }

    @Test
    fun `전역 필드는 보류 대상이 아니다 — 기다릴 세계관이 없다`() {
        // 전역 구역 필드(B-119 확장)의 ref는 세계관 코드가 null이다(B-133). 그 null은
        // **전역의 표기**라 해석에서는 자연키로 승격되지만, 보류 판정에서는 반대다 —
        // 전역 필드는 어떤 세계관의 복원도 기다리지 않으므로, 지금 없으면 정말로 없다.
        // (여기서 pendingCodes를 봐주면 되살아나지 않을 값을 "괜찮다"고 예고하게 된다.)
        val globalRef = FieldDefRef(universeCode = null, entityType = "character", key = "mana")
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-1"))
        val res = tally.noteFieldDef(
            SnapshotRefResolver.resolveFieldDef(12L, globalRef, emptyMap(), emptyMap(), emptySet()),
            globalRef
        )

        assertFalse(res.found)
        assertFalse(tally.previewOnly)
        // 그리고 **거짓 경고도 아니다** — 근거는 자연키였고, 없다는 판정 자체가 사실이다.
        assertFalse("자연키로 판정했으므로 '근거가 id뿐'이 아니다", tally.legacyGuess)
    }

    @Test
    fun `entityType이 비어 자연키가 서지 않으면 세계관이 복원 예정이어도 보류가 아니다`() {
        // B-154 — **미리보기와 실제 복원이 갈리던 자리다.** 보류 판정이 `ref.universeCode`만
        // 보던 동안, entityType이 빈 ref는 그 세계관이 같은 작업에 있다는 이유로 PENDING을 받아
        // 유실 집계에서 빠졌다. 정작 복원에서는 `naturalKeyOf`가 그 ref를 떨어뜨리고
        // `buildFieldDefIndex`의 refs 루프도 건너뛰므로, 세계관이 살아나 옛 id가 죽은 뒤에는
        // LEGACY_MISSING으로 **값이 버려진다** — 예고는 '유실 없음', 결과는 유실이었다.
        val noType = FieldDefRef(universeCode = "UNI-1", entityType = null, key = "mana")
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-1"))
        val res = tally.noteFieldDef(
            SnapshotRefResolver.resolveFieldDef(12L, noType, emptyMap(), emptyMap(), emptySet()),
            noType
        )

        assertFalse("자연키가 안 서는 ref에 보류를 주면 되살아나지 않을 값을 '괜찮다'고 예고한다", res.found)
        assertNull(res.id)
        assertFalse(tally.previewOnly)
    }

    @Test
    fun `key가 비어 자연키가 서지 않을 때도 마찬가지다`() {
        // 자연키는 세 요소가 함께 있어야 선다. 셋 중 어느 하나가 빠져도 판정은 같아야 하는데,
        // **한 요소만 시험하면 다음 사람이 나머지 둘을 다르게 고쳐도 초록이다.**
        val noKey = FieldDefRef(universeCode = "UNI-1", entityType = "character", key = "")
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-1"))
        val res = tally.noteFieldDef(
            SnapshotRefResolver.resolveFieldDef(12L, noKey, emptyMap(), emptyMap(), emptySet()),
            noKey
        )

        assertFalse(res.found)
        assertFalse(tally.previewOnly)
    }

    @Test
    fun `자연키가 서면 종전대로 세계관 코드로 보류를 판정한다`() {
        // B-154가 좁힌 것이 **필요한 보류까지 좁히지는 않았는지**를 잰다. 이쪽이 무너지면
        // 고친 거짓 경고(세계관 삭제 미리보기의 규모 비례 경고)가 통째로 되돌아온다.
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-1"))
        val res = tally.noteFieldDef(missingFieldDef(), ref)

        assertTrue(res.found)
        assertEquals(RestoreTally.PENDING_ID, res.id)
    }

    @Test
    fun `필드 정의가 살아 있으면 보류 판정을 거치지 않고 그대로 해석된다`() {
        // 코드로 다시 찾은 건수(relinked)는 R-1 수정의 실효를 보여 주는 값이라 함께 고정한다.
        val natural = FieldDefNaturalKey("UNI-1", "character", "mana")
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-1"))
        val res = tally.noteFieldDef(
            SnapshotRefResolver.resolveFieldDef(
                oldId = 12L,
                ref = ref,
                naturalById = emptyMap(),
                idByNatural = mapOf(natural to 77L),
                liveIds = emptySet()
            ),
            ref
        )

        assertEquals(77L, res.id)
        assertEquals(1, tally.relinked)
        assertFalse("실제 id를 얻었으므로 미리보기 전용이 아니다", tally.previewOnly)
    }

    @Test
    fun `코드를 가진 참조의 보류 판정은 종전과 같다`() {
        // noteFieldDef를 더하면서 note의 기존 규약이 흔들리지 않았는지 함께 고정한다.
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("CHR-9"))
        val missing = SnapshotRefResolver.resolveByCode(88L, "CHR-9", emptyMap(), emptyMap(), emptySet())
        val res = tally.note(missing, "CHR-9")

        assertEquals(RestoreTally.PENDING_ID, res.id)
        assertTrue(tally.previewOnly)
    }

    // ── B-25 — 코드가 없는 참조의 보류 키 ──

    @Test
    fun `코드가 있으면 코드가 곧 보류 키다`() {
        assertEquals("EVT-1", RestoreTally.pendingKeyOf("event", 41L, "EVT-1"))
    }

    @Test
    fun `코드가 없으면 타입과 옛 id가 보류 키가 된다`() {
        // v35 이전 사건은 `TimelineEvent.code`가 null이다. 근거가 옛 id뿐이지만 **그 id도
        // 식별자다** — 복원이 비어 있는 옛 id를 그대로 되살리기 때문이다(applyEvent).
        val key = RestoreTally.pendingKeyOf("event", 41L, null)
        assertEquals(key, RestoreTally.pendingKeyOf("event", 41L, ""))
        assertEquals("빈 코드는 코드가 없는 것과 같다", key, RestoreTally.pendingKeyOf("event", 41L, "  "))
    }

    @Test
    fun `보류 키는 타입과 id로 갈린다`() {
        // 사건 41번과 캐릭터 41번은 다른 대상이다. 한 키로 뭉치면 **엉뚱한 참조가 보류를 받아**
        // 유실을 '유실 없음'으로 예고한다 — B-25가 고치려던 것의 정반대다.
        val event = RestoreTally.pendingKeyOf("event", 41L, null)
        assertNotEquals(event, RestoreTally.pendingKeyOf("character", 41L, null))
        assertNotEquals(event, RestoreTally.pendingKeyOf("event", 42L, null))
    }

    @Test
    fun `보류 키는 진짜 엔티티 코드와 겹치지 않는다`() {
        // 엔티티 코드는 16자리 hex다(generateEntityCode). 겹치면 **코드 없는 사건 하나가
        // 남의 코드 자리를 차지해** 그 참조까지 보류로 만든다.
        val key = RestoreTally.pendingKeyOf("event", 41L, null)
        assertFalse("진짜 코드가 될 수 없는 모양이어야 한다", key.matches(Regex("[0-9a-f]{16}")))
        assertTrue(key.startsWith("#"))
    }

    @Test
    fun `코드 없는 사건이 같은 작업에 있으면 유실로 세지 않는다`() {
        // B-25의 본체. 같은 작업으로 지워진 code 없는 사건을 참조하는 캐릭터가, 종전에는
        // "사건을 찾을 수 없음"으로 집계됐다 — 실제 복원은 사건이 먼저 살아나
        // (restorePriority 6 < 7) id 폴백으로 그대로 이어지는데도.
        val pendingKey = RestoreTally.pendingKeyOf("event", 41L, null)
        val tally = RestoreTally(legacy = false, pendingCodes = setOf(pendingKey))
        // 스냅샷에 코드가 없고 옛 id도 살아 있지 않다 → LEGACY_MISSING
        val missing = SnapshotRefResolver.resolveByCode(41L, null, emptyMap(), emptyMap(), emptySet())
        assertFalse("전제 확인 — 해석 자체는 못 찾는다", missing.found)

        val res = tally.note(missing, pendingKey)

        assertTrue("같은 작업이 되살릴 대상이다", res.found)
        assertEquals(RestoreTally.PENDING_ID, res.id)
        assertTrue("자리표시자가 섞였으므로 미리보기 전용이다", tally.previewOnly)
    }

    @Test
    fun `구버전 참조자는 대상에 코드가 있어도 옛 id로 묻는다 — 그래서 둘 다 심는다`() {
        // **어느 키로 묻는가는 참조하는 쪽이 정한다.** payload에 refs가 통째로 없는 구버전
        // 참조자는 대상의 코드를 알 방법이 없어 **옛 id로만** 가리킨다. 그래서 심는 쪽
        // (`collectOperationCodes`)이 코드만 심으면, 대상이 같은 작업에 멀쩡히 있는데도
        // 이 참조는 못 찾아 **거짓 경고가 그대로 남는다** — 마무리 콜드 검토가 잡은 자리다.
        val asked = RestoreTally.pendingKeyOf("event", 41L, null)
        val codeOnly = RestoreTally.pendingKeyOf("event", 41L, "EVT-1")
        assertNotEquals("코드만 심으면 구버전 참조자의 물음과 만나지 않는다", asked, codeOnly)

        // 둘 다 심은 상태 — 구버전 참조자도 보류를 받는다.
        val tally = RestoreTally(legacy = true, pendingCodes = setOf(codeOnly, asked))
        val missing = SnapshotRefResolver.resolveByCode(41L, null, emptyMap(), emptyMap(), emptySet())
        assertTrue(tally.note(missing, asked).found)
    }

    @Test
    fun `같은 작업에 없는 코드 없는 사건은 사실대로 유실로 센다`() {
        // 거짓 경고를 없앤다고 진짜 유실까지 감추면 그것이 무음 유실이다 — B-25의 경계선.
        val tally = RestoreTally(legacy = false, pendingCodes = setOf(RestoreTally.pendingKeyOf("event", 99L, null)))
        val missing = SnapshotRefResolver.resolveByCode(41L, null, emptyMap(), emptyMap(), emptySet())

        val res = tally.note(missing, RestoreTally.pendingKeyOf("event", 41L, null))

        assertFalse(res.found)
        assertNull(res.id)
        assertFalse(tally.previewOnly)
    }

    @Test
    fun `구버전 payload는 근거가 id뿐임을 계속 알린다`() {
        // ref가 없으면 자연키가 없다 — 옛 id가 살아 있을 때만 찾고, 그 사실을 고지해야 한다.
        val tally = RestoreTally(legacy = true, pendingCodes = emptySet())
        val res = tally.noteFieldDef(
            SnapshotRefResolver.resolveFieldDef(
                oldId = 12L,
                ref = null,
                naturalById = mapOf(12L to FieldDefNaturalKey("UNI-1", "character", "mana")),
                idByNatural = emptyMap(),
                liveIds = setOf(12L)
            ),
            null
        )

        assertEquals(12L, res.id)
        assertTrue(tally.legacyGuess)
    }
}
