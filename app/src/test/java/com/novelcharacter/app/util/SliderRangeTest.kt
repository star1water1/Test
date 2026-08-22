package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 연도 슬라이더 범위의 계약 — **어기면 그리기 패스에서 앱이 죽는다.**
 *
 * Material `Slider`의 검증은 대입 시점이 아니라 측정·그리기 패스에서 돌기 때문에,
 * 호출부를 감싼 `try/catch`가 원리적으로 그 예외를 못 잡는다. 그래서 *어기지 않는 것*이
 * 유일한 방어이고, 그 판단을 순수 계층으로 내려 여기서 잠근다.
 */
class SliderRangeTest {

    private fun assertValid(spec: SliderRange.Spec, label: String) {
        assertTrue("$label — 시작이 끝보다 작아야 한다: $spec", spec.from < spec.to)
        val span = spec.to - spec.from
        assertEquals("$label — (끝 - 시작)이 눈금의 배수여야 한다: $spec", 0f, span % spec.step, 0.001f)
        val offset = spec.value - spec.from
        assertEquals("$label — 값이 눈금 위에 있어야 한다: $spec", 0f, offset % spec.step, 0.001f)
        assertTrue("$label — 값이 범위 안이어야 한다: $spec", spec.value in spec.from..spec.to)
    }

    /** 실제로 죽던 자리 — 폭이 1,000을 넘어 눈금이 10인데 범위가 10의 배수가 아니었다. */
    @Test
    fun `천 년이 넘는 폭에서도 범위가 눈금의 배수다`() {
        val spec = SliderRange.of(1000, 2005, current = null)
        assertEquals(10f, spec.step)
        assertValid(spec, "1000~2005")
    }

    @Test
    fun `만 년이 넘는 폭은 눈금이 백이다`() {
        val spec = SliderRange.of(1, 12000, current = null)
        assertEquals(100f, spec.step)
        assertValid(spec, "1~12000")
    }

    /** 폭·여유·현재값을 훑는다 — 한 조합이라도 어기면 그 화면이 열릴 때 죽는다. */
    @Test
    fun `어떤 폭 여유 현재값 조합에서도 계약이 성립한다`() {
        val mins = listOf(-30000, -1201, -1, 0, 1, 7, 999, 1000, 1993)
        val spans = listOf(0, 1, 2, 9, 10, 11, 999, 1000, 1001, 1005, 9999, 10000, 10001, 33333)
        val pads = listOf(0, 10)
        for (min in mins) for (span in spans) for (pad in pads) {
            val max = min + span
            for (current in listOf(null, min, max, min - 5000, max + 5000, (min + max) / 2)) {
                assertValid(SliderRange.of(min, max, current, pad), "min=$min span=$span pad=$pad cur=$current")
            }
        }
    }

    /** 기원전 표기(음수 연도) — `/`가 0 쪽으로 자르는 함정이 있는 자리다. */
    @Test
    fun `음수 연도에서도 경계가 격자에 맞는다`() {
        val spec = SliderRange.of(-2005, -1000, current = -1500)
        assertEquals(10f, spec.step)
        assertValid(spec, "기원전")
        assertTrue("실측 범위를 덮어야 한다", spec.from <= -2005f && spec.to >= -1000f)
    }

    @Test
    fun `폭이 없으면 한 눈금을 벌린다`() {
        val spec = SliderRange.of(1993, 1993, current = 1993)
        assertValid(spec, "한 해뿐")
        assertTrue("한 해뿐이어도 끌 수 있어야 한다", spec.to - spec.from >= spec.step)
    }

    @Test
    fun `실측 범위는 언제나 덮인다`() {
        for (pad in listOf(0, 10)) {
            val spec = SliderRange.of(37, 4210, current = null, pad = pad)
            assertTrue("왼쪽을 덮어야 한다: $spec", spec.from <= 37f)
            assertTrue("오른쪽을 덮어야 한다: $spec", spec.to >= 4210f)
        }
    }

    @Test
    fun `현재값이 범위 밖이면 안으로 들어온다`() {
        val far = SliderRange.of(1000, 1100, current = 99999)
        assertValid(far, "오른쪽 밖")
        assertTrue(far.value <= far.to)
        val near = SliderRange.of(1000, 1100, current = -99999)
        assertValid(near, "왼쪽 밖")
        assertTrue(near.value >= near.from)
    }

    /** 최대가 최소보다 작게 들어와도(자료가 깨졌어도) 죽지 않는다. */
    @Test
    fun `뒤집힌 입력도 유효한 범위를 낸다`() {
        assertValid(SliderRange.of(2000, 1990, current = null), "뒤집힘")
    }

    @Test
    fun `눈금 잣대는 두 화면이 같은 함수를 쓴다`() {
        assertEquals(1f, SliderRange.stepFor(1000))
        assertEquals(10f, SliderRange.stepFor(1001))
        assertEquals(10f, SliderRange.stepFor(10000))
        assertEquals(100f, SliderRange.stepFor(10001))
    }
}
