package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DialogScrollCap] — 다이얼로그 본문 스크롤의 높이 상한 규칙(규약 R-31 · B-91·B-98).
 *
 * 이 하네스가 지키는 것은 셋이다:
 * 1. **부모를 이기지 않는다** — 상한을 무조건 밀어붙이면 가로 모드·작은 화면에서 되레
 *    넘쳐 *고치려던 증상을 다른 자리에서 재현한다.* 이것이 규약의 핵심 단서다.
 * 2. **부모가 높이를 안 주면 상한이 유일한 제약이다** — `UNSPECIFIED`로 측정될 때
 *    상한까지 놓아 버리면 다이얼로그가 화면 밖으로 밀려난다(B-91의 원래 증상).
 * 3. **몫이 어긋나도 상한이 무력화되지 않는다** — 0이나 1 초과가 들어오면 기본값으로
 *    되돌린다. 어느 쪽이든 증상이 *상한 자체의 부재*와 구별되지 않아 진단이 어렵다.
 *
 * 뷰 셋(`cappedScrollView` · `CappedScrollView` · `CappedNestedScrollView`)은 전부 이
 * 계산만 부르는 껍데기이므로, 규약이 갈리지 않는다는 것은 여기서 증명된다.
 */
class DialogScrollCapTest {

    // ── 1. 부모를 이기지 않는다 ───────────────────────────────────────────────

    @Test
    fun `부모가 상한보다 좁게 잡으면 부모를 따른다`() {
        val cap = DialogScrollCap.capPx(2000)   // 1400
        assertEquals(
            "부모가 더 좁은데 상한을 밀어붙이면 가로 모드에서 넘친다",
            600,
            DialogScrollCap.limitPx(cap, parentBounded = true, parentSizePx = 600)
        )
    }

    @Test
    fun `부모가 상한보다 넓게 잡으면 상한에서 멈춘다`() {
        val cap = DialogScrollCap.capPx(2000)
        assertEquals(cap, DialogScrollCap.limitPx(cap, parentBounded = true, parentSizePx = 5000))
    }

    // ── 2. 부모가 높이를 안 줄 때 ─────────────────────────────────────────────

    @Test
    fun `부모가 높이를 주지 않으면 상한이 유일한 제약이다`() {
        val cap = DialogScrollCap.capPx(2000)
        // parentSizePx는 UNSPECIFIED에서 뜻이 없다 — 무엇이 오든 상한이 이겨야 한다.
        assertEquals(cap, DialogScrollCap.limitPx(cap, parentBounded = false, parentSizePx = 0))
        assertEquals(cap, DialogScrollCap.limitPx(cap, parentBounded = false, parentSizePx = 99999))
    }

    // ── 3. 몫 계산 ────────────────────────────────────────────────────────────

    @Test
    fun `기본 몫은 제목과 버튼이 설 자리를 남긴다`() {
        assertEquals(1400, DialogScrollCap.capPx(2000))
        assertTrue(
            "화면 전부를 쓰면 제목·버튼이 밀려나 상한의 뜻이 없다",
            DialogScrollCap.DEFAULT_FRACTION < 1f
        )
    }

    @Test
    fun `호출부가 다른 몫을 주면 그것을 쓴다`() {
        assertEquals(1000, DialogScrollCap.capPx(2000, 0.5f))
        assertEquals(2000, DialogScrollCap.capPx(2000, 1f))
    }

    @Test
    fun `몫이 범위를 벗어나면 기본값으로 되돌린다`() {
        val expected = DialogScrollCap.capPx(2000)
        assertEquals("0이면 아무것도 안 보인다", expected, DialogScrollCap.capPx(2000, 0f))
        assertEquals("음수도 같은 부류다", expected, DialogScrollCap.capPx(2000, -0.5f))
        assertEquals("1 초과는 상한 없음과 같다", expected, DialogScrollCap.capPx(2000, 1.5f))
    }
}
