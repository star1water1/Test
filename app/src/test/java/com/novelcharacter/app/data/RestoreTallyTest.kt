package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.FieldDefNaturalKey
import com.novelcharacter.app.data.model.FieldDefRef
import com.novelcharacter.app.data.repository.RestoreTally
import com.novelcharacter.app.data.repository.SnapshotRefResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            idByNatural = emptyMap()
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
        // universeCode가 없으면 어느 세계관의 정의인지 알 수 없다 — 근거 없이 "괜찮다"고
        // 말하면 그것이야말로 사실과 다른 안내다.
        val bare = FieldDefRef(universeCode = null, entityType = "character", key = "mana")
        val tally = RestoreTally(legacy = false, pendingCodes = setOf("UNI-1"))
        val res = tally.noteFieldDef(
            SnapshotRefResolver.resolveFieldDef(12L, bare, emptyMap(), emptyMap()),
            bare
        )

        assertFalse(res.found)
        assertFalse(tally.previewOnly)
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
                idByNatural = mapOf(natural to 77L)
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

    @Test
    fun `구버전 payload는 근거가 id뿐임을 계속 알린다`() {
        // ref가 없으면 자연키가 없다 — 옛 id가 살아 있을 때만 찾고, 그 사실을 고지해야 한다.
        val tally = RestoreTally(legacy = true, pendingCodes = emptySet())
        val res = tally.noteFieldDef(
            SnapshotRefResolver.resolveFieldDef(
                oldId = 12L,
                ref = null,
                naturalById = mapOf(12L to FieldDefNaturalKey("UNI-1", "character", "mana")),
                idByNatural = emptyMap()
            ),
            null
        )

        assertEquals(12L, res.id)
        assertTrue(tally.legacyGuess)
    }
}
