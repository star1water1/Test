package com.novelcharacter.app.util

/**
 * **쉼표로 여러 값을 담는 입력칸의 결합·분해 — 한 쌍이 든다** (순수 계층, Android 비의존).
 *
 * 태그·이명·별칭·선택지처럼 **한 줄에 여러 값을 쉼표로 적는 칸**은 언제나 두 반쪽이다:
 * 저장된 토큰들을 칸에 **채우고**(`format`), 사용자가 고친 글자를 다시 **되쪼갠다**(`parse`).
 * 이 둘이 같은 규칙을 지나지 않으면 **왕복 한 번에 값이 갈린다.**
 *
 * ## 왜 이름을 새로 세웠는가 — 두 반쪽이 서로 다른 파일에 살았다
 *
 * 규칙 자체는 [CsvTokens]가 이미 들고 있었고 [FieldValueTokenizer.splitMulti]가 그것을 인앱
 * 이름으로 열어 두었다. 그런데 **되쪼개는 쪽만 이름이 있었다** — 채우는 쪽은 자리마다
 * `joinToString(", ")`이라 짝이 없었고, 실제로 한 칸의 두 반쪽이 다른 파일에 갈라져 있었다
 * (태그: 채우기는 `CharacterEditFragment`, 되쪼개기는 `CharacterSaveCoordinator`). 그래서
 * **여는 것만으로 값이 갈렸다**: 엑셀에서 `"주인공, 남자"`로 들여온 태그 **하나**가 캐릭터를
 * 열었다 저장하는 순간 `주인공` · `남자` **둘**이 됐다 — 사용자는 아무것도 고치지 않았는데.
 *
 * R-47이 엑셀 셀에서 배운 것이 그대로 반복된 자리다(*"붙이는 쪽만 고치면 파일이 스스로를
 * 못 읽는다"*). 그래서 **이 객체는 규칙이 아니라 *짝*을 든다** — 정의는 여전히 [CsvTokens]이고
 * 여기 둘은 위임이지만, **한 객체 안에 둘이 나란히 있는 것 자체가 이 이름의 값어치다.**
 *
 * ## 좁힌 모드를 쓴다 (`quotedOnlyIfNeeded`)
 *
 * 엑셀 셀은 CSV 관용 그대로지만(익숙함이 규약을 고른 이유다 — 확정 7-6), **인앱 칸은
 * *우리가 감쌌을 값*일 때만** 감싼 것으로 읽는다([FieldValueTokenizer.splitMulti]와 같은 모드).
 * 따옴표를 글자로 쓰던 옛 값(`"별칭", 본명`)이 한 글자도 바뀌지 않게 하는 장치다(확정 14-2의 19번).
 *
 * > **저장된 값은 바뀌지 않는다 — 바뀌는 것은 칸에 보이는 글자다.** 쉼표나 따옴표를 품은
 * > 값은 이제 칸에서 `"…"`로 감싸 보인다(엑셀 셀에 이미 그렇게 나간다). 마이그레이션이
 * > 필요 없는 것은 그 때문이다: DB에 실린 토큰은 그대로이고, 감싸기는 *칸에 담는 동안*만이다.
 */
object MultiValueInput {

    /**
     * 토큰들을 입력칸 한 줄로 만든다 — [parse]의 짝.
     * 쉼표나 따옴표를 품은 값만 감싸므로, 그렇지 않은 값은 종전 `joinToString(", ")`과 글자가 같다.
     */
    fun format(tokens: Collection<String>): String = CsvTokens.join(tokens)

    /**
     * 셀렉터를 받는 짝 — 호출부가 `joinToString(", ") { … }`로 되돌아가지 않게 한다
     * (`joinCsv`가 같은 이유로 이 꼴을 함께 연다).
     */
    fun <T> format(tokens: Collection<T>, selector: (T) -> String): String =
        CsvTokens.join(tokens, selector)

    /**
     * 입력칸의 글자를 토큰으로 되쪼갠다 — [format]의 짝.
     * 빈 칸과 공백은 [CsvTokens]가 이미 걷어내므로 호출부가 다시 거를 필요가 없다.
     */
    fun parse(text: String?): List<String> =
        CsvTokens.split(text.orEmpty(), quotedOnlyIfNeeded = true)
}
