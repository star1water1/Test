package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 엔진에 못박은 문자 클래스 — **집합을 수로 붙들어 둔다.**
 *
 * ## 이 시험이 실제로 무엇을 보증하는가 (그리고 무엇을 못 하는가)
 *
 * 이 시험은 **데스크톱 JVM 엔진에서의 집합**을 확인한다. 그것만으로 기기 동작이 보증되지는
 * 않는다 — 안드로이드는 ICU 엔진이라 같은 글자가 다른 뜻일 수 있기 때문이다(그것이 이
 * 클래스들이 생긴 이유다).
 *
 * **그래서 두 겹으로 막는다:**
 * 1. [RegexCharClasses]가 고른 표기는 **두 엔진에서 글자 단위로 같다는 것을 오프라인에서
 *    확인해 두었다** — ICU 74와 OpenJDK 21로 BMP 전 코드포인트(63,488자)를 대조했다.
 *    그 확인 덕분에 *여기서 잰 집합이 곧 기기의 집합*이 된다.
 * 2. 약칭(`\d`·`\s`·`\w`)이 다시 기어들어오는 것은 `tools/check_regex_engine_parity.sh`가 막는다.
 *
 * **그러니 여기 적힌 수를 "그냥 고치지" 말 것.** 수가 틀어졌다면 집합이 바뀐 것이고,
 * 집합이 바뀌었다면 위 1번의 대조를 **다시 해야** 한다.
 */
class RegexCharClassesTest {

    /** ICU가 `\s`로 치는 25자 — 이 목록이 [RegexCharClasses.WHITESPACE]의 정체다. */
    private val expectedWhitespace: Set<Int> = setOf(
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20,          // ASCII 6 (= 데스크톱 JVM의 \s 전부)
        0x85, 0xA0, 0x1680,                           // NEL · NBSP · OGHAM SPACE MARK
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A,       // EN QUAD … HAIR SPACE
        0x2028, 0x2029,                               // LINE · PARAGRAPH SEPARATOR
        0x202F, 0x205F, 0x3000                        // NNBSP · MMSP · 전각 공백
    )

    private fun bmp(): Sequence<Int> =
        (0x0000..0xFFFF).asSequence().filterNot { it in 0xD800..0xDFFF }

    @Test fun whitespaceClassIsExactlyTheTwentyFiveCharacters() {
        val actual = bmp().filter {
            RegexCharClasses.WHITESPACE_RUN.matches(String(Character.toChars(it)))
        }.toSet()
        assertEquals(25, expectedWhitespace.size)
        assertEquals(expectedWhitespace, actual)
    }

    @Test fun whitespaceCoversTheOnesExcelAndKoreanTextActuallyCarry() {
        // 엑셀 헤더와 한국어 글에 실제로 들어오는 둘 — 데스크톱 JVM의 `\s`는 **둘 다 못 본다.**
        assertTrue(RegexCharClasses.WHITESPACE_RUN.containsMatchIn("\u00A0"))   // NBSP
        assertTrue(RegexCharClasses.WHITESPACE_RUN.containsMatchIn("\u3000"))   // 전각 공백
        // 폭 없는 공백은 공백이 아니다 — ICU도 그렇게 친다. 넓히지 않았다는 확인.
        assertFalse(RegexCharClasses.WHITESPACE_RUN.containsMatchIn("\u200B"))  // ZWSP
    }

    @Test fun headerNoiseFoldsSpacingAndBracketsForAliasMatching() {
        fun norm(s: String) = s.trim().lowercase().replace(RegexCharClasses.HEADER_NOISE, "")
        assertEquals("캐릭터이름", norm("캐릭터 이름"))
        assertEquals("캐릭터이름", norm("캐릭터\u00A0이름"))   // NBSP — 엑셀이 흔히 넣는다
        assertEquals("캐릭터이름", norm("캐릭터\u3000이름"))   // 전각 공백
        assertEquals("캐릭터이름", norm("캐릭터_이름"))
        assertEquals("캐릭터이름", norm("캐릭터-이름"))
        assertEquals("캐릭터이름", norm("(캐릭터)이름"))
        assertEquals("캐릭터이름", norm("（캐릭터）이름"))
    }

    @Test fun dashSlashWhitespaceSplitsTheSameWayForAllThreeCallers() {
        val split = { s: String -> s.split(RegexCharClasses.DASH_SLASH_WHITESPACE).filter { it.isNotEmpty() } }
        assertEquals(listOf("86", "60", "88"), split("86-60-88"))
        assertEquals(listOf("86", "60", "88"), split("86/60/88"))
        assertEquals(listOf("86", "60", "88"), split("86 60 88"))
        assertEquals(listOf("86", "60", "88"), split("86\u00A060\u00A088"))   // NBSP
    }

    @Test fun filenameKeepDropsSeparatorsAndInvisiblesButKeepsRealLetters() {
        val strip = Regex("[^${RegexCharClasses.FILENAME_KEEP}]")
        fun clean(s: String) = s.replace(strip, "_")
        // 남는다 — 어느 문자든 '글자'면 파일 이름에 둔다
        assertEquals("한글이름", clean("한글이름"))
        assertEquals("Latin_1", clean("Latin 1"))
        assertEquals("日本語", clean("日本語"))
        // **수(數)로 쓰이는 글자도 남는다** — 콜드 검토가 잡은 자리다. 종전에는 `\p{Nl}` 65자
        // (로마숫자·〇·항주 숫자)가 통째로 `_`가 됐고, 그것은 *"기기 동작을 보존한다"*던
        // 이 판의 주장과 어긋났다.
        assertEquals("\u2162\uAD8C", clean("\u2162\uAD8C"))   // Ⅲ권
        assertEquals("\u3007", clean("\u3007"))                 // 〇
        // 사라진다 — 경로 구분자·제어문자·보이지 않는 결합자·원문자
        assertEquals("a_b", clean("a/b"))
        assertEquals("a_b", clean("a\\b"))
        assertEquals("a_b", clean("a\u0000b"))
        assertEquals("a_b", clean("a\u202Eb"))    // RTL override
        assertEquals("a_b", clean("a\u200Db"))    // ZWJ
        assertEquals("a_b", clean("a\u24B6b"))    // Ⓐ 원문자(So)
        assertEquals("a_b", clean("a\u203Fb"))    // ‿ 이음 문장부호(Pc)
    }

    @Test fun constantsCarryNoEngineDivergentShorthand() {
        // 약칭이 상수 안에 숨으면 이 파일이 단일 소스인 의미가 없어진다.
        // (같은 판정을 `tools/check_regex_engine_parity.sh`가 소스 전수에 건다.)
        val fragments = listOf(
            RegexCharClasses.WHITESPACE,
            RegexCharClasses.ASCII_DIGIT,
            RegexCharClasses.ANY_DIGIT,
            RegexCharClasses.FILENAME_KEEP
        )
        val divergent = listOf("\\d", "\\D", "\\s", "\\S", "\\w", "\\W", "\\b", "\\B")
        for (f in fragments) {
            for (d in divergent) {
                assertFalse("엔진이 갈리는 약칭 $d 가 상수에 들어 있다: $f", f.contains(d))
            }
        }
    }

    @Test fun asciiDigitIsExactlyTenAndAnyDigitIsTheUnicodeCategory() {
        val ascii = Regex("[${RegexCharClasses.ASCII_DIGIT}]")
        val any = Regex(RegexCharClasses.ANY_DIGIT)
        assertEquals(10, bmp().count { ascii.matches(String(Character.toChars(it))) })
        // 유니코드 십진 숫자 — 전각/아랍-인도/타이가 여기 든다
        assertTrue(any.matches("１"))   // 전각 1
        assertTrue(any.matches("١"))   // 아랍-인도 1
        assertFalse(ascii.matches("１"))
        // **이것이 수를 파싱하는 자리에서 [0-9]를 쓰는 이유다** — parseDouble은 전각을 거부한다.
        assertEquals(null, "１２３".toDoubleOrNull())
    }
}
