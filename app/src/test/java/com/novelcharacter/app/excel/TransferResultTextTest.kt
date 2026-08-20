package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가져오기 결과 상세 본문 — 화면의 '상세 보기' 창과 화면 밖 보관함이 **같은 함수**로
 * 짓는다([TransferResultText]). 여기서 잠그는 것은 그 본문의 모양이다: 이 모양이 갈리면
 * 화면 안팎에서 같은 결과가 다르게 읽히고, 상한이 무너지면 보관함(SharedPreferences)에
 * 수천 줄이 실린다.
 */
class TransferResultTextTest {

    @Test
    fun `오류는 번호로 경고는 글머리표로 적는다`() {
        val body = TransferResultText.detailBody(
            errors = listOf("첫째 오류", "둘째 오류"),
            warnings = listOf("첫째 경고")
        )
        assertTrue(body.contains("── 오류 (2건) ──"))
        assertTrue(body.contains("1. 첫째 오류"))
        assertTrue(body.contains("2. 둘째 오류"))
        assertTrue(body.contains("── 경고 (1건) ──"))
        assertTrue(body.contains("• 첫째 경고"))
    }

    @Test
    fun `상한을 넘으면 20건까지 적고 나머지는 개수로 알린다`() {
        // R-14 부류 — 잘라낸 것은 개수로 존재를 알린다. 상한이 무너지면 보관함에 수천 줄이 실린다.
        val body = TransferResultText.detailBody(
            errors = (1..31).map { "오류 $it" },
            warnings = (1..31).map { "경고 $it" }
        )
        assertTrue(body.contains("20. 오류 20"))
        assertFalse(body.contains("21. 오류 21"))
        assertTrue(body.contains("... 외 11건"))
        assertTrue(body.contains("• 경고 20"))
        assertFalse(body.contains("• 경고 21"))
    }

    @Test
    fun `상한 이하면 전부 적는다`() {
        // 30건까지는 자르지 않는다 — 21~30번째가 사라지면 사용자가 고칠 자리도 함께 사라진다.
        val body = TransferResultText.detailBody(
            errors = (1..30).map { "오류 $it" },
            warnings = emptyList()
        )
        assertTrue(body.contains("30. 오류 30"))
        assertFalse(body.contains("외"))
    }

    @Test
    fun `둘 다 없으면 빈 본문이다`() {
        assertEquals("", TransferResultText.detailBody(emptyList(), emptyList()))
    }
}
