package com.novelcharacter.app.ai

/**
 * 창작도 (P-A A-4) — **값의 후보 공간을 넓히지, 계약을 넓히지 않는다.**
 *
 * 2층 구조: ① 샘플링(temperature) ② 지시(시스템 프롬프트 꼬리 규칙). 온도만 올리면 표현이
 * 흔들릴 뿐 무엇을 후보로 삼을지는 바뀌지 않고, 지시만 바꾸면 모델이 여전히 무난한 답으로
 * 수렴하므로 둘 다 바꾼다.
 *
 * 어느 단계에서도 **프롬프트가 완화하지 않는 것**: 필드 설명(A-2)·SELECT/GRADE 옵션·
 * 형식(생일 MM-DD 등)·`restricted` 허용 목록·캐릭터 정보와의 모순 금지.
 * 창작도를 최대로 올려도 잘못된 값이 **저장되는** 경로는 생기지 않는다 — 옵션·형식에 어긋난
 * 값은 사유와 함께 제외된다(비용은 "드롭이 늘 수 있다"이지 "데이터가 망가진다"가 아니다).
 *
 * **단 `restricted`의 처분은 드롭이 아니다**(B-79) — 목록 밖 값은 '목록 밖'으로 표시된 후보로
 * 나가고, 채택하면 저장 가드가 "추가하고 저장 / 입력 수정"을 묻는다. 손으로 넣을 수 있는 값을
 * 유료 응답에서만 버리던 비대칭을 없앤 것이며, **프롬프트 지시(아래 [promptRule])는 그대로다.**
 *
 * 기본값 [BALANCED]는 temperature를 **아예 싣지 않는다** — 프로바이더 기본값 그대로,
 * 설정을 열지 않은 사용자의 동작은 현행과 완전히 동일하다(회귀 없음).
 */
enum class AiCreativity(val wire: String, val label: String) {
    /** 캐릭터 정보·기존 사용값에서 직접 도출되는 값만. */
    PRECISE("precise", "정확"),

    /** 기본 — 현행 프롬프트 그대로, 샘플링 파라미터 미전송. */
    BALANCED("balanced", "균형"),

    /** 기존 사용값은 표기 방식의 참고이지 선택지의 울타리가 아니다. */
    FREE("free", "자유"),

    /** 뻔한 답을 피하고 서로 다른 방향을 시도한다. */
    BOLD("bold", "실험");

    /**
     * 이 단계가 [protocol]에 실을 temperature. null = **파라미터 자체를 싣지 않는다**.
     * 계수는 프로토콜별 상한([maxTemperatureOf])에 대한 비율이다.
     */
    fun temperatureFor(protocol: AiProtocol): Double? = when (this) {
        BALANCED -> null
        PRECISE -> 0.1 * maxTemperatureOf(protocol)
        FREE -> 0.85 * maxTemperatureOf(protocol)
        BOLD -> 1.0 * maxTemperatureOf(protocol)
    }

    /**
     * 시스템 프롬프트 꼬리에 붙는 단계별 지시 (confidenceFloorRule과 같은 문법).
     * [BALANCED]는 빈 문자열 — 추가 규칙 없음(현행과 동일).
     *
     * FREE 이상은 표기 기조 규칙(짧은 값 규칙 9)을 **덮어쓰지 않고 좁힌다** — 적용 범위를
     * 표기 방식(길이·상세도·어투)으로 한정한다. `restricted`의 허용 목록(규칙 10)은 취향이
     * 아니라 계약이라 어느 단계에서도 완화하지 않는다.
     */
    fun promptRule(): String = when (this) {
        BALANCED -> ""
        PRECISE -> "\n" + """
            [창작도: 정확] 주어진 캐릭터 정보와 '기존 사용값'에서 직접 도출되는 값·내용만 내라.
            새 표기·새 설정을 만들지 마라. 근거 없이 추정해야 하는 항목은 규칙대로 사유를 밝혀라.
        """.trimIndent()
        FREE -> "\n" + """
            [창작도: 자유] '기존 사용값'과 참고 자료는 **표기 방식(길이·상세도·어투)**의 참고이지
            선택지의 울타리가 아니다. 표기 기조 규칙은 그 범위로만 지키고, 값·내용 자체는
            기존에 없던 것이어도 된다. 단 '이 목록의 값만 허용'이 붙은 필드는 그 목록을 그대로 지킨다.
        """.trimIndent()
        BOLD -> "\n" + """
            [창작도: 실험] 뻔한 답을 피하고 항목마다 서로 다른 방향을 시도하라. '기존 사용값'은
            **표기 방식(길이·상세도·어투)**의 참고로만 쓰고 값 자체는 새로워도 된다.
            단 '설명'·옵션·형식 제약과 '이 목록의 값만 허용' 목록은 그대로 지킨다.
        """.trimIndent()
    }

    companion object {
        val DEFAULT = BALANCED

        /**
         * 프로토콜별 temperature 상한 — 규격 최대(OpenAI·Gemini 2.0)가 아니라 **형식이 무너지지
         * 않는 상한**이다. 이 기능들은 JSON 스키마 응답을 요구하는데, 온도가 과하면 형식이 깨져
         * 유료 응답이 통째로 버려진다(UNREADABLE). Anthropic은 규격 자체가 1.0이다.
         */
        fun maxTemperatureOf(protocol: AiProtocol): Double = when (protocol) {
            AiProtocol.ANTHROPIC -> 1.0
            AiProtocol.OPENAI_COMPAT -> 1.4
            AiProtocol.GEMINI -> 1.4
        }

        /**
         * 상한이 1.0으로 좁아 자유(0.85)·실험(1.0)의 샘플링 차이가 작은 프로토콜인가.
         * 이 사실을 숨기면 "올려도 똑같다"는 불신이 생긴다 — UI가 한 줄로 밝힌다(§6-4).
         */
        fun narrowTopRange(protocol: AiProtocol): Boolean = maxTemperatureOf(protocol) <= 1.0

        /** 알 수 없는 표기는 기본값 — 손상 설정으로 동작이 바뀌지 않는다. */
        fun fromWire(raw: String?): AiCreativity = entries.find { it.wire == raw } ?: DEFAULT

        /**
         * 설정 카드 **밖**(실행 다이얼로그·재요청·서술형 시트의 칩 줄)에서 값을 바꿨을 때
         * 고지할 단계. null이면 고지하지 않는다 (B-82).
         *
         * **값은 하나다**(§6-8)의 뒷면이다 — 그 자리에서 한 번 올리면 이번 요청만이 아니라
         * **이후 모든 요청의 기본값**이 바뀐다. 그 사실을 말하지 않으면 사용자는 자기가
         * 언제 무엇을 바꿨는지 모르는 채로 계속 그 단계로 과금된다.
         * R-23("초기화 사실은 한 줄로 고지한다 — 조용히 바꾸지 않는다")과 같은 부류다.
         *
         * [initial]은 그 자리를 **열었을 때**의 값이다. 되돌리면(현재 == 초기) 바뀐 것이
         * 없으므로 고지도 사라진다 — 이 고지는 *일어난 사건*이 아니라 *지금의 상태*를 말한다.
         * 사건으로 두면 균형 → 실험 → 균형 뒤에도 "바뀌었다"가 남아 화면이 거짓이 된다.
         */
        fun globalChangeNotice(initial: AiCreativity, current: AiCreativity): AiCreativity? =
            current.takeIf { it != initial }
    }
}
