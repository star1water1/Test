package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProgressScale] — 작업형 진행도의 바이트 눈금 환산(규약 R-26 · B-51).
 *
 * 이 하네스가 지키는 것은 셋이다:
 * 1. **Int 상한을 넘는 총량에서 막대가 뒤집히지 않는다** — 이 앱의 파일 상한은 4GB·8GB이고
 *    진행 창은 `Int`(약 2.1GB)를 받는다. 바이트를 그대로 넘기면 음수가 되어 "진행도가
 *    거꾸로 간다"가 된다. 이것이 이 클래스가 존재하는 이유다.
 * 2. **다 된 것은 다 된 것으로 보고된다** — 내림만 쓰면 1MB 미만 파일이 끝까지 0%다.
 * 3. **중간에 100%를 지나치지 않는다** — 올림만 쓰면 막대가 넘었다가 돌아온다.
 */
class ProgressScaleTest {

    private val mb = ProgressScale.MEGABYTE

    // ── 1. Int 상한 위 ────────────────────────────────────────────────────────

    @Test
    fun `외부 파일 상한 4GB에서 총량 눈금이 Int 안에 있다`() {
        val fourGb = 4L * 1024 * 1024 * 1024
        val scale = ProgressScale.forBytes(fourGb)
        assertEquals(4096, scale.totalSteps)
        assertTrue("총량 눈금은 양수여야 한다(뒤집히면 막대가 거꾸로 간다)", scale.totalSteps > 0)
    }

    @Test
    fun `이미지 총량 상한 8GB에서도 진행이 단조 증가한다`() {
        val eightGb = 8L * 1024 * 1024 * 1024
        val scale = ProgressScale.forBytes(eightGb)
        assertEquals(8192, scale.totalSteps)

        var previous = 0
        // Int 상한(약 2.1GB)을 넘어가는 지점을 반드시 지나도록 훑는다
        var bytes = 0L
        while (bytes <= eightGb) {
            val steps = scale.stepsFor(bytes)
            assertTrue("진행 눈금이 음수가 됐다: bytes=$bytes steps=$steps", steps >= 0)
            assertTrue("진행이 뒤로 갔다: $previous → $steps", steps >= previous)
            assertTrue("진행이 총량을 넘었다: $steps > ${scale.totalSteps}", steps <= scale.totalSteps)
            previous = steps
            bytes += 512L * 1024 * 1024
        }
    }

    // ── 2. 끝은 끝으로 보고된다 ────────────────────────────────────────────────

    @Test
    fun `1MB 미만 파일도 다 되면 100퍼센트가 된다`() {
        val small = 300L * 1024   // 0.3MB
        val scale = ProgressScale.forBytes(small)
        assertEquals("올림이라 총량은 1이다", 1, scale.totalSteps)
        assertEquals("총량에 닿으면 총량 눈금", 1, scale.stepsFor(small))
    }

    @Test
    fun `총량에 닿으면 언제나 총량 눈금이다`() {
        for (bytes in listOf(1L, 1023L, mb, mb + 1, 7L * mb + 13, 750L * mb)) {
            val scale = ProgressScale.forBytes(bytes)
            assertEquals("bytes=$bytes", scale.totalSteps, scale.stepsFor(bytes))
        }
    }

    @Test
    fun `총량을 넘겨 보고해도 총량 눈금에서 멈춘다`() {
        val scale = ProgressScale.forBytes(10L * mb)
        assertEquals(10, scale.stepsFor(99L * mb))
    }

    // ── 3. 중간은 총량을 넘지 않는다 ───────────────────────────────────────────

    @Test
    fun `중간 진행은 총량 눈금을 넘지 않는다`() {
        val total = 750L * mb
        val scale = ProgressScale.forBytes(total)
        assertEquals(750, scale.totalSteps)
        assertEquals(0, scale.stepsFor(0))
        assertEquals("0.9MB는 아직 한 칸도 아니다", 0, scale.stepsFor(900L * 1024))
        assertEquals(1, scale.stepsFor(mb))
        assertEquals(375, scale.stepsFor(375L * mb))
        // 끝 직전에 총량으로 올라붙지 않는다
        assertEquals(749, scale.stepsFor(total - 1))
    }

    @Test
    fun `음수 진행은 0이다`() {
        val scale = ProgressScale.forBytes(10L * mb)
        assertEquals(0, scale.stepsFor(-1))
    }

    // ── 4. 총량을 모르는 스트림 ────────────────────────────────────────────────

    @Test
    fun `크기를 보고하지 않는 스트림은 총량 눈금이 0이다`() {
        // statSize가 -1을 주는 콘텐츠 프로바이더 — 창은 이때 퍼센트 대신 불확정 막대를 그린다
        for (unknown in listOf(-1L, 0L)) {
            val scale = ProgressScale.forBytes(unknown)
            assertEquals("bytes=$unknown", 0, scale.totalSteps)
        }
    }

    @Test
    fun `총량을 몰라도 지금까지의 MB는 센다`() {
        val scale = ProgressScale.forBytes(-1L)
        assertEquals(0, scale.stepsFor(0))
        assertEquals(0, scale.stepsFor(mb - 1))
        assertEquals(1, scale.stepsFor(mb))
        assertEquals(350, scale.stepsFor(350L * mb))
        // 총량을 모르는 구간도 Int를 넘겨선 안 된다
        assertTrue(scale.stepsFor(8L * 1024 * 1024 * 1024) > 0)
    }
}
