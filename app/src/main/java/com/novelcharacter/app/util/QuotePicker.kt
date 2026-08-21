package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterQuote

/**
 * **명대사 여럿 중 어느 것을 띄우는가** (사용자 요청 2026.08.20).
 *
 * 사용자 확정 셋이 이 객체의 전부다:
 *
 * | 물음 | 답 |
 * |---|---|
 * | 생일 전용 대사를 안 적었으면 | **일반 명대사 중 하나를 대신** 띄운다. 그것도 없으면 줄 자체가 없다 |
 * | 여럿이면 어느 것을 | **그날그날 무작위** — 열 때마다 다른 대사 |
 * | 같은 날 두 자리에 뜨면 | **같은 대사**여야 한다 |
 *
 * ## 왜 `Random`이 아니라 셈인가
 *
 * 마지막 줄이 그 이유다. 생일 모달과 대결 카드가 같은 날 같은 캐릭터를 그리는데 각자
 * 주사위를 굴리면 **한 화면에서 다른 대사가 뜬다** — 사용자가 보기에 앱이 둘 중 무엇을
 * 이 캐릭터의 대사로 여기는지 알 수 없다. 그래서 고르기를 `(날짜, 캐릭터)`의 **함수**로
 * 둔다: 부르는 자리가 몇이든 같은 날 같은 캐릭터면 답이 하나다.
 *
 * 부수 효과 하나가 더 있다 — **회전과 재진입에도 대사가 안 바뀐다.** 모달을 닫았다
 * 다시 열거나 화면을 돌렸을 때 대사가 갈리면 그것은 무작위가 아니라 고장으로 읽힌다.
 *
 * ## 씨앗은 밖에서 받는다
 * [dayStamp]를 인자로 받는 것은 이 계층이 **순수해야 시험이 실제로 돌기 때문**이다
 * (`Calendar`를 안에서 부르면 *"내일은 다른 대사가 뜨는가"*를 시험이 물을 수 없다).
 * 오늘의 값은 [todayStamp]가 낸다.
 */
object QuotePicker {

    /**
     * 생일 자리에 띄울 대사 — **생일 전용이 먼저고, 없으면 그 밖의 것**.
     *
     * 되돌아가는 순서가 요점이다. *"생일 대사를 적었으면 그것"*이 사용자가 적은 뜻이고,
     * 안 적었다고 축하 자리에 빈 줄을 두는 것보다 **그 캐릭터의 명대사 하나**가 낫다
     * (사용자 확정). 둘 다 없으면 `null`이고, 그때 화면은 **줄을 지운다** — 없는 것을
     * *"대사 없음"*으로 적어 자리를 잡아 두지 않는다.
     */
    fun forBirthday(quotes: List<CharacterQuote>, characterId: Long, dayStamp: Long): CharacterQuote? {
        val birthday = quotes.filter { it.isBirthday }
        if (birthday.isNotEmpty()) return pick(birthday, characterId, dayStamp)
        return pick(quotes.filterNot { it.isBirthday }, characterId, dayStamp)
    }

    /**
     * 생일이 아닌 자리(대결 카드 등)에 띄울 대사 — **생일 전용은 뺀다.**
     *
     * *"생일 전용"*은 사용자가 *이 대사는 생일에만*이라고 적어 둔 것이라, 그것을 평소
     * 카드에 띄우면 사용자가 그은 구분이 앱에서 없어진다. **사용자가 만든 그 밖의 상황은
     * 빼지 않는다** — 그쪽은 *어디서 한 말인가*를 적어 둔 것이지 *언제만 보이라*가 아니다.
     */
    fun forDisplay(quotes: List<CharacterQuote>, characterId: Long, dayStamp: Long): CharacterQuote? =
        pick(quotes.filterNot { it.isBirthday }, characterId, dayStamp)

    /**
     * 목록에서 하나 — 비었으면 `null`, 하나뿐이면 셈 없이 그것.
     *
     * **차례가 답을 정한다**([CharacterQuoteDao]가 어디서든 `sortOrder ASC, id ASC`로
     * 낸다). 목록 순서가 자리마다 다르면 같은 씨앗이 다른 대사를 집으므로, 이 함수의
     * 계약은 *"같은 씨앗 + 같은 목록 = 같은 답"*이지 *"같은 씨앗 = 같은 답"*이 아니다.
     */
    fun pick(quotes: List<CharacterQuote>, characterId: Long, dayStamp: Long): CharacterQuote? {
        if (quotes.isEmpty()) return null
        if (quotes.size == 1) return quotes[0]
        val index = (mix(dayStamp, characterId) % quotes.size).toInt()
        return quotes[index]
    }

    /**
     * 씨앗 둘을 섞어 **음수 아닌** 값 하나로.
     *
     * splitmix64의 마무리 셈이다 — 이웃한 씨앗(어제·오늘, 옆 캐릭터)이 이웃한 값을 내지
     * 않아야 *"매일 다른 대사"*가 실제로 돌아간다. 단순히 더해 나머지를 취하면 대사가
     * 세 개인 캐릭터에서 **사흘 주기로 같은 차례**가 돌아 무작위로 보이지 않는다.
     *
     * 마지막에 최상위 비트를 떨어뜨리는 것은 `%`가 음수를 내지 않게 하려는 것이다
     * (Kotlin의 `%`는 피제수의 부호를 따른다 — 그대로 두면 `IndexOutOfBounds`다).
     */
    private fun mix(dayStamp: Long, characterId: Long): Long {
        var z = dayStamp * 0x9E3779B97F4A7C15uL.toLong() + characterId * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        z = z xor (z ushr 31)
        return z and Long.MAX_VALUE
    }

    /**
     * 오늘의 씨앗 — `yyyyMMdd`.
     *
     * **날짜를 수로 읽는 것이 요점이다.** 밀리초를 그대로 쓰면 같은 날에도 값이 달라져
     * 열 때마다 대사가 바뀌고, 그러면 위의 *"같은 날 두 자리에 같은 대사"*가 깨진다.
     * 자정을 넘기면 값이 바뀌어 저절로 다음 대사로 넘어간다.
     */
    fun todayStamp(calendar: java.util.Calendar = java.util.Calendar.getInstance(java.util.Locale.US)): Long {
        val y = calendar.get(java.util.Calendar.YEAR).toLong()
        val m = (calendar.get(java.util.Calendar.MONTH) + 1).toLong()
        val d = calendar.get(java.util.Calendar.DAY_OF_MONTH).toLong()
        return y * 10000 + m * 100 + d
    }
}
