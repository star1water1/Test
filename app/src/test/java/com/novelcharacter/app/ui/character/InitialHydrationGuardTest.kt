package com.novelcharacter.app.ui.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InitialHydrationGuard] — 초기 적재가 사용자가 이미 적은 것을 덮지 못하게 막는 판정기 (B-268).
 *
 * **이 시험이 대신 보는 것.** 결함 자체는 화면에서 났다 — 편집 창을 열자마자 적으면 DB 적재가
 * 그것을 덮었고, 뒤이어 더티 표식까지 꺼져 **뒤로가기 확인창도 드래프트 기록도 안 남았다.**
 * 판정에 필요한 재료가 위젯이 아니라 *국면 · 만져진 칸 · 쓰려는 칸* 셋뿐이라 그 판정을 화면 밖으로
 * 꺼냈고, 여기서 전수로 본다. 화면 배선(어느 위젯이 어느 키를 쓰는가)은 여전히 기기의 몫이다.
 */
class InitialHydrationGuardTest {

    // ── 결함 재현 — 이 셋이 B-268의 실제 경로다 ────────────────────────────

    @Test
    fun 적재_전에_적은_칸은_적재가_덮지_못한다() {
        val g = InitialHydrationGuard()
        g.onFieldChanged(InitialHydrationGuard.KEY_NAME)   // 사용자가 이름을 적기 시작

        g.beginHydration()                                  // DB 값이 돌아왔다
        assertFalse("적재 전에 만진 칸은 덮으면 안 된다", g.mayWrite(InitialHydrationGuard.KEY_NAME))
        assertTrue("안 만진 칸은 DB 값으로 채워야 한다", g.mayWrite(InitialHydrationGuard.KEY_MEMO))
    }

    @Test
    fun 적재_전_편집이_있으면_더티를_끄지_않는다() {
        val g = InitialHydrationGuard()
        g.onFieldChanged(InitialHydrationGuard.KEY_NAME)
        g.beginHydration()
        g.endHydration()
        // `loadExistingCharacter` 끝의 `hasUnsavedChanges = false`를 막는 판정.
        // 여기가 false로 돌아오면 지켜 낸 입력이 "저장할 것 없음"이 되어 유실이 조용해진다.
        assertTrue(g.hasEarlyEdits())
    }

    @Test
    fun 적재_전_편집이_없으면_더티를_끈다() {
        val g = InitialHydrationGuard()
        g.beginHydration()
        g.onFieldChanged(InitialHydrationGuard.KEY_NAME)    // fillForm의 프로그램적 setText
        g.onFieldChanged(InitialHydrationGuard.KEY_MEMO)
        g.endHydration()
        assertFalse("적재가 쓴 것은 미저장 입력이 아니다", g.hasEarlyEdits())
    }

    // ── 국면별 워처 판정 ────────────────────────────────────────────────────

    @Test
    fun 적재_전_워처는_사용자_편집이다() {
        val g = InitialHydrationGuard()
        assertTrue(g.onFieldChanged(InitialHydrationGuard.KEY_NAME))
    }

    @Test
    fun 적재_중_워처는_사용자_편집이_아니다() {
        val g = InitialHydrationGuard()
        g.beginHydration()
        assertFalse("적재의 setText로 더티가 서면 열기만 해도 미저장이 된다", g.onFieldChanged(InitialHydrationGuard.KEY_NAME))
    }

    @Test
    fun 적재_뒤_워처는_다시_사용자_편집이다() {
        val g = InitialHydrationGuard()
        g.beginHydration()
        g.endHydration()
        assertTrue(g.onFieldChanged(InitialHydrationGuard.KEY_MEMO))
    }

    @Test
    fun 적재_중의_워처는_만진_칸으로_적히지_않는다() {
        val g = InitialHydrationGuard()
        g.beginHydration()
        g.onFieldChanged(InitialHydrationGuard.KEY_NAME)
        assertTrue("적재가 쓴 칸을 '만졌다'로 적으면 다음 칸부터 적재가 스스로를 막는다", g.mayWrite(InitialHydrationGuard.KEY_NAME))
        assertEquals(emptyList<String>(), g.earlyEditedKeys())
    }

    @Test
    fun 적재_뒤의_편집은_만진_칸으로_적히지_않는다() {
        val g = InitialHydrationGuard()
        g.beginHydration()
        g.endHydration()
        g.onFieldChanged(InitialHydrationGuard.KEY_MEMO)
        assertFalse(g.hasEarlyEdits())
    }

    // ── 칸 단위 판정 — 통째로 건너뛰지 않는다 ───────────────────────────────

    @Test
    fun 만진_칸만_거절하고_나머지는_전부_허용한다() {
        val g = InitialHydrationGuard()
        g.onFieldChanged(InitialHydrationGuard.KEY_NAME)
        g.onFieldChanged(InitialHydrationGuard.KEY_TAGS)
        g.beginHydration()

        assertFalse(g.mayWrite(InitialHydrationGuard.KEY_NAME))
        assertFalse(g.mayWrite(InitialHydrationGuard.KEY_TAGS))
        // 나머지 여섯은 DB 값으로 채워야 한다 — 통째로 건너뛰면 빈 폼이 남는다
        listOf(
            InitialHydrationGuard.KEY_FIRST_NAME,
            InitialHydrationGuard.KEY_LAST_NAME,
            InitialHydrationGuard.KEY_ANOTHER_NAME,
            InitialHydrationGuard.KEY_MEMO,
            InitialHydrationGuard.KEY_NOVEL,
            InitialHydrationGuard.KEY_IMAGES
        ).forEach { assertTrue("$it 은 안 만졌으므로 채워야 한다", g.mayWrite(it)) }
    }

    @Test
    fun 텍스트가_아닌_칸도_같은_규칙을_받는다() {
        // 스피너·이미지는 워처가 없어 조작 자리에서 직접 알린다. 덮이는 성질은 텍스트와 같다.
        val g = InitialHydrationGuard()
        g.onFieldChanged(InitialHydrationGuard.KEY_NOVEL)
        g.onFieldChanged(InitialHydrationGuard.KEY_IMAGES)
        g.beginHydration()
        assertFalse(g.mayWrite(InitialHydrationGuard.KEY_NOVEL))
        assertFalse(g.mayWrite(InitialHydrationGuard.KEY_IMAGES))
    }

    @Test
    fun 같은_칸을_여러_번_만져도_한_번만_적힌다() {
        val g = InitialHydrationGuard()
        repeat(5) { g.onFieldChanged(InitialHydrationGuard.KEY_NAME) }
        assertEquals(listOf(InitialHydrationGuard.KEY_NAME), g.earlyEditedKeys())
    }

    @Test
    fun 만진_순서가_보존된다() {
        val g = InitialHydrationGuard()
        g.onFieldChanged(InitialHydrationGuard.KEY_MEMO)
        g.onFieldChanged(InitialHydrationGuard.KEY_NAME)
        g.onFieldChanged(InitialHydrationGuard.KEY_MEMO)
        assertEquals(
            listOf(InitialHydrationGuard.KEY_MEMO, InitialHydrationGuard.KEY_NAME),
            g.earlyEditedKeys()
        )
    }

    @Test
    fun 적재_전의_프로그램적_쓰기를_알리면_적재가_스스로_막힌다() {
        // **이 시험은 계약을 못박는 자리다** — 판정기는 *누가* 썼는지 모른다. 적재 전에
        // 화면이 스스로 쓰는 경로(사건 편집의 역법 시드가 그 부류다)가 이 자리를 부르면
        // 그 칸은 '만져진 것'이 되고, 뒤따르는 진짜 적재가 자기 칸을 못 채운다.
        // 그러므로 부르는 쪽이 프로그램적 쓰기를 걸러야 한다.
        val g = InitialHydrationGuard()
        g.onFieldChanged(InitialHydrationGuard.KEY_CALENDAR)   // ← 화면이 스스로 쓴 것을 잘못 알렸다
        g.beginHydration()
        assertFalse("적재가 자기 칸을 못 채운다 — 이것이 잘못 알렸을 때의 결말이다",
            g.mayWrite(InitialHydrationGuard.KEY_CALENDAR))
    }

    @Test
    fun 잘못_알리면_유실의_방향이_뒤집힌다() {
        // 판정기는 '만졌다'를 받으면 그 칸의 적재를 **막는다.** 그래서 과잉 알림은
        // 덮어쓰기가 아니라 **DB 값이 아예 안 들어오는** 상태를 만들고, 그대로 저장하면
        // 종전 값이 지워진다 — 막으려던 것보다 나쁜 결말이다.
        // (실례: 작품 스피너를 터치만으로 '골랐다'로 세던 것. 콜드 검토 2026.08.19)
        val g = InitialHydrationGuard()
        g.onFieldChanged(InitialHydrationGuard.KEY_NOVEL)   // ← 열었다 닫았을 뿐인데 알렸다
        g.beginHydration()
        assertFalse("적재가 작품 선택을 못 채운다", g.mayWrite(InitialHydrationGuard.KEY_NOVEL))
        assertTrue("그런데 더티는 서 있어 저장이 열린다 — 이 조합이 유실을 만든다", g.hasEarlyEdits())
    }

    // ── 국면은 되돌지 않는다 ────────────────────────────────────────────────

    @Test
    fun 적재를_두_번_시작해도_국면이_되돌지_않는다() {
        val g = InitialHydrationGuard()
        g.beginHydration()
        g.endHydration()
        g.beginHydration()   // 늦게 도착한 두 번째 적재 — 무시돼야 한다
        assertEquals(InitialHydrationGuard.Phase.SETTLED, g.phase)
        assertTrue("여기서 국면이 되돌면 사용자의 평범한 편집이 프로그램적 쓰기로 취급된다", g.onFieldChanged(InitialHydrationGuard.KEY_NAME))
    }

    @Test
    fun 적재_없이_국면만_닫아도_된다() {
        // 신규 캐릭터 경로 — 덮을 DB 값이 없어 `beginHydration` 없이 닫는다.
        val g = InitialHydrationGuard()
        g.endHydration()
        assertEquals(InitialHydrationGuard.Phase.SETTLED, g.phase)
        assertFalse(g.hasEarlyEdits())
    }

    @Test
    fun 국면_기본값은_적재_전이다() {
        assertEquals(InitialHydrationGuard.Phase.AWAITING, InitialHydrationGuard().phase)
    }

    // ── 키 목록의 자기 정합 ─────────────────────────────────────────────────

    @Test
    fun 폼_칸_키는_서로_겹치지_않는다() {
        val keys = listOf(
            // 캐릭터 편집
            InitialHydrationGuard.KEY_NAME,
            InitialHydrationGuard.KEY_FIRST_NAME,
            InitialHydrationGuard.KEY_LAST_NAME,
            InitialHydrationGuard.KEY_ANOTHER_NAME,
            InitialHydrationGuard.KEY_MEMO,
            InitialHydrationGuard.KEY_TAGS,
            InitialHydrationGuard.KEY_NOVEL,
            InitialHydrationGuard.KEY_IMAGES,
            // 사건 편집
            InitialHydrationGuard.KEY_YEAR,
            InitialHydrationGuard.KEY_MONTH,
            InitialHydrationGuard.KEY_DAY,
            InitialHydrationGuard.KEY_CALENDAR,
            InitialHydrationGuard.KEY_DESCRIPTION,
            InitialHydrationGuard.KEY_EVENT_TYPE
        )
        // 겹치면 한 칸을 만졌을 때 다른 칸까지 적재가 건너뛴다 — 조용한 빈 칸이 된다.
        // 두 화면이 **한 판정기를 공유**하므로 화면을 넘는 겹침도 여기서 걸린다.
        assertEquals("폼 칸 키가 겹친다", keys.size, keys.toSet().size)
    }

    @Test
    fun 두_화면의_판정기는_서로를_모른다() {
        // 인스턴스가 화면마다 따로 산다 — 한쪽에서 만진 칸이 다른 쪽 적재를 막으면
        // 사건 창을 먼저 만진 사용자가 캐릭터 창에서 빈 칸을 본다.
        val character = InitialHydrationGuard()
        val event = InitialHydrationGuard()
        character.onFieldChanged(InitialHydrationGuard.KEY_NAME)
        assertTrue(event.mayWrite(InitialHydrationGuard.KEY_NAME))
        assertFalse(event.hasEarlyEdits())
    }
}
