package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오류 로그 회전 (2026.08.22).
 *
 * 이 시험이 지키는 것은 하나다: **상한에 닿아도 최근 기록은 남는다.**
 * 종전에는 상한을 넘는 순간 파일을 통째로 지워, 목록이 *"총 137건"*에서 **1건**으로
 * 떨어졌다 — 회전이 아니라 삭제였다.
 */
class LogRotationTest {

    private val sep = "──────────"

    private fun entry(n: Int) = "$sep\n[2026-08-22 00:00:0$n][ERROR][T$n] 메시지 $n\n"

    @Test
    fun `상한 안이면 그대로 둔다`() {
        val text = entry(1) + entry(2)
        assertEquals(text, LogRotation.rotate(text, 10_000, sep))
    }

    @Test
    fun `넘치면 최근 쪽을 남긴다`() {
        val text = (1..6).joinToString("") { entry(it) }
        val one = entry(1).toByteArray().size.toLong()
        val out = LogRotation.rotate(text, one * 3, sep)

        // 가장 최근(6)은 반드시 살아 있고, 가장 오래된(1)은 떨어진다.
        assertTrue("최근 항목이 남아야 한다", out.contains("메시지 6"))
        assertFalse("가장 오래된 항목은 떨어진다", out.contains("메시지 1"))
        assertTrue("잘린 사실을 말해야 한다", out.contains(LogRotation.TRUNCATION_NOTICE))
    }

    @Test
    fun `한 블록이 상한보다 커도 그 하나는 남긴다`() {
        // 상한 때문에 **방금 난 오류**를 잃는 것이 가장 나쁘다.
        val huge = "$sep\n[2026-08-22 00:00:00][ERROR][T] ${"x".repeat(500)}\n"
        val out = LogRotation.rotate(entry(1) + huge, 10, sep)
        assertTrue(out.contains("x".repeat(500)))
    }

    @Test
    fun `구분자 없는 옛 파일도 한 블록으로 다룬다`() {
        val legacy = "옛 형식 한 줄\n"
        assertEquals(legacy, LogRotation.rotate(legacy, 10_000, sep))
    }

    @Test
    fun `빈 파일은 그대로다`() {
        assertEquals("", LogRotation.rotate("", 100, sep))
    }

    /**
     * **회전 뒤에도 파서가 읽을 수 있어야 한다** — 구분자는 블록의 *머리*이므로
     * 잘라 붙인 결과에서도 남은 첫 블록이 자기 구분자를 들고 있어야 한다.
     */
    @Test
    fun `회전 결과가 블록 경계를 지킨다`() {
        val text = (1..6).joinToString("") { entry(it) }
        val one = entry(1).toByteArray().size.toLong()
        val out = LogRotation.rotate(text, one * 2, sep)

        val blocks = out.split(sep).filter { it.isNotBlank() }
        // 잘림 표시 한 블록 + 남은 항목들 — 모든 항목 블록이 온전한 헤더를 들고 있다.
        assertTrue(blocks.isNotEmpty())
        val entries = blocks.filterNot { it.contains(LogRotation.TRUNCATION_NOTICE) }
        assertTrue("남은 항목이 있어야 한다", entries.isNotEmpty())
        for (b in entries) assertTrue("헤더가 온전해야 한다: $b", b.contains("[ERROR]"))
    }
}
