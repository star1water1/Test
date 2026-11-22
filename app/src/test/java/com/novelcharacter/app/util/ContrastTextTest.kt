package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 색 바탕 위 글자색 고르기의 계약 — 고른 쪽이 언제나 대비가 더 크고,
 * 실제 앱 팔레트에서 흰 글자 고정이 안 읽히던 바탕은 어두운 글자를 받는다.
 */
class ContrastTextTest {

    private fun c(hex: Long): Int = hex.toInt()

    @Test fun picksDarkTextOnLightBackgrounds() {
        assertEquals(ContrastText.DARK, ContrastText.on(c(0xFFFFFFFF)))
        // 라이트 accent(#E8853D) — 흰 글자 2.7:1이던 자리
        assertEquals(ContrastText.DARK, ContrastText.on(c(0xFFE8853D)))
        // 다크 차트 팔레트 chart_7(#FFC95C) — 흰 글자 1.5:1이던 자리
        assertEquals(ContrastText.DARK, ContrastText.on(c(0xFFFFC95C)))
        // 다크 primary(#8891E8) — 흰 글자 2.9:1이던 자리
        assertEquals(ContrastText.DARK, ContrastText.on(c(0xFF8891E8)))
    }

    @Test fun picksLightTextOnDarkBackgrounds() {
        assertEquals(ContrastText.LIGHT, ContrastText.on(c(0xFF000000)))
        assertEquals(ContrastText.LIGHT, ContrastText.on(c(0xFF15181D)))
        // 라이트 primary(#5E6AD2) — 흰 글자가 이미 더 낫다(4.7:1)
        assertEquals(ContrastText.LIGHT, ContrastText.on(c(0xFF5E6AD2)))
    }

    @Test fun chosenSideIsNeverWorse() {
        val samples = listOf(
            0xFF5E6AD2, 0xFFE8853D, 0xFF12A594, 0xFFD6409F, 0xFF8E4EC6, 0xFF34A853, 0xFFE5A50A, 0xFF0588C7,
            0xFF8891E8, 0xFFF09A5C, 0xFF2EC8B6, 0xFFE170BD, 0xFFA76FE0, 0xFF6FCF75, 0xFFFFC95C, 0xFF4FB7FF,
            0xFF808080, 0xFF767676, 0xFF777777
        ).map { c(it) }
        for (bg in samples) {
            val chosen = ContrastText.contrast(ContrastText.on(bg), bg)
            assertTrue(chosen >= ContrastText.contrast(ContrastText.DARK, bg))
            assertTrue(chosen >= ContrastText.contrast(ContrastText.LIGHT, bg))
            // 두 글자색 중 나은 쪽은 어떤 바탕에서도 약 4.1:1 아래로 떨어지지 않는다
            // (전 RGB 공간 최악값 — 순빨강 부근 ≈ 4.13)
            assertTrue("bg=${Integer.toHexString(bg)} chosen=$chosen", chosen >= 4.1)
        }
    }

    @Test fun contrastMatchesWcagReferenceValues() {
        assertEquals(21.0, ContrastText.contrast(c(0xFF000000), c(0xFFFFFFFF)), 0.01)
        assertEquals(1.0, ContrastText.contrast(c(0xFF5E6AD2), c(0xFF5E6AD2)), 0.0001)
        assertEquals(4.54, ContrastText.contrast(c(0xFF767676), c(0xFFFFFFFF)), 0.01)
    }

    @Test fun alphaIsIgnored() {
        assertEquals(ContrastText.on(c(0xFF8891E8)), ContrastText.on(c(0x558891E8)))
    }
}
