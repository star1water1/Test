package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeEventYearMismatchTest {

    @Test
    fun `출생 사건 고지는 사건 설명과 두 연도를 말한다`() {
        val msg = LifeEventYearMismatch.message("Dan", isBirth = true, eventDescription = "Dan birth", eventYear = 972, stateYear = 970)
        assertEquals(
            "캐릭터 'Dan': 출생 사건 'Dan birth'의 연도(972)와 출생 연도(970)가 다릅니다 — " +
                "파일의 '상태 변화' 시트나 출생연도 값이 다른 연도를 말해 사건은 그대로 두었습니다. " +
                "어느 쪽이 맞는지 확인해 한쪽을 고친 뒤 다시 가져오세요",
            msg
        )
    }

    @Test
    fun `사망 사건은 사망으로 말한다`() {
        val msg = LifeEventYearMismatch.message("Eve", isBirth = false, eventDescription = "", eventYear = 1001, stateYear = 1000)
        assertTrue(msg.startsWith("캐릭터 'Eve': 사망 사건의 연도(1001)와 사망 연도(1000)"))
        assertFalse(msg.contains("출생"))
    }

    @Test
    fun `긴 설명은 잘라 싣는다`() {
        val msg = LifeEventYearMismatch.message("A", isBirth = true, eventDescription = "가".repeat(50), eventYear = 1, stateYear = 2)
        assertTrue(msg.contains("'" + "가".repeat(30) + "…'의 연도"))
    }
}
