package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 목록 정렬 비교키의 회귀 가드 (B-37).
 *
 * **이 파일의 방어선은 건수가 아니라 그 안의 둘이다.**
 *
 * ① *"쪼개는 기준이 통계와 같다"* — 종전 [FieldValueSorter.textValue]는
 *   `field.type == "MULTI_TEXT"`를 직접 견줘, **쉼표 목록 표시 형식의 TEXT를 통문자열로**
 *   정렬했다. 같은 값이 통계에서는 두 토큰인데 목록에서는 하나였고, **화면에는 그저 순서가
 *   그렇게 보일 뿐이라 눈으로는 틀린 줄을 모른다.** 하드코딩을 지워도 [FieldValueTokenizer]가
 *   실제로 불리는지는 시험만 잰다.
 *
 * ② *"기존 `MULTI_TEXT`의 순서가 하나도 안 바뀐다"* — 대표값이 `minOrNull()`이라는 사실을
 *   못 박는다. 기각된 쪽(**맨 앞 토큰**)으로 바꾸면 코드는 더 단순해 보이고 검사도 전부
 *   초록인데, **이미 쓰던 필드의 정렬이 전부 조용히 달라진다**(확정 7-5가 그것을 이유로
 *   기각했다). ①만 있으면 그 갈아치움이 통과하므로 짝으로 세워야 잠긴다.
 */
class FieldValueSorterTest {

    private fun fd(type: String, config: String = "{}") = FieldDefinition(
        id = 1L, universeId = 1L, key = "k", name = "필드", type = type, config = config
    )

    private val commaList = """{"displayFormat":"comma_list"}"""
    private val bulletList = """{"displayFormat":"bullet_list"}"""

    // ===== ① 쪼개는 기준이 토큰화 단일 소스와 같다 =====

    @Test
    fun textValue_commaListText_usesMinToken_notWholeString() {
        // 종전에는 "검, 활" 통문자열이었다 — 그러면 "ㄱ"으로 시작하는 다른 값과의 순서가
        // 토큰이 아니라 **적은 순서**에 좌우된다.
        assertEquals("검", FieldValueSorter.textValue(fd("TEXT", commaList), "활, 검"))
    }

    @Test
    fun textValue_bulletListText_alsoSplits() {
        assertEquals("가", FieldValueSorter.textValue(fd("TEXT", bulletList), "나, 가"))
    }

    @Test
    fun textValue_plainText_isNotSplit() {
        // 표시 형식이 목록이 아니면 쉼표는 그냥 글자다 — 이름의 쉼표가 값을 쪼개면 안 된다.
        assertEquals("활, 검", FieldValueSorter.textValue(fd("TEXT"), "활, 검"))
    }

    // ===== ② 기존 MULTI_TEXT의 순서가 안 바뀐다 (맨 앞 토큰이 아니다) =====

    @Test
    fun textValue_multiText_takesAlphabeticalMinimum_notFirstToken() {
        // 맨 앞 토큰이었다면 "활"이 나온다.
        assertEquals("검", FieldValueSorter.textValue(fd("MULTI_TEXT"), "활, 검"))
    }

    @Test
    fun textValue_multiText_orderOfEntryDoesNotMatter() {
        val f = fd("MULTI_TEXT")
        assertEquals(
            FieldValueSorter.textValue(f, "활, 검, 방패"),
            FieldValueSorter.textValue(f, "방패, 검, 활")
        )
    }

    // ===== 경계: 빈 값·공백은 최후순(null) =====

    @Test
    fun textValue_blank_isNull() {
        assertNull(FieldValueSorter.textValue(fd("TEXT"), "   "))
        assertNull(FieldValueSorter.textValue(fd("MULTI_TEXT"), " , , "))
        assertNull(FieldValueSorter.textValue(fd("TEXT", commaList), ""))
    }

    @Test
    fun textValue_trimsAndLowercases() {
        assertEquals("apple", FieldValueSorter.textValue(fd("TEXT"), "  Apple  "))
        assertEquals("apple", FieldValueSorter.textValue(fd("MULTI_TEXT"), "Banana, Apple"))
    }

    // ===== 수치 경로는 이 변경과 무관하다 =====

    @Test
    fun isNumericSortType_unchanged() {
        assertEquals(true, FieldValueSorter.isNumericSortType(FieldType.NUMBER))
        assertEquals(true, FieldValueSorter.isNumericSortType(FieldType.BODY_SIZE))
        assertEquals(false, FieldValueSorter.isNumericSortType(FieldType.MULTI_TEXT))
        // 모르는 타입(월드패키지·손으로 고친 DB로 들어올 수 있다)은 수로 읽지 않는다 — B-55.
        assertEquals(false, FieldValueSorter.isNumericSortType(null))
    }

    @Test
    fun numericValue_number_parses() {
        assertEquals(3.5, FieldValueSorter.numericValue(fd("NUMBER"), "3.5", null)!!, 0.0001)
        assertNull(FieldValueSorter.numericValue(fd("NUMBER"), "숫자아님", null))
    }
}
