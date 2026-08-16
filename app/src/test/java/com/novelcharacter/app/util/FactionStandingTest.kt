package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FactionMembership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * '지금 소속'의 단일 소스 (B-171).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 넷이고, 앞의 둘은 서로 반대 방향의 사고를 막는다.**
 *
 * 1. **판정은 [FactionMembership.leaveType]이 한다 — 연도가 아니다.** 대결에는 연도 축이
 *    없는데 이 앱의 시간 축은 표준 연도라, 다음 사람이 *"소속인데 연도를 안 보는 게 이상하다"*고
 *    [FactionMembership.isActiveAtYear]로 위임하고 싶어지는 자리다. 그러면 **가입 연도를
 *    적어 둔 멤버가 그 해 이전 시점에서 통째로 빠지고**(통계·세력 관리가 함께 움직인다)
 *    *지금*이 무엇인지는 여전히 아무도 못 정한다.
 * 2. **그런데 연도가 있다고 지금이 아닌 것도 아니다.** (**반대편이다** — 1만 있으면
 *    *"leaveYear가 있으면 끝난 것"*이라는 구현이 통과하는데, 그러면 **가입 연도만 적은 행**과
 *    **엑셀에서 탈퇴 연도만 적힌 행**이 한 규칙에 묶여 착수 이전과 다른 수가 나온다.
 *    그 모순의 처분은 이 슬라이스가 정하지 않기로 했고(B-206), **정하지 않았다는 사실 자체를
 *    시험이 잠근다** — 누가 조용히 정해 버리면 여기서 먼저 막힌다.)
 * 3. **겸직은 정상이고 재가입은 한 세력이다.** 소속 표에 unique 제약이 없는 것이 그 근거다.
 *    [FactionStanding.currentFactionIds]가 중복을 안 걷어내면 카드가 같은 세력을 두 번 띄우고,
 *    거꾸로 세력별로 뭉치면 겸직이 하나로 접힌다.
 * 4. **차례는 넘긴 그대로다.** 부르는 쪽이 `createdAt`으로 정렬해 넘기는데 여기서 다시 줄을
 *    세우면 *같은 캐릭터의 세력 목록이 화면마다 다른 차례*로 뜬다.
 */
class FactionStandingTest {

    private fun membership(
        id: Long,
        factionId: Long,
        characterId: Long = 1L,
        joinYear: Int? = null,
        leaveYear: Int? = null,
        leaveType: String? = null
    ) = FactionMembership(
        id = id,
        factionId = factionId,
        characterId = characterId,
        joinYear = joinYear,
        leaveYear = leaveYear,
        leaveType = leaveType,
        createdAt = id
    )

    // ── 1. 판정은 상태 표식이 한다 ──

    @Test
    fun `표식이 없으면 지금 소속이다`() {
        assertTrue(FactionStanding.isCurrent(membership(1, 10)))
    }

    @Test
    fun `순수 제거와 설정상 탈퇴는 지금 소속이 아니다`() {
        assertFalse(
            FactionStanding.isCurrent(
                membership(1, 10, leaveYear = 1200, leaveType = FactionMembership.LEAVE_REMOVED)
            )
        )
        assertFalse(
            FactionStanding.isCurrent(
                membership(2, 10, leaveYear = 1200, leaveType = FactionMembership.LEAVE_DEPARTED)
            )
        )
    }

    @Test
    fun `가입 연도를 적어 두어도 지금 소속이다 — 연도로 판정하지 않는다`() {
        // isActiveAtYear 로 위임한 구현은 여기서 죽는다: 그쪽은 기준 연도를 요구하고,
        // 대결에는 줄 연도가 없다.
        assertTrue(FactionStanding.isCurrent(membership(1, 10, joinYear = 1300)))
    }

    @Test
    fun `연도 판정은 다른 물음이라는 것을 나란히 잰다`() {
        val m = membership(1, 10, joinYear = 1300)
        // 같은 행이 '지금 소속'이면서 '1299년에는 아니었다' — 둘은 모순이 아니라 다른 물음이다.
        assertTrue(FactionStanding.isCurrent(m))
        assertFalse(m.isActiveAtYear(1299))
        assertTrue(m.isActiveAtYear(1300))
    }

    // ── 2. 반대편: 연도만으로 끝났다고 하지 않는다 ──

    @Test
    fun `탈퇴 연도만 적히고 표식이 없는 행은 지금 소속이다`() {
        // 앱 안에서는 만들어지지 않고 엑셀 손편집으로만 생기는 조합이다(B-206).
        // **여기서 처분을 바꾸면 통계·세력 관리·AI 문맥이 함께 움직인다** — B-206은 그래서
        // 읽는 쪽을 그대로 두고 **들어오는 쪽**에서 고치기로 확정됐다(확정 15장 7번 ⓒ).
        // 이 시험은 여전히 *읽는 쪽이 안 움직였다*를 잠근다 — 이미 DB에 든 행의 수가
        // 조용히 달라지면 여기서 먼저 막힌다.
        assertTrue(FactionStanding.isCurrent(membership(1, 10, leaveYear = 1200)))
    }

    // ── 2-a. 들어오는 자리의 교정 (B-206 · 확정 15장 7번 ⓒ) ──

    @Test
    fun `탈퇴연도만 온 행은 설정상탈퇴로 채운다`() {
        assertEquals(
            FactionMembership.LEAVE_DEPARTED,
            FactionStanding.leaveTypeForImportedRow(1200, null, leaveTypeColumnPresent = true)
        )
    }

    @Test
    fun `적혀 있는 표식은 덮지 않는다`() {
        // 순수제거로 적은 행을 설정상탈퇴로 바꾸면 사용자가 적은 것을 앱이 뒤집는 것이다.
        assertEquals(
            FactionMembership.LEAVE_REMOVED,
            FactionStanding.leaveTypeForImportedRow(1200, FactionMembership.LEAVE_REMOVED, leaveTypeColumnPresent = true)
        )
    }

    @Test
    fun `탈퇴연도가 없으면 채우지 않는다`() {
        // 가입 연도만 적힌 평범한 활성 행이 여기 걸리면 **살아 있는 소속이 통째로 탈퇴가 된다**.
        assertNull(FactionStanding.leaveTypeForImportedRow(null, null, leaveTypeColumnPresent = true))
    }

    @Test
    fun `탈퇴유형 열 자체가 없으면 채우지 않는다`() {
        // 열 없음은 "비웠다"가 아니라 "말하지 않았다"다(F1-A) — 채우면 그 열이 없던 시절에
        // 내보낸 파일을 다시 들이는 것만으로 활성 소속이 탈퇴로 바뀐다.
        assertNull(FactionStanding.leaveTypeForImportedRow(1200, null, leaveTypeColumnPresent = false))
    }

    // ── 3. 겸직·재가입 ──

    @Test
    fun `겸직은 세력이 여럿이고 재가입은 하나다`() {
        val rows = listOf(
            membership(1, factionId = 10),                                              // 기사단
            membership(2, factionId = 20),                                              // 상단(겸직)
            membership(3, factionId = 10, leaveYear = 1100, leaveType = FactionMembership.LEAVE_DEPARTED),
            membership(4, factionId = 10)                                               // 기사단 재가입
        )
        assertEquals(listOf(10L, 20L), FactionStanding.currentFactionIds(rows))
        assertEquals(3, FactionStanding.countCurrent(rows))
        assertTrue(FactionStanding.hasCurrent(rows))
    }

    @Test
    fun `전부 끝났으면 지금 소속이 없다`() {
        val rows = listOf(
            membership(1, 10, leaveYear = 1100, leaveType = FactionMembership.LEAVE_DEPARTED),
            membership(2, 20, leaveYear = 1150, leaveType = FactionMembership.LEAVE_REMOVED)
        )
        assertFalse(FactionStanding.hasCurrent(rows))
        assertEquals(0, FactionStanding.countCurrent(rows))
        assertEquals(emptyList<Long>(), FactionStanding.currentFactionIds(rows))
    }

    @Test
    fun `빈 목록은 조용히 빈 답이다`() {
        assertFalse(FactionStanding.hasCurrent(emptyList()))
        assertEquals(emptyList<Long>(), FactionStanding.currentFactionIds(emptyList()))
    }

    // ── 4. 차례 ──

    @Test
    fun `차례는 넘긴 그대로다`() {
        val rows = listOf(membership(1, 30), membership(2, 10), membership(3, 20))
        assertEquals(listOf(30L, 10L, 20L), FactionStanding.currentFactionIds(rows))
        assertEquals(listOf(1L, 2L, 3L), FactionStanding.current(rows).map { it.id })
    }

    // ── SQL 쌍둥이 ──

    @Test
    fun `SQL 쌍둥이의 글자가 DAO가 쓰는 그것이다`() {
        // 값을 시험에 박아 두는 것은 **여기가 세 번째 자리이기 때문이 아니라**, 술어를 바꾸면
        // DAO·검사·시험 셋이 함께 울어야 하기 때문이다. 하나만 고쳐서는 초록이 되지 않는다.
        assertEquals("leaveType IS NULL", FactionStanding.SQL_CURRENT)
    }
}
