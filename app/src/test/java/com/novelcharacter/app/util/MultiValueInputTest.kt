package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 다중값 입력칸의 결합·분해 (B-186 · R-63).
 *
 * **이 시험이 지키는 방어선은 케이스 수가 아니라 그 안의 셋이다:**
 * ① *여는 것만으로 값이 갈리지 않는다* — 이 판을 부른 결함 그 자체다. 엑셀에서 들여온
 *    `주인공, 남자` 태그 **하나**가 캐릭터를 열었다 저장하는 것만으로 **둘**이 됐다.
 *    채우기와 되쪼개기가 한 벌이 아니면 이 왕복이 깨지고, 갈린 쪽은 되돌릴 방법이 없다.
 * ② *옛 값의 뜻이 한 글자도 바뀌지 않는다* — 따옴표를 글자로 쓰던 값, 쉼표 없는 평범한 값은
 *    종전 `split(",")`·`joinToString(", ")`과 결과가 같다. 이 판은 규칙을 넓히는 것이지
 *    저장된 값을 옮기는 것이 아니다(그래서 마이그레이션이 없다).
 * ③ *엑셀 셀과 모드가 갈린 채로 붙어 있다* — 셀은 CSV 관용 그대로(확정 7-6), 인앱 칸은
 *    *우리가 감쌌을 값*일 때만(확정 14-2의 19번). 둘이 같아지면 어느 한쪽의 확정이 깨진 것이다.
 */
class MultiValueInputTest {

    // ===== ① 왕복 — 이 판이 존재하는 이유 =====

    @Test
    fun roundTrip_tokenWithComma_survivesOpenAndSave() {
        // 엑셀 `태그` 칸에 `"주인공, 남자"`로 적어 들여온 태그 **하나**.
        // 칸에 채웠다(format) 저장하면(parse) 하나 그대로여야 한다 — 종전에는 여기서 둘이 됐다.
        val stored = listOf("주인공, 남자")
        assertEquals(stored, MultiValueInput.parse(MultiValueInput.format(stored)))
    }

    @Test
    fun roundTrip_mixedTokens_keepBoundariesAndOrder() {
        val stored = listOf("주인공, 남자", "기사", "\"이명\"", "가\"나, 다")
        assertEquals(stored, MultiValueInput.parse(MultiValueInput.format(stored)))
    }

    @Test
    fun roundTrip_isStableOnSecondPass() {
        // 사용자가 고치지 않고 여러 번 열었다 닫아도 값이 자라거나 깎이지 않는다
        // (따옴표를 겹쳐 쓰는 규칙이라, 한 벌이 아니면 이 자리에서 이스케이프가 누적된다).
        val stored = listOf("주인공, 남자", "\"별칭\"")
        val once = MultiValueInput.format(stored)
        assertEquals(once, MultiValueInput.format(MultiValueInput.parse(once)))
    }

    // ===== ② 옛 값은 한 글자도 바뀌지 않는다 =====

    @Test
    fun parse_plainText_matchesLegacyBehaviour() {
        assertEquals(listOf("주인공", "남자", "기사"), MultiValueInput.parse("주인공, 남자,기사"))
    }

    @Test
    fun parse_quotesAsCharacters_areKeptVerbatim() {
        // 우리 쓰기 규칙은 이런 값을 결코 이렇게 적지 않으므로(`"별칭"`은 `"""별칭"""`이 된다)
        // 감싼 것으로 읽을 근거가 없다 — 좁힌 모드의 안전장치다.
        assertEquals(listOf("\"별칭\"", "본명"), MultiValueInput.parse("\"별칭\", 본명"))
    }

    @Test
    fun format_valueWithoutDelimiter_matchesLegacyJoin() {
        // 쉼표도 따옴표도 없는 값은 종전 `joinToString(", ")`과 글자가 같다 —
        // 이 판이 기존 화면의 보이는 글자를 흔들지 않는다는 뜻이다.
        val tokens = listOf("주인공", "남자")
        assertEquals(tokens.joinToString(", "), MultiValueInput.format(tokens))
    }

    @Test
    fun parse_blankAndNull_areEmpty() {
        assertEquals(emptyList<String>(), MultiValueInput.parse(null))
        assertEquals(emptyList<String>(), MultiValueInput.parse("   "))
        assertEquals(listOf("가"), MultiValueInput.parse("가, ,"))
    }

    @Test
    fun parse_fullWidthComma_isNotADelimiter() {
        // 좁힌 모드는 전각 정규화를 하지 않는다 — 오늘 한 토큰인 `가，나`가 갈리면
        // 그것이 바로 이 규약이 막으려던 *조용한 뜻 바뀜*이다(B-178이 시험을 세우다 잡은 자리).
        assertEquals(listOf("가，나"), MultiValueInput.parse("가，나"))
    }

    // ===== ③ 셀과 모드가 갈린 채로 붙어 있다 =====

    @Test
    fun narrowMode_differsFromExcelCell_forQuoteOnlyValue() {
        // 같은 글자를 셀은 감싼 것으로, 인앱 칸은 글자 그대로 읽는다. 이 둘이 같아지면
        // 확정 7-6(셀은 CSV 관용)이나 14-2의 19번(인앱은 좁게) 중 하나가 깨진 것이다.
        assertEquals(listOf("전체"), CsvTokens.split("\"전체\""))
        assertEquals(listOf("\"전체\""), MultiValueInput.parse("\"전체\""))
    }

    @Test
    fun format_isTheSameRuleAsTheExcelCell() {
        // 반대로 **쓰는 쪽은 한 규칙이다** — 그래야 앱이 적은 것을 셀도 앱도 같게 읽는다.
        val tokens = listOf("주인공, 남자", "기사")
        assertEquals(CsvTokens.join(tokens), MultiValueInput.format(tokens))
        assertEquals(tokens, CsvTokens.split(MultiValueInput.format(tokens)))
    }

    // ===== 셀렉터 짝 =====

    @Test
    fun format_withSelector_appliesTheSameQuoting() {
        val rows = listOf("주인공, 남자", "기사")
        assertEquals(MultiValueInput.format(rows), MultiValueInput.format(rows) { it })
    }
}
