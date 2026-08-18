package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 쉼표 규칙의 단일 소스 (B-178 · R-47).
 *
 * **이 시험이 지키는 방어선은 케이스 수가 아니라 그 안의 셋이다:**
 * ① *좁힌 모드에서 옛 값의 뜻이 한 글자도 바뀌지 않는다* — B-178의 착수 조건은 *양끝이 따옴표인
 *    기존 값*을 실측하라는 것이었고, 착수 세션에는 사용자 데이터가 없다. 대신 **셀 필요가 없도록**
 *    규칙을 좁혔으므로, 그 좁힘이 무너지면 세지도 못한 값들이 조용히 바뀐다. 되돌려 보면 빨간불이다.
 * ② *엑셀 셀은 좁히지 않는다* — 확정 7-6이 CSV 관용을 고른 이유가 익숙함이라 방언을 만들지 않는다.
 *    두 모드가 같아지면 어느 한쪽의 확정이 깨진 것이다.
 * ③ *쓰기와 읽기가 한 벌이다* — [CsvTokens.quote]가 감싼 것은 [CsvTokens.split]이 정확히 되돌린다.
 */
class CsvTokensTest {

    // ===== ① 좁힌 모드(인앱 값): 우리가 감쌌을 값만 감싼 것으로 본다 =====

    @Test
    fun narrow_wrappedValueWithComma_isOneToken() {
        // 여는 쪽 근거 그 자체 — 지금까지 원리적으로 불가능하던 것이 가능해진다
        assertEquals(
            listOf("홍길동, 어릴 적 이름"),
            CsvTokens.split("\"홍길동, 어릴 적 이름\"", quotedOnlyIfNeeded = true)
        )
    }

    @Test
    fun narrow_wrappedValueWithoutDelimiter_keepsQuotesVerbatim() {
        // **이 케이스가 이 판정의 안전장치다.** 따옴표를 글자로 쓰던 옛 값은 뜻이 바뀌지 않는다 —
        // 우리 쓰기 규칙은 이런 값을 결코 이렇게 적지 않으므로(`"별칭"`은 `"""별칭"""`이 된다),
        // 감싼 것으로 읽을 근거가 없다.
        assertEquals(listOf("\"별칭\""), CsvTokens.split("\"별칭\"", quotedOnlyIfNeeded = true))
        assertEquals(
            listOf("\"별칭\"", "본명"),
            CsvTokens.split("\"별칭\", 본명", quotedOnlyIfNeeded = true)
        )
    }

    @Test
    fun narrow_mixedQuoting_followsTheCellRule() {
        // 사람은 CSV 습관대로 **전부** 감싼다(`"가, 나", "다"`). 칸마다 따져 하나라도 불필요하면
        // 통째로 옛 규칙에 넘기면, 엑셀 셀은 한 값으로 읽는 같은 글자가 **앱에서만 갈린다** —
        // 통일이 목적인 판정이 정작 두 규칙을 다시 만드는 자리다(콜드 검토가 잡았다).
        assertEquals(listOf("가, 나", "다"), CsvTokens.split("\"가, 나\", \"다\"", quotedOnlyIfNeeded = true))
        assertEquals(listOf("가, 나", "다"), CsvTokens.split("\"가, 나\", \"다\""))
    }

    @Test
    fun narrow_wrappedValueWithEscapedQuote_isUnwrapped() {
        // 우리가 감쌌을 모양(안에 따옴표가 있어 이스케이프가 필요했다)이면 되돌린다
        assertEquals(listOf("a\"b"), CsvTokens.split("\"a\"\"b\"", quotedOnlyIfNeeded = true))
    }

    @Test
    fun narrow_midTokenQuote_isLiteral() {
        // 확정이 못박은 문장: "토큰 중간의 따옴표는 글자 그대로 — 지금과 한 글자도 다르지 않다"
        assertEquals(listOf("a\"b", "c"), CsvTokens.split("a\"b, c", quotedOnlyIfNeeded = true))
    }

    // ===== ② 엑셀 셀(넓은 모드)은 CSV 관용 그대로 =====

    @Test
    fun wide_wrappedValueWithoutDelimiter_isUnwrapped() {
        // R-47이 감추지 않고 명시로 잠근 그 자리 — 엑셀도 똑같이 동작한다.
        // 좁힌 모드와 **여기서 갈리는 것이 설계**이고, 같아지면 어느 한쪽 확정이 깨진 것이다.
        assertEquals(listOf("전체"), CsvTokens.split("\"전체\""))
        assertEquals(listOf("\"전체\""), CsvTokens.split("\"전체\"", quotedOnlyIfNeeded = true))
    }

    @Test
    fun wide_quotedComma_isOneToken() {
        assertEquals(listOf("Smith, John", "Alice"), CsvTokens.split("\"Smith, John\", Alice"))
    }

    // ===== 옛 값 경로 — 따옴표가 없거나 규칙에 어긋나면 종전 구현 그대로 =====

    @Test
    fun noQuotes_behavesExactlyAsBefore() {
        val legacy = "가, 나 ,다,, 라"
        val expected = legacy.split(",").map { it.trim() }.filter { it.isNotBlank() }
        assertEquals(expected, CsvTokens.split(legacy))
        assertEquals(expected, CsvTokens.split(legacy, quotedOnlyIfNeeded = true))
    }

    @Test
    fun malformedQuotes_fallBackToLegacy() {
        // 닫히지 않은 따옴표
        assertEquals(listOf("\"인용 시리즈", "다음"), CsvTokens.split("\"인용 시리즈, 다음"))
        // 닫은 뒤 군더더기
        assertEquals(listOf("\"인용\" 시리즈"), CsvTokens.split("\"인용\" 시리즈"))
        assertEquals(listOf("\"인용\" 시리즈"), CsvTokens.split("\"인용\" 시리즈", quotedOnlyIfNeeded = true))
    }

    @Test
    fun fullWidthComma_isSeparatorForCellsOnly() {
        // 엑셀 셀은 CJK 입력기의 전각 쉼표를 관대 수용한다(F4) — 사람이 손으로 고치는 자리다.
        assertEquals(listOf("가", "나"), CsvTokens.split("가，나"))
        // **인앱 값은 그러지 않는다.** 오늘 한 토큰인 값이 두 개로 갈리면 그것이 바로 이 판정이
        // 막으려던 조용한 뜻 바뀜이다 — 규칙을 옮겨 오면서 정규화가 딸려 왔던 것을 이 시험이 잡았다.
        assertEquals(listOf("가，나"), CsvTokens.split("가，나", quotedOnlyIfNeeded = true))
        assertEquals(listOf("가，나"), CsvTokens.split("\"가，나\"", quotedOnlyIfNeeded = true))
    }

    // ===== ③ 쓰기와 읽기가 한 벌 =====

    @Test
    fun joinThenSplit_roundTripsInBothModes() {
        val tokens = listOf("홍길동, 어릴 적 이름", "a\"b", "평범")
        val joined = CsvTokens.join(tokens)
        assertEquals(tokens, CsvTokens.split(joined))
        assertEquals(tokens, CsvTokens.split(joined, quotedOnlyIfNeeded = true))
    }

    @Test
    fun joinThenSplit_fullWidthComma_survivesInAppButNormalizesInCells() {
        val joined = CsvTokens.join(listOf("가，나"))
        // 인앱 값은 글자 그대로 돌아온다
        assertEquals(listOf("가，나"), CsvTokens.split(joined, quotedOnlyIfNeeded = true))
        // 엑셀 셀은 정규화한 모양으로 돌아온다 — R-47 이전부터의 동작이고, 감싸 두었기에
        // **갈라지지는 않는다**(감싸지 않으면 두 값으로 파손된다. 그것이 감싸기 판정이 정규화한
        // 모양을 보는 이유다)
        assertEquals(listOf("가,나"), CsvTokens.split(joined))
    }

    /**
     * **세 모드가 두 축의 조합이다** (B-261) — 종전에는 두 축이 `quotedOnlyIfNeeded` 하나에
     * 묶여 있어 *따옴표는 셀 관용, 전각은 원문 그대로*라는 조합을 고를 수 없었다.
     *
     * 그 조합이 필요한 자리는 **토큰이 식별자인 셀**이다(`대결 축` 시트의 필드 연결) —
     * 값을 *찾는* 이름과 달리 **글자 그대로가 곧 정체**라, 전각을 고쳐 읽으면 왕복 한 번에
     * 어느 필드도 가리키지 않는 죽은 연결이 된다.
     */
    @Test
    fun identifierCellMode_keepsCsvQuotingButNotFullWidthNormalization() {
        // 감싸기는 셀 관용 그대로다 — 감싼 칸이 감쌀 필요가 없었어도 감싼 것으로 읽는다
        assertEquals(listOf("전체"), CsvTokens.split("\"전체\"", normalizeFullWidth = false))
        assertEquals(listOf("가, 나"), CsvTokens.split("\"가, 나\"", normalizeFullWidth = false))
        // 전각은 손대지 않는다 — 구분자로도 읽지 않고 내용도 고치지 않는다
        assertEquals(listOf("가，나"), CsvTokens.split("가，나", normalizeFullWidth = false))
        assertEquals(listOf("ｐｏｗｅｒ"), CsvTokens.split("ｐｏｗｅｒ", normalizeFullWidth = false))
        assertEquals(listOf("가，나"), CsvTokens.split(CsvTokens.join(listOf("가，나")), normalizeFullWidth = false))
    }

    /** 기본값은 **오늘까지의 두 부름을 글자 하나 안 바꾼다** — 셀은 정규화하고 인앱 값은 안 한다. */
    @Test
    fun normalizeFullWidth_defaultsToTheOppositeOfQuotedOnlyIfNeeded() {
        assertEquals(CsvTokens.split("가，나", normalizeFullWidth = true), CsvTokens.split("가，나"))
        assertEquals(
            CsvTokens.split("가，나", quotedOnlyIfNeeded = true, normalizeFullWidth = false),
            CsvTokens.split("가，나", quotedOnlyIfNeeded = true)
        )
    }

    @Test
    fun quote_onlyWhenDelimiterPresent() {
        assertEquals("평범", CsvTokens.quote("평범"))
        assertEquals("\"가, 나\"", CsvTokens.quote("가, 나"))
        assertEquals("\"a\"\"b\"", CsvTokens.quote("a\"b"))
        // 전각 쉼표를 품은 값도 감싼다 — 읽는 쪽이 정규화 뒤에 쪼개기 때문
        assertEquals("\"가，나\"", CsvTokens.quote("가，나"))
    }

    @Test
    fun needsQuoting_isTheSharedPredicate() {
        // 좁힌 모드의 인정 조건과 감싸기 조건이 **같은 술어**여야 왕복이 성립한다.
        // 갈리면 우리가 쓴 값을 우리가 못 읽는다.
        for (v in listOf("평범", "가, 나", "a\"b", "가，나", "")) {
            val quoted = CsvTokens.quote(v)
            val wrapped = quoted.startsWith("\"") && quoted.endsWith("\"") && quoted.length >= 2
            assertEquals("감싸기 판정이 갈렸다: '$v'", CsvTokens.needsQuoting(v), wrapped)
        }
    }

    /**
     * **빈 칸은 토큰 0개다** — 빈 토큰 하나가 아니다.
     *
     * 이 계약을 여기서 못박는 것은 **'앱 설정' 시트의 목록 키 둘이 그 위에 서 있기 때문**이다
     * (B-226 — `stats_pattern_types`·`assistant_categories`). 그 바인딩은 *"적었는데 하나도
     * 해석되지 않았다"*(`names.isNotEmpty() && known.isEmpty()`)를 거절-유지의 조건으로 쓰는데,
     * 만약 빈 칸이 `[""]`로 쪼개지면 **빈 칸까지 거절**이 되어 *'전부 끔'을 저장할 길이
     * 사라진다** — 사용자가 전부 꺼 둔 설정을 왕복으로 되살릴 수 없게 만드는 것과 정반대
     * 방향의 결함이고, 조건 한 줄이 조용히 뒤집히는 자리다.
     *
     * 공백만 든 칸도 같다(엑셀에서 지우다 만 셀이 그 모양이다).
     */
    @Test
    fun `빈 칸은 토큰이 없다`() {
        assertEquals(emptyList<String>(), CsvTokens.split(""))
        assertEquals(emptyList<String>(), CsvTokens.split("   "))
        assertEquals(emptyList<String>(), CsvTokens.split(","))
        // 빈 토큰이 섞여도 그것만 빠진다 — 나머지는 그대로 산다.
        assertEquals(listOf("a", "b"), CsvTokens.split("a, ,b"))
    }
}
