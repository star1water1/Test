package com.novelcharacter.app.ai

/**
 * 양식의 자리표를 실제 값으로 펼친다 — **순수**.
 *
 * ## 한 번만 훑는다 (프롬프트 조작 방어)
 *
 * 펼친 결과를 **다시 훑지 않는다.** 다시 훑으면 캐릭터 메모에 `{{추천할필드}}`라고 적어 둔
 * 사람이 프롬프트의 구조를 바꿀 수 있다 — 데이터가 지시가 되는 자리다. [expand]가 쓰는
 * `Regex.replace`는 바꾼 자리를 되읽지 않으므로 그 성질이 구현에서 그대로 나온다.
 *
 * ## 빈 자리는 줄째로, 빈 절은 머리까지 사라진다
 *
 * 종전 조립기는 `if (tags.isNotEmpty())`로 절마다 가드를 걸었다. 그 일을 두 규칙이 대신한다.
 *
 * 1. **자리표가 든 줄은 그 줄의 자리표가 전부 비면 사라진다.** 안 그러면 `태그: `처럼
 *    이름표만 남은 줄이 나가고, 모델은 *"태그가 빈 값이다"*라는 없는 사실을 읽는다.
 * 2. **몸이 통째로 비워진 절은 머리까지 사라진다.** `[원문]`만 남고 원문이 없으면 모델은
 *    원문이 빈 글이라고 읽는다 — 이어쓰기가 거기서 무너진다.
 *
 *    **절의 몸은 머리 바로 뒤부터 첫 빈 줄까지다**, 그리고 **원래부터 몸이 없던 머리는
 *    건드리지 않는다.** 앞은 다음 절의 몸을 제 것으로 세지 않게 하고, 뒤는 사용자가 보기
 *    좋으라고 머리 아래 넣은 빈 줄에 절 이름이 죽지 않게 한다. 지우는 것은 *있던 몸이
 *    전부 비워진* 절뿐이다.
 *
 * ## 중괄호는 **닫는 쪽까지** 이스케이프한다 (2026.08.21 크래시)
 *
 * 아래 정규식들은 `\{\{…\}\}`처럼 여는 쪽과 닫는 쪽을 **모두** 이스케이프하고, 문자 클래스
 * 안에서도 `[^\{\}]`로 적는다. 군더더기로 보이지만 **뺄 수 없다.**
 *
 * 데스크톱 JVM의 `java.util.regex`는 홀로 선 `}`를 글자 그대로 읽어 준다. **기기는 아니다** —
 * 안드로이드의 `java.util.regex`는 ICU 엔진(`com.android.icu.util.regex`)이고, ICU는 홀로 선
 * `}`를 **문법 오류로 거절한다**(`U_REGEX_RULE_SYNTAX`).
 *
 * **여는 쪽 `{`는 이 축이 아니다** — 홀로 선 `{`는 **두 엔진 다** 거절한다(OpenJDK는
 * *"Illegal repetition"*, ICU는 `U_REGEX_BAD_INTERVAL`). 갈리는 것은 닫는 쪽 하나다.
 * **문자 클래스 안도 이 축이 아니다** — `[^{}]`와 `[^\{\}]`는 두 엔진에서 **글자 단위로 같다**
 * (BMP 전수 대조: 양쪽 63,486자). 콜드 검토가 잡은 정정이다: 종전 이 KDoc은 *"ICU의 클래스는
 * UnicodeSet 문법이라 `[^{}]`가 거절된다"*고 적었는데 **사실이 아니었다.** `TOKEN_SHAPED`가
 * 거절된 진짜 이유는 그 패턴 **끝의 날 `}}`** 하나뿐이다.
 * (클래스 안까지 이스케이프해 둔 것은 그대로 둔다 — 뜻이 같고, 한 파일 안에서 표기가
 * 갈리지 않는 편이 읽기 쉽다.)
 *
 * 그래서 `\{\{([가-힣A-Za-z0-9]+)}}`는 **JVM 시험을 전부 통과한 채 기기에서만 죽었다.**
 * 이 정규식들은 `object`의 초기화식이라 하나만 거절돼도 `<clinit>`이 통째로 터지고, 자리표를
 * 쓰는 **모든** 경로가 `ExceptionInInitializerError`로 함께 넘어간다 — 이미지탭의 AI 태그
 * 제안이 그렇게 튕겼다(`ImageBatchTagSuggester.buildSystemPrompt`).
 *
 * **이스케이프는 값을 바꾸지 않는다** — `\}`도 `}`도 닫는 중괄호 한 글자다. R-37이 날
 * 제어문자에 대해 같은 판단을 했다: 값을 바꾸지 말고 **표기만** 바꾼다.
 *
 * **두 규칙 다 펼치기 *전에* 양식의 줄을 보고 판정한다.** 펼친 뒤의 글자를 보면 값 안의
 * `[...]`가 절 머리로 읽혀 위의 조작 방어가 뒷문으로 열린다.
 */
object PromptTokens {

    /**
     * 자리표 문법 — `{{이름}}`.
     *
     * **홑중괄호가 아닌 이유:** 양식 본문에 `{"suggestions":[…]}` 같은 JSON 보기가 그대로
     * 들어 있다. 홑중괄호를 자리표로 읽으면 사용자가 형식 보기를 적는 순간 *"모르는 자리표"*로
     * 거절된다. **`@`를 안 쓴 이유:** 엑셀이 칸 첫 글자 `@`를 함수 호출로 읽는다
     * (`DuelSystemFields`가 같은 이유로 `sys:`를 골랐다) — 이 글은 엑셀 칸에서도 편집된다.
     *
     * 이름은 공백 없는 한글·영문·숫자다. 관용(`{{ 이름 }}`)을 두지 않는 것은 일부러다 —
     * 관용을 두면 같은 뜻이 두 표기로 갈리고, 대신 [PromptTemplateValidator]가 앞뒤 공백을
     * **따로 짚어** 알린다(눈으로는 옳아 보이는데 안 먹는 부류라 뭉뚱그리면 못 고친다).
     */
    private val TOKEN = Regex("""\{\{([가-힣A-Za-z0-9]+)\}\}""")

    /**
     * **자리표처럼 생긴 것 전부** — 성립하는 것과 아닌 것을 가리지 않는다.
     *
     * 검증이 이것과 [namesIn]의 차집합을 *"자리표를 쓰려다 만 글"*로 짚는다. 그 차집합이
     * 없으면 `{{캐릭터 명}}`·`{{}}`처럼 **눈으로는 자리표인데 글자 그대로 나가는 것**이
     * 아무 말 없이 실려 나간다 — 사용자는 그 자리가 왜 안 채워졌는지 알 길이 없다.
     *
     * `[^\{\}]*`라 중괄호를 품지 않는다 — 양식 본문의 JSON 보기(`{"a":{"b":1}}`)에 걸리지 않는다.
     */
    private val TOKEN_SHAPED = Regex("""\{\{[^\{\}]*\}\}""")

    /** 앞뒤에 공백을 둔 자리표 — 검증이 이것만 따로 짚는다. */
    val PADDED_TOKEN = Regex("""\{\{[ \t]+[가-힣A-Za-z0-9]+[ \t]*\}\}|\{\{[ \t]*[가-힣A-Za-z0-9]+[ \t]+\}\}""")

    /** 절 머리 — 줄이 `[`로 시작하고 `]`가 있다. `[원문]`도 `[문체 참고] 설명…`도 절 머리다. */
    private val SECTION_HEAD = Regex("""^\[[^\[\]]*].*$""")

    /**
     * **자리표를 쓰려다 만 글** — `{{…}}` 꼴인데 자리표로 성립하지 않는 것.
     *
     * `{{캐릭터 명}}`(이름 안에 공백) · `{{}}`(이름이 없음) · `{{a-b}}`(어휘 밖 글자)가 여기
     * 걸린다. 이것들은 **아무 말 없이 글자 그대로 실려 나간다** — [namesIn]이 못 보므로
     * "모르는 이름"으로도 짚이지 않고, 사용자는 그 자리가 왜 안 채워졌는지 알 길이 없다.
     * [PADDED_TOKEN]이 이미 따로 짚는 부류는 여기서 뺀다 — 한 자리를 두 번 나무라지 않는다.
     */
    fun malformedIn(template: String): List<String> =
        TOKEN_SHAPED.findAll(template)
            .map { it.value }
            .filterNot { TOKEN.matches(it) }
            .filterNot { PADDED_TOKEN.matches(it) }
            .distinct()
            .toList()

    /** 이 양식이 쓰는 자리표 이름 (나온 차례, 중복 포함). */
    fun namesIn(template: String): List<String> =
        TOKEN.findAll(template).map { it.groupValues[1] }.toList()

    /**
     * 이 양식이 **실제로 쓰는** 자리표 이름의 집합.
     *
     * 조립기가 이것을 먼저 보고 **안 쓰는 재료는 아예 만들지 않는다.** 만들면 그 재료의
     * 상한·절단 고지가 딸려 나오는데, **사용자가 뺀 것은 잘린 것이 아니다**(R-14) — 양식에서
     * `{{태그목록}}`을 지운 사람에게 *"태그 3건 생략"*이라고 말하면 보내지도 않은 것을
     * 잘랐다고 하는 셈이고, 그 사람은 없는 설정을 찾아 헤맨다.
     */
    fun usedNames(template: String): Set<String> = namesIn(template).toSet()

    /**
     * 양식 + 값 → 실제로 나갈 글.
     *
     * @param values 자리표 이름 → 펼칠 값. 목록에 없는 이름은 빈 값으로 친다(줄이 사라진다).
     */
    fun expand(template: String, values: Map<String, String>): String {
        fun filled(name: String): Boolean = values[name]?.isNotBlank() == true

        val source = template.split("\n")

        // ① 자리표가 전부 빈 줄을 표시해 둔다. **지우기 전에 표시부터 하는 것이 요점이다** —
        //    ②가 *원래 양식이 어떤 모양이었는지*를 알아야 하기 때문이다.
        val dropped = BooleanArray(source.size) { i ->
            val names = namesIn(source[i])
            names.isNotEmpty() && names.none { filled(it) }
        }

        // ② 몸이 통째로 비워진 절은 머리까지 걷어낸다.
        //
        //    **절의 몸은 머리 바로 뒤부터 첫 빈 줄까지다.** 빈 줄을 건너뛰며 찾으면 다음 절의
        //    몸을 제 것으로 세어 재료가 하나도 없는 절이 살아남고(이미지 태그 지시문에서 실제로
        //    그랬다), 반대로 다음 한 줄만 보면 **사용자가 보기 좋으라고 머리 아래 넣은 빈 줄**에
        //    걸려 값이 다 찬 절의 이름이 사라진다.
        //
        //    그래서 **원래부터 몸이 없던 머리는 건드리지 않는다** — 그것은 사용자가 그렇게 쓴
        //    것이고, 앱이 지울 근거가 없다. 지우는 것은 *있던 몸이 전부 비워진* 절뿐이다.
        for (i in source.indices) {
            if (dropped[i]) continue
            if (!SECTION_HEAD.matches(source[i].trim())) continue
            // 머리 자신이 값을 들고 있으면(`[유지] {{유지목록}}`) 그 줄이 곧 내용이다.
            if (namesIn(source[i]).any { filled(it) }) continue
            var j = i + 1
            var body = 0
            var alive = 0
            while (j < source.size && source[j].isNotBlank()) {
                body++
                if (!dropped[j]) alive++
                j++
            }
            if (body > 0 && alive == 0) dropped[i] = true
        }

        val lines = source.filterIndexed { i, _ -> !dropped[i] }

        // ③ 절이 통째로 빠지면 빈 줄이 겹친다 — 한 줄로 접고 앞뒤는 턴다.
        val collapsed = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank() && collapsed.lastOrNull()?.isBlank() == true) continue
            collapsed.add(line)
        }
        while (collapsed.isNotEmpty() && collapsed.first().isBlank()) collapsed.removeAt(0)
        while (collapsed.isNotEmpty() && collapsed.last().isBlank()) collapsed.removeAt(collapsed.size - 1)

        // ④ 펼친다. **한 번만 훑는다** — 바꾼 자리는 되읽지 않는다(위 KDoc).
        // 람다판 `replace`는 돌려준 글자를 **그대로** 넣는다(`$1` 같은 것을 다시 읽지 않는다).
        // 문자열판을 쓰면 값 안의 `$`가 그룹 참조로 읽혀 사용자 데이터가 지시가 된다.
        return TOKEN.replace(collapsed.joinToString("\n")) { m ->
            values[m.groupValues[1]].orEmpty()
        }
    }
}
