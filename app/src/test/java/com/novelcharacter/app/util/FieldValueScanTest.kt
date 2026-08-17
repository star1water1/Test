package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FieldValueScan] · [FieldValueOverlay] — 필드값 범주의 계수 (B-187).
 *
 * **여기가 잠그는 것은 숫자가 아니라 성질 셋이다:**
 * ⓐ 항등식 `inBackup = new + update + unchanged + cleared + skipped`
 * ⓑ 파일 안에서 **뒤 행이 앞 행의 쓰기를 본다**(R-33 ⑦ · B-233이 캐릭터에서 겪은 그 갈림)
 * ⓒ 본 시트가 처리한 쌍을 오버플로 시트가 **다시 세지 않는다**(가져오기가 그 행을 무시한다)
 */
class FieldValueScanTest {

    private fun overlay(vararg rows: Triple<Long, Long, String>) =
        FieldValueOverlay.of(rows.toList()) { it }

    private fun scan(vararg rows: Triple<Long, Long, String>) = FieldValueScan(overlay(*rows))

    /** 항등식 — 어떤 순서로 세든 성립해야 한다. */
    private fun assertIdentity(s: FieldValueScan) = assertEquals(
        "inBackup = new + update + unchanged + cleared + skipped",
        s.inBackup,
        s.newCount + s.updateCount + s.unchangedCount + s.clearedCount + s.skippedCount
    )

    // ── ① 다섯 처분이 제 칸에 들어간다 ───────────────────────────────────────────

    @Test
    fun `신규 변경 동일 비움이 각자 세어지고 항등식이 선다`() {
        val s = scan(
            Triple(1L, 10L, "옛 값"),   // 변경 대상
            Triple(1L, 11L, "그대로"),  // 동일 대상
            Triple(1L, 12L, "지울 값")  // 비움 대상
        )
        s.count(1L, 9L, "새 값")     // 신규
        s.count(1L, 10L, "새 값")    // 변경
        s.count(1L, 11L, "그대로")   // 동일
        s.count(1L, 12L, "")         // 비움

        assertEquals(1, s.newCount)
        assertEquals(1, s.updateCount)
        assertEquals(1, s.unchangedCount)
        assertEquals(1, s.clearedCount)
        assertEquals(4, s.inBackup)
        assertIdentity(s)
    }

    /**
     * **'없음'은 세지 않는다** — 셀도 비었고 지울 것도 없는 자리다. 세면 정상 파일에서도
     * '백업에 N건'이 부풀어, 사용자가 파일과 견줄 수 없다(R-33 ⑥의 같은 근거).
     */
    @Test
    fun `빈 칸에 지울 것도 없으면 백업 건수에 들어가지 않는다`() {
        val s = scan()
        s.count(1L, 9L, "")
        s.count(1L, 10L, "   ")
        assertEquals(0, s.inBackup)
        assertIdentity(s)
    }

    @Test
    fun `건너뜀도 백업 건수에 함께 든다`() {
        val s = scan(Triple(1L, 10L, "값"))
        s.count(1L, 10L, "값")
        s.skip()
        s.skip()
        assertEquals(3, s.inBackup)
        assertEquals(2, s.skippedCount)
        assertIdentity(s)
    }

    // ── ② 뒤 행이 앞 행의 쓰기를 본다 ────────────────────────────────────────────
    // 가져오기는 넣은 값을 곧바로 다시 읽으므로, 같은 (소유자, 필드)를 두 번 적은 파일은
    // *신규 1 + 변경 1*이 된다. 겹을 갱신하지 않으면 미리보기는 **신규 2**라 말한다.

    @Test
    fun `같은 칸을 두 번 적은 파일은 신규 하나에 변경 하나다`() {
        val s = scan()
        s.count(1L, 9L, "첫 값")
        s.count(1L, 9L, "둘째 값")
        assertEquals(1, s.newCount)
        assertEquals(1, s.updateCount)
        assertEquals(0, s.unchangedCount)
        assertIdentity(s)
    }

    @Test
    fun `앞 행이 쓴 값과 같은 값을 뒤 행이 적으면 동일이다`() {
        val s = scan()
        s.count(1L, 9L, "같은 값")
        s.count(1L, 9L, "같은 값")
        assertEquals(1, s.newCount)
        assertEquals(1, s.unchangedCount)
        assertEquals(0, s.updateCount)
    }

    /** 비운 뒤 다시 적으면 **신규**다 — 지운 것을 겹이 기억하지 못하면 '변경'이라 말한다. */
    @Test
    fun `비운 칸에 뒤 행이 값을 적으면 신규다`() {
        val s = scan(Triple(1L, 9L, "옛 값"))
        s.count(1L, 9L, "")          // 비움
        s.count(1L, 9L, "새 값")     // 신규 — 지금은 아무 값도 없다
        assertEquals(1, s.clearedCount)
        assertEquals(1, s.newCount)
        assertEquals(0, s.updateCount)
        assertIdentity(s)
    }

    // ── ③ 소유자끼리 섞이지 않는다 ───────────────────────────────────────────────
    // 필드 id는 소유자 사이에서 공유되므로(같은 필드 정의를 여럿이 쓴다), 키가 필드만이면
    // 한 캐릭터의 값이 다른 캐릭터의 답이 되고 **두 답 모두 그럴듯해 보인다.**

    @Test
    fun `같은 필드라도 소유자가 다르면 다른 값이다`() {
        val s = scan(Triple(1L, 9L, "붉은색"))
        s.count(2L, 9L, "붉은색")
        assertEquals(1, s.newCount)
        assertEquals(0, s.unchangedCount)
    }

    /** 미리보기가 만들 소유자는 음수 id라 어떤 기존 값과도 짝지어지지 않는다. */
    @Test
    fun `미리보기가 만들 소유자의 칸은 전부 신규다`() {
        val s = scan(Triple(1L, 9L, "붉은색"))
        s.count(-2L, 9L, "붉은색")
        assertEquals(1, s.newCount)
        assertEquals(0, s.unchangedCount)
    }

    // ── ④ 본 시트가 처리한 쌍은 오버플로 시트가 다시 세지 않는다 ─────────────────

    @Test
    fun `센 칸은 소유 표시가 남고 안 센 칸은 남지 않는다`() {
        val s = scan()
        s.count(1L, 9L, "값")
        assertTrue(s.isOwned(1L, 9L))
        assertFalse(s.isOwned(1L, 10L))
        assertFalse(s.isOwned(2L, 9L))
    }

    /** **값이 없는 칸도 소유다** — 가져오기의 '본 시트 우선'은 값 유무가 아니라 열로 정해진다. */
    @Test
    fun `빈 칸이라도 본 시트가 다뤘으면 소유로 표시된다`() {
        val s = scan()
        s.count(1L, 9L, "")
        assertEquals(0, s.inBackup)
        assertTrue(s.isOwned(1L, 9L))
    }

    // ── ⑤ 현재 DB 총계는 겹을 실을 때 이미 센 것이다 ─────────────────────────────
    // 같은 읽기가 색인과 총계 둘 다를 먹인다(B-236의 처방) — 따로 물으면 그 사이에 값이
    // 바뀌어 '현재 DB'와 '백업에 없음'이 서로 맞지 않게 된다.

    @Test
    fun `현재 DB 총계는 실은 행 수이고 계수로 바뀌지 않는다`() {
        val s = scan(Triple(1L, 9L, "가"), Triple(1L, 10L, "나"), Triple(2L, 9L, "다"))
        assertEquals(3, s.existingTotal)
        s.count(3L, 9L, "라")
        s.count(1L, 9L, "")
        assertEquals(3, s.existingTotal)
    }

    // ── ⑥ 겹 자체의 성질 ─────────────────────────────────────────────────────────

    @Test
    fun `겹은 없는 값에 null을 답하고 지운 값도 null이 된다`() {
        val o = overlay(Triple(1L, 9L, "값"))
        assertEquals("값", o.get(1L, 9L))
        o.remove(1L, 9L)
        assertEquals(null, o.get(1L, 9L))
        assertEquals(null, o.get(1L, 99L))
    }

    /** 같은 키가 두 번 실리면 **뒤엣것이 남는다** — 표에는 유니크 색인이 있어 실제로는 없는 일이다. */
    @Test
    fun `겹은 같은 키의 마지막 행을 든다`() {
        val o = overlay(Triple(1L, 9L, "먼저"), Triple(1L, 9L, "나중"))
        assertEquals("나중", o.get(1L, 9L))
        assertEquals(2, o.loadedRows)
    }
}
