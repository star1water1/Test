package com.novelcharacter.app.util

import com.novelcharacter.app.util.PickedImageNotices.Notice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 고른 이미지의 고지 판정 계약 (`1-ㅎ7` 수리, 2026.11.21).
 *
 * 이 판정이 화면 안에 있던 동안 **두 번 틀렸고 둘 다 침묵이었다**([PickedImageNotices] KDoc).
 * 침묵은 시험으로 잡기 가장 어려운 모양이라 여기서 수로 못박는다.
 */
class PickedImageNoticesTest {

    @Test
    fun `아무 몫도 없으면 아무 말도 하지 않는다`() {
        assertEquals(emptyList<Pair<Notice, Int>>(), PickedImageNotices.of(0, 0, 0, 0))
    }

    /** 회전 한 번의 흔한 모양 — 여섯 장을 고르고 돌리면 **한 줄**이다. */
    @Test
    fun `폼이 사라져 전부 담겼으면 담았다는 한 줄만 낸다`() {
        assertEquals(
            listOf(Notice.STRANDED_ADOPTED to 6),
            PickedImageNotices.of(adopted = 6, strandedTotal = 6, notAttempted = 0, failed = 0)
        )
    }

    /**
     * **두 번째로 틀렸던 자리.** 담는 데 실패한 몫이 *"담을 것이 없었다"*와 같은 침묵으로
     * 나가면, 디스크에 쓴 파일이 주인도 meta도 없이 남은 것을 아무도 모른다.
     */
    @Test
    fun `담지 못한 몫을 따로 말한다`() {
        assertEquals(
            listOf(Notice.STRANDED_ADOPTED to 4, Notice.STRANDED_FAILED to 2),
            PickedImageNotices.of(adopted = 4, strandedTotal = 6, notAttempted = 0, failed = 0)
        )
    }

    @Test
    fun `하나도 담지 못했으면 담았다는 말은 아예 없다`() {
        assertEquals(
            listOf(Notice.STRANDED_FAILED to 3),
            PickedImageNotices.of(adopted = 0, strandedTotal = 3, notAttempted = 0, failed = 0)
        )
    }

    @Test
    fun `네 몫이 함께 나면 사실의 무게 순으로 넷을 말한다`() {
        val notices = PickedImageNotices.of(adopted = 1, strandedTotal = 3, notAttempted = 2, failed = 1)
        assertEquals(
            listOf(
                Notice.STRANDED_ADOPTED to 1,
                Notice.STRANDED_FAILED to 2,
                Notice.NOT_ATTEMPTED to 2,
                Notice.SAVE_FAILED to 1
            ),
            notices
        )
    }

    /** 수가 어긋나도 **음수를 말하지는 않는다** — "-2장"은 사용자에게 아무 뜻이 없다. */
    @Test
    fun `담긴 수가 폼이 못 받은 수보다 커도 음수를 말하지 않는다`() {
        val notices = PickedImageNotices.of(adopted = 5, strandedTotal = 3, notAttempted = 0, failed = 0)
        assertTrue(notices.toString(), notices.none { it.first == Notice.STRANDED_FAILED })
        assertTrue(notices.toString(), notices.all { it.second > 0 })
    }

    /** 고지의 합은 **고른 장수를 넘지 않는다** — 몫끼리 겹치면 사용자가 센 수와 안 맞는다. */
    @Test
    fun `몫끼리 겹치지 않는다`() {
        val picked = 10
        val attached = 3
        val notices = PickedImageNotices.of(adopted = 2, strandedTotal = 4, notAttempted = 2, failed = 1)
        val spoken = notices.sumOf { it.second }
        assertEquals(7, spoken)
        assertTrue(spoken + attached <= picked)
    }
}
