package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.SearchPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시트 값 해석 규약의 단일 소스 — 불리언 F1-A 규약과 드롭다운 유효값 검증.
 * 이 규약이 시트마다 갈라지면 같은 이름의 열이 시트에 따라 반대로 동작한다.
 */
class SheetValueConventionsTest {

    // ── 불리언: 빈칸은 false(비움 의도)이며, null은 '열 없음'만을 뜻한다 ──

    @Test
    fun parseSheetBoolean_tolerantTokens() {
        listOf("Y", "yes", "TRUE", "t", "1", "O", "예", "참", " y ").forEach {
            assertTrue("'$it' 는 true 여야 한다", parseSheetBoolean(it))
        }
        listOf("N", "no", "FALSE", "f", "0", "아니오", "x").forEach {
            assertFalse("'$it' 는 false 여야 한다", parseSheetBoolean(it))
        }
    }

    @Test
    fun parseSheetBoolean_fullWidthNormalized() {
        assertTrue(parseSheetBoolean("Ｙ"))
        assertTrue(parseSheetBoolean("１"))
    }

    @Test
    fun parseSheetBoolean_blankIsFalse_notNull() {
        // 빈칸을 true 쪽이나 '유지' 쪽으로 접으면 '기본값'을 비워 해제할 수 없게 된다
        assertFalse(parseSheetBoolean(""))
        assertFalse(parseSheetBoolean("   "))
    }

    @Test
    fun sheetBooleanOrKeep_nullOnlyWhenColumnAbsent() {
        // 열 없음 → null (호출측이 기존값 유지)
        assertNull(sheetBooleanOrKeep(columnPresent = false, cellText = ""))
        assertNull(sheetBooleanOrKeep(columnPresent = false, cellText = "Y"))
        // 열 있음 → 빈칸도 해석 대상 (F1-A: 비움 의도)
        assertEquals(false, sheetBooleanOrKeep(columnPresent = true, cellText = ""))
        assertEquals(true, sheetBooleanOrKeep(columnPresent = true, cellText = "Y"))
    }

    // ── 드롭다운: 표기 차이만 흡수하고 뜻이 다른 값은 해석하지 않는다 ──

    @Test
    fun matchDropdownValue_normalizesNotationOnly() {
        val allowed = SearchPreset.SORT_MODES
        assertEquals("name", matchDropdownValue("NAME", allowed))
        assertEquals("name", matchDropdownValue("  name  ", allowed))
        assertEquals("relevance", matchDropdownValue("Relevance", allowed))
    }

    @Test
    fun matchDropdownValue_rejectsDifferentMeaning() {
        val allowed = SearchPreset.SORT_MODES
        // 한글 라벨은 앱 UI 표기이지 저장값이 아니다 — 조용히 수용하면 relevance로 동작해 사용자가 틀린 줄 모른다
        assertNull(matchDropdownValue("이름순", allowed))
        assertNull(matchDropdownValue("오름차순", allowed))
        assertNull(matchDropdownValue("", allowed))
    }

    // ── 세계관 커스텀 관계 유형·색상: JSON이 아닌 입력의 관대 해석 ──

    @Test
    fun parseRelTypeTokens_acceptsCommaSeparated() {
        assertEquals(listOf("연인", "라이벌"), parseRelTypeTokens("연인, 라이벌"))
        assertEquals(listOf("연인", "라이벌"), parseRelTypeTokens("\"연인\", \"라이벌\""))
        assertEquals(listOf("연인"), parseRelTypeTokens("연인, 연인"))   // 중복 제거
        assertTrue(parseRelTypeTokens("   ").isEmpty())
    }

    @Test
    fun parseRelColorTokens_acceptsEqualsAndColonForms() {
        assertEquals(listOf("연인" to "#E91E63"), parseRelColorTokens("연인=#E91E63"))
        assertEquals(listOf("연인" to "#E91E63"), parseRelColorTokens("연인:#E91E63"))
        assertEquals(
            listOf("연인" to "#E91E63", "라이벌" to "#3F51B5"),
            parseRelColorTokens("연인=#E91E63, 라이벌=#3F51B5")
        )
    }

    @Test
    fun parseRelColorTokens_recoversBracelessJsonFragment() {
        assertEquals(listOf("연인" to "#E91E63"), parseRelColorTokens("\"연인\":\"#E91E63\""))
    }

    @Test
    fun parseRelColorTokens_rejectsUnparseable() {
        assertTrue(parseRelColorTokens("연인").isEmpty())        // 구분자 없음
        assertTrue(parseRelColorTokens("=#E91E63").isEmpty())    // 키 없음
        assertTrue(parseRelColorTokens("연인=").isEmpty())        // 값 없음
    }

    @Test
    fun searchPresetSortModes_areTheSingleSourceForDropdownAndValidation() {
        // 내보내기 드롭다운과 가져오기 검증이 같은 상수를 봐야 드리프트하지 않는다
        val spec = searchPresetSpec()
        val sortModeColumn = spec.columns.single { it.header == "정렬모드" }
        assertEquals(SearchPreset.SORT_MODES, sortModeColumn.dropdownOptions)
        assertTrue(SearchPreset.SORT_RELEVANCE in SearchPreset.SORT_MODES)
    }

    // ── 셀 절단: 한도 경계에서 서러게이트 쌍을 반쪽 내지 않는다 ──

    @Test
    fun truncateForCell_underLimitIsUntouched() {
        assertEquals("짧은 값", truncateForCell("짧은 값"))
        val exactly = "a".repeat(EXCEL_CELL_TEXT_LIMIT)
        assertEquals(exactly, truncateForCell(exactly))
    }

    @Test
    fun truncateForCell_cutsAtLimit() {
        val over = "a".repeat(EXCEL_CELL_TEXT_LIMIT + 10)
        assertEquals(EXCEL_CELL_TEXT_LIMIT, truncateForCell(over).length)
    }

    @Test
    fun truncateForCell_doesNotSplitSurrogatePair() {
        // 한도 경계 바로 앞에 보충 평면 문자(😀 = 서러게이트 쌍 2유닛)를 놓는다 —
        // String.take라면 상위 서러게이트만 남아 짝 잃은 반쪽이 마지막 글자가 된다.
        val limit = 10
        val value = "a".repeat(limit - 1) + "😀" + "뒤"   // 잘림 지점이 쌍의 한가운데
        val cut = truncateForCell(value, limit)
        assertEquals("반쪽 서러게이트를 떨궈 한 글자 짧아야 한다", limit - 1, cut.length)
        assertFalse("마지막 글자가 짝 잃은 상위 서러게이트면 안 된다", cut.isNotEmpty() && cut.last().isHighSurrogate())
    }

    @Test
    fun truncateForCell_pairInsideLimitSurvives() {
        // 쌍이 한도 안에 온전히 들어가면 그대로 산다.
        val limit = 10
        val value = "😀" + "a".repeat(limit)              // 쌍(2) + a×10 = 12유닛
        val cut = truncateForCell(value, limit)
        assertEquals(limit, cut.length)
        assertTrue(cut.startsWith("😀"))
    }
}
