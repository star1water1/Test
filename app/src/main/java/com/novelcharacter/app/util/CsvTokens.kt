package com.novelcharacter.app.util

/**
 * **쉼표로 갈리는 값의 규칙 — 앱 전체의 단일 소스** (순수 계층, Android 비의존).
 *
 * 이 규칙을 쓰는 자리는 둘이고 **한 벌이어야 한다**:
 * - **엑셀 목록 셀**(참여 캐릭터·태그·별칭처럼 앱이 이름을 이어 붙였다 되쪼개는 칸) — 규약 R-47.
 *   `excel/SheetSpec.kt`의 `joinCsv`/`splitCsv`가 이 객체를 부른다.
 * - **인앱 필드값 토큰**(통계·폼 자동완성·값 라이브러리·검색) — [FieldValueTokenizer].
 *
 * 종전에는 앞쪽만 따옴표를 알고 뒤쪽은 `split(",")`이라 **같은 앱 안에서 같은 개념이 두 규칙**이었다
 * (B-178). `태그` 칸에 `"주인공, 남자"`라 적으면 한 값으로 들어오는데 복수 텍스트 필드 칸에 똑같이
 * 적으면 따옴표째 두 토큰으로 갈렸다.
 *
 * **규칙이 여기 사는 이유(계층):** `util`은 아무도 바라보지 않는 순수 계층이라 실행 검증이 되는
 * 거의 유일한 자리다(아키텍처 2장). 규칙을 `excel/`에 두면 `util`이 `excel`을 바라보게 되어
 * 의존 방향이 뒤집힌다 — 그래서 **정의는 여기, 엑셀 쪽 이름(`joinCsv`/`splitCsv`)은 위임**이다.
 *
 * ## 옛 값을 계속 읽는 경로가 이 규칙의 절반이다
 *
 * 저장·교환 형식이라 되돌리기 어려운 축이다(착수 규칙 3번 셋째). 따옴표가 없으면 [splitLegacy]가
 * 그대로 돌고, 그것이 **종전 구현 글자 그대로**다. 따옴표가 있어도 CSV 규칙에 어긋나면
 * (닫히지 않음 · 닫은 뒤 군더더기) 옛 규칙으로 되돌린다 — `"인용" 시리즈`처럼 따옴표를 글자로
 * 쓰던 값이 그대로 살아난다. 거부가 아니라 수용이다(개발 의도 4번).
 */
object CsvTokens {

    /**
     * 쉼표로 나눈다. 값 안의 쉼표는 **따옴표로 감싼다**(엑셀·CSV와 같은 방식 — 사용자 확정
     * 2026.08.04, `judgment_confirmations_2026-08.md` 7-6): `"Smith, John", Alice`
     * → [`Smith, John`, `Alice`].
     *
     * @param quotedOnlyIfNeeded **감싼 칸을 인정하는 조건을 좁힌다.** false면 CSV 관용 그대로라
     *   `"전체"`가 `전체`로 읽힌다(엑셀도 똑같이 동작한다 — 엑셀 셀은 그 익숙함이 규약을 고른
     *   이유이므로 방언으로 피하지 않는다). true면 **우리 쓰기 규칙([quote])이 그렇게 감쌌을
     *   값일 때만** 감싼 것으로 본다 — 즉 안쪽에 쉼표나 따옴표가 있을 때만이다.
     *
     *   **인앱 필드값이 true인 이유가 이 판정의 안전장치다**(B-178 착수 조건). 좁히지 않으면
     *   *양끝이 따옴표인 기존 값* 전부가 뜻이 바뀌는데, 그 집합은 사용자 데이터를 봐야 셀 수 있고
     *   착수 세션에는 그 데이터가 없다. 좁히면 뜻이 바뀌는 값이 **따옴표 안에 쉼표가 든 값뿐**이고,
     *   그것들은 지금도 `"a` · `b"`로 갈려 있어 **잃을 뜻이 애초에 없다** — 세지 않고도 위험이 0이다.
     *
     *   **좁힌 모드는 전각 정규화도 하지 않는다.** 엑셀 셀은 CJK 입력기가 넣은 전각 쉼표(，)를
     *   구분자로 받아 주지만(F4 — 사람이 손으로 고치는 자리라 관대함이 값어치다), 인앱 값에
     *   그 관대함을 들여오면 **오늘 한 토큰인 `가，나`가 두 토큰으로 갈린다.** 그것은 바로 이
     *   판정이 막으려던 *조용한 뜻 바뀜*이라, 좁힌 모드는 원문 그대로만 본다
     *   (실제로 이 시험을 세우다 잡았다 — 규칙을 옮겨 오면서 정규화가 딸려 왔었다).
     */
    fun split(value: String, quotedOnlyIfNeeded: Boolean = false): List<String> {
        val source = if (quotedOnlyIfNeeded) value else toHalfWidth(value)
        if (!source.contains('"')) return splitLegacy(source)
        return parseQuoted(source, quotedOnlyIfNeeded) ?: splitLegacy(source)
    }

    /** 종전 구현 그 자체 — 따옴표가 없거나 CSV 규칙에 어긋날 때의 경로. */
    fun splitLegacy(normalized: String): List<String> =
        normalized.split(",").map { it.trim() }.filter { it.isNotBlank() }

    /** 토큰들을 하나의 칸으로 잇는다 — [split]의 짝. 구분자를 품은 값만 감싼다. */
    fun join(values: Collection<String>): String = values.joinToString(", ") { quote(it) }

    /** 셀렉터를 받는 짝 — 호출부가 `joinToString(", ")`으로 되돌아가지 않게 한다. */
    fun <T> join(values: Collection<T>, selector: (T) -> String): String =
        values.joinToString(", ") { quote(selector(it)) }

    /**
     * 한 값의 감싸기 판정. **감쌀지는 정규화한 모양으로 정한다** — 읽는 쪽이 [toHalfWidth] 뒤에
     * 파싱하므로 전각 쉼표(，)를 품은 값도 감싸지 않으면 그쪽에서 쪼개진다.
     */
    fun quote(value: String): String {
        if (!needsQuoting(value)) return value
        // 따옴표만 반각으로 고정한다 — 전각 따옴표를 그대로 두면 읽는 쪽의 정규화가
        // 그것을 따옴표로 바꿔 이스케이프 자리와 어긋난다(다른 전각 글자는 건드리지 않는다).
        return "\"" + value.replace('＂', '"').replace("\"", "\"\"") + "\""
    }

    /** 이 값은 감싸야 하는가 — [quote]와 [split]의 좁힌 판정이 **같은 술어**를 보게 한다. */
    fun needsQuoting(value: String): Boolean =
        toHalfWidth(value).any { it == ',' || it == '"' }

    /**
     * 전각 ASCII(U+FF01–FF5E)를 반각으로 정규화한다. 엑셀에 CJK 입력기로 넣은 전각 쉼표(，)·
     * 전각 영숫자(Ｙ／１ 등)를 관대하게 수용하기 위한 공용 유틸 (F4). CJK 문자(예/참 등)는 그대로 둔다.
     */
    fun toHalfWidth(value: String): String {
        if (value.none { it.code in 0xFF01..0xFF5E }) return value
        return buildString(value.length) {
            for (c in value) append(if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c)
        }
    }

    /**
     * 표준 CSV 규칙으로 판다. 규칙에 어긋나면 **null** — 호출측이 옛 규칙으로 되돌린다.
     * 따옴표는 **칸의 첫 글자일 때만** 감싼 것이고, 그 안의 `""`는 따옴표 한 글자다.
     */
    private fun parseQuoted(s: String, quotedOnlyIfNeeded: Boolean): List<String>? {
        val fields = ArrayList<String>()
        var i = 0
        while (true) {
            // **공백의 뜻을 아래 `trim()`과 맞춘다.** 칸 앞을 `' '`만 건너뛰면, 엑셀에서 줄바꿈
            // (Alt+Enter)이나 붙여넣기로 들어온 탭이 앞에 붙는 순간 감싼 칸을 못 알아보고
            // 옛 규칙으로 쪼개진다 — 사용자는 규약대로 감쌌는데 아무 말 없이 값이 갈린다.
            // 판정에 실패하면 어차피 옛 규칙으로 되돌아가므로 넓히는 쪽이 손해가 없다.
            while (i < s.length && s[i].isWhitespace()) i++
            if (i < s.length && s[i] == '"') {
                i++
                val buf = StringBuilder()
                var closed = false
                while (i < s.length) {
                    if (s[i] == '"') {
                        if (i + 1 < s.length && s[i + 1] == '"') { buf.append('"'); i += 2 }
                        else { i++; closed = true; break }
                    } else { buf.append(s[i]); i++ }
                }
                if (!closed) return null                       // 닫히지 않은 따옴표
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] != ',') return null   // 닫은 뒤 군더더기
                // 좁힌 모드: 우리 쓰기 규칙이 감쌌을 값이 아니면 **감싼 것으로 보지 않는다**.
                // 칸 하나라도 걸리면 칸 단위로 봐주지 않고 통째로 옛 규칙에 넘긴다 —
                // 한 칸만 벗기고 다른 칸은 두면 같은 셀 안에서 규칙이 갈린다.
                if (quotedOnlyIfNeeded && !needsQuoting(buf.toString())) return null
                fields.add(buf.toString())
            } else {
                val next = s.indexOf(',', i)
                val end = if (next < 0) s.length else next
                fields.add(s.substring(i, end).trim())
                i = end
            }
            if (i < s.length && s[i] == ',') { i++ } else break
        }
        return fields.filter { it.isNotBlank() }
    }
}
