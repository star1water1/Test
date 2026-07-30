package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * '등급 체계' 시트(U-1)의 정체성·왕복 계약.
 *
 * 시트 하나를 새로 열 때 지켜야 하는 방어 두 겹을 고정한다 — 예약명(세계관 시트에 자리를
 * 빼앗기지 않는다)과 첫 열('이름'이 아니라 캐릭터 시트 지문에 걸리지 않는다). '캐릭터 필드값'
 * 시트가 세운 선례 그대로이며, 여기서 깨지면 가져오기가 이 시트를 엉뚱한 것으로 읽는다.
 */
class GradeSystemSheetTest {

    @Test
    fun `예약명으로 등재돼 세계관 이름에 자리를 빼앗기지 않는다`() {
        assertTrue(gradeSystemSpec().sheetName in RESERVED_SHEET_NAMES)
        // 같은 이름의 세계관 캐릭터 시트는 접미사로 밀려난다 (R-6: 예약명은 소유자만).
        val used = mutableSetOf<String>()
        val taken = assignSheetName("등급 체계", used, ownerOf = null)
        assertEquals("등급 체계(2)", taken)
    }

    @Test
    fun `첫 열이 이름이 아니라 캐릭터 시트로 오인되지 않는다`() {
        val spec = gradeSystemSpec()
        assertEquals("세계관", spec.firstColumnHeader)
        assertFalse("첫 열 '이름'은 캐릭터 시트 지문에 걸린다", spec.firstColumnHeader == "이름")
    }

    @Test
    fun `헤더 계약 - 내보내기와 가져오기가 같은 열을 본다`() {
        // 가져오기(collectGradeSystemRows)가 이름으로 찾는 열들이다. 헤더를 바꾸면
        // 그쪽 해석 문자열도 함께 바꿔야 한다 — 이 테스트가 그 짝을 강제한다.
        assertEquals(
            listOf("세계관", "체계명", "등급", "기본숫자", "세계관코드", "코드"),
            gradeSystemSpec().columns.map { it.header }
        )
    }

    @Test
    fun `필드 정의 시트와 첫 열이 같아도 두 번째 열에서 갈린다 - R-7`() {
        val fieldSpec = fieldDefinitionSpec(emptyList())
        val gradeSpec = gradeSystemSpec()
        assertEquals(fieldSpec.firstColumnHeader, gradeSpec.firstColumnHeader)
        assertTrue(headersMatchSpec(listOf("세계관", "체계명", "등급"), gradeSpec))
        assertFalse(headersMatchSpec(listOf("세계관", "체계명", "등급"), fieldSpec))
        assertFalse(headersMatchSpec(listOf("세계관", "필드키", "필드명"), gradeSpec))
    }

    @Test
    fun `필드 정의 시트는 등급체계 참조 열 한 쌍을 싣는다`() {
        val headers = fieldDefinitionSpec(emptyList(), listOf("능력 등급")).columns.map { it.header }
        assertTrue("등급체계" in headers)
        assertTrue("등급체계코드" in headers)
        // 참조 열은 이름(사람이 고침) + 코드(안정 식별자) 쌍 — 코드 열은 앱이 채우는 열이다.
        val codeColumn = fieldDefinitionSpec(emptyList()).columns.first { it.header == "등급체계코드" }
        assertTrue(codeColumn.readOnly)
    }

    @Test
    fun `체계명 드롭다운은 내보내기 시점의 체계 이름을 싣는다`() {
        val withNames = fieldDefinitionSpec(emptyList(), listOf("능력 등급", "위험 등급"))
        assertEquals(
            listOf("능력 등급", "위험 등급"),
            withNames.columns.first { it.header == "등급체계" }.dropdownOptions
        )
        // 체계가 없으면 드롭다운도 없다 — 빈 목록의 데이터 검증은 아무것도 고르지 못하게 만든다.
        assertEquals(
            null,
            fieldDefinitionSpec(emptyList()).columns.first { it.header == "등급체계" }.dropdownOptions
        )
    }
}
