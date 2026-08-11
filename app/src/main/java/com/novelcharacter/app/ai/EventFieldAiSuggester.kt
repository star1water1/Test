package com.novelcharacter.app.ai

/**
 * 사건 필드 값 AI 추천 — 연표의 사건을 근거로 그 사건의 필드값을 제안한다 (B-43).
 *
 * ## 무엇이 캐릭터 축과 다른가
 *
 * **프롬프트뿐이다**(사용자 확정 16번 ㄴ2 — *"사건 전용 프롬프트를 따로"*). 근거 재료가
 * 통째로 다르기 때문이다: 캐릭터는 태그·메모·소속·관계·대결 우열을 근거로 삼는데
 * 사건에는 그런 칸이 없고, 대신 **때(연도)·관련 작품·관련 캐릭터·앞뒤 사건**이 있다.
 * 같은 프롬프트에 섹션만 갈아 끼우면 없는 근거를 대라는 지시가 그대로 나간다(R-13).
 *
 * 그 밖은 전부 캐릭터 축과 **같은 것을 쓴다** — 응답 스키마·검증·정규화·값 라이브러리
 * 접기·결손 사유 분류·청킹·부분 실패 격리. 다루는 단위가 똑같이 *"필드 하나의 값"*이라
 * 갈라 둘 이유가 없고, 갈라 두면 한쪽에서만 검증이 느슨해진다([FieldPromptSource] KDoc).
 *
 * ## 재료 범위는 사용자가 고른다 (확정 16번 ㄱ1/ㄱ2 → 인앱 선택)
 *
 * 무엇을 프롬프트에 실을지는 [EventAiMaterial]을 사용자가 켜고 끈다. 사건마다 쓸모 있는
 * 재료가 다르고(전투 사건엔 참여 캐릭터가, 시대 배경 사건엔 앞뒤 사건이 결정적이다),
 * 그 판단은 개발자가 대신할 수 없다(자율성 우선). 저장은 **앱 전체**다(P-7 해소 —
 * AI 설정 저장소 셋이 전부 앱 전체이고 세계관 단위 AI 설정은 하나도 없다).
 *
 * **끈 재료는 조용히 빠지지 않는다** — 뺀 항목을 절단 고지와 같은 경로로 표면화한다(R-14).
 * 추천이 빈약할 때 사용자가 *"내가 꺼 둔 탓"*임을 알 수 있어야 하기 때문이다.
 */
class EventFieldAiSuggester(private val aiService: AiService) {

    /** 실행 규칙은 축을 타지 않는다 — 프롬프트만 갈아 끼워 같은 엔진으로 들어간다. */
    private val engine = CharacterFieldAiSuggester(aiService)

    /**
     * 프롬프트에 실을 사건 컨텍스트 — 호출측(사건 편집 창)이 라이브 입력값 + DB에서 조립한다.
     *
     * 저장된 DB 상태가 아니라 **창의 현재 입력값**을 쓴다(캐릭터 축과 같은 규약) — 사건을
     * 쓰다가 ✨를 누르는 것이 정상 동선이고, 그때 저장은 아직 안 됐다.
     */
    data class EventAiContext(
        /** 사건 설명 — 사건의 이름 노릇을 한다(연표 카드에 뜨는 그 줄). */
        val description: String,
        /** "천개력 1023년 5월" 같은 표기. [com.novelcharacter.app.data.model.TimelineEvent.getFormattedDate]가 만든다. */
        val dateLabel: String,
        /** 출생·사망 같은 시맨틱 유형. 없으면 빈 문자열. */
        val eventTypeLabel: String = "",
        val universeName: String = "",
        /** 관련 작품명 */
        val novels: List<String> = emptyList(),
        /** 관련 캐릭터명 */
        val characters: List<String> = emptyList(),
        /** 앞뒤 사건 요약 — "1020년 – 국경 분쟁" 꼴. 가까운 순으로 정렬돼 온다. */
        val neighborEvents: List<String> = emptyList(),
        /** (필드 표시명, 현재 값) — 창의 라이브 입력값. 추천 대상 필드는 조립 시 제외된다. */
        val filledFields: List<Pair<String, String>> = emptyList(),
        /** 조회에 실패해 프롬프트에서 빠진 섹션명 — 절단 고지와 같은 경로로 표면화 (변수 제어) */
        val loadFailures: List<String> = emptyList()
    )

    /**
     * 사건 필드 값 추천. 반환 형태·결손 규약은 캐릭터 축과 같다
     * ([CharacterFieldAiSuggester.SuggestOutcome]) — 검토 화면이 둘을 같은 코드로 읽는다.
     */
    suspend fun suggest(
        context: EventAiContext,
        targets: List<CharacterFieldAiSuggester.FieldSpec>,
        /** 사용자가 켜 둔 재료 (앱 전체 설정 — P-7) */
        scope: Set<EventAiMaterial> = EventAiMaterial.DEFAULT,
        minConfidence: CharacterFieldAiSuggester.Confidence? = null,
        creativity: AiCreativity = AiCreativity.DEFAULT,
        errorMessageOf: (AiResult.Failure) -> String
    ): CharacterFieldAiSuggester.SuggestOutcome = engine.suggest(
        prompts = object : FieldPromptSource {
            override fun system(
                minConfidence: CharacterFieldAiSuggester.Confidence?,
                creativity: AiCreativity
            ) = buildSystemPrompt(minConfidence, creativity)

            override fun user(targets: List<CharacterFieldAiSuggester.FieldSpec>) =
                buildUserPrompt(context, targets, scope)
        },
        targets = targets,
        minConfidence = minConfidence,
        creativity = creativity,
        // 사건에는 이미지가 없다 — 캐릭터 축의 첨부 경로(A-7)는 캐릭터 이미지 목록을 근거로
        // 삼는 것이고, 사건 축에는 그 목록 자체가 없다. 빈 채로 두는 것이 정확하다.
        images = emptyList(),
        errorMessageOf = errorMessageOf
    )

    companion object {

        /** 사건 설명 프롬프트 상한 — 초과분은 잘라 싣고 고지한다 (R-14). */
        const val MAX_DESCRIPTION_CHARS = 500

        /** 관련 캐릭터·작품·앞뒤 사건의 적재 상한. 초과분은 개수로 고지한다 (R-14). */
        const val MAX_CHARACTERS = 30
        const val MAX_NOVELS = 10
        const val MAX_NEIGHBORS = 12

        /**
         * 사건 축 시스템 프롬프트.
         *
         * 캐릭터판과 **번호 체계를 맞춘다** — 공통 규칙(1~7·9~14)은 뜻이 같아야 검토 화면의
         * 고지 문구가 두 축에서 같은 것을 말한다. 갈리는 것은 8번(무엇을 근거로 삼는가)이고,
         * 캐릭터판의 15번(대결 우열)은 사건에 그 개념이 없어 자리 자체가 없다.
         */
        fun buildSystemPrompt(
            minConfidence: CharacterFieldAiSuggester.Confidence? = null,
            creativity: AiCreativity = AiCreativity.DEFAULT
        ): String = """
            당신은 소설 세계관의 연표 사건 설정 도우미다. 주어진 사건 정보를 근거로 요청된 필드의 값을 추천하라.
            규칙:
            1. 반드시 아래 JSON 스키마로만 응답하고 다른 텍스트를 덧붙이지 마라:
            {"suggestions":[{"key":"필드키","value":"추천값","reason":"근거 한 문장","confidence":"high|medium|low"}]}
            2. [추천할 필드]에 제시된 key **전부**에 대해 항목을 하나씩 낸다. 필드가 N개면 항목도 N개다.
               임의로 빠뜨리지 마라 — 빠진 필드는 사용자에게 이유를 알 수 없는 결손으로 남는다.
            3. key는 [추천할 필드]에 제시된 key만 사용한다. 목록에 없는 key를 만들지 마라.
            4. 정보가 적다는 이유로 추천을 포기하지 마라. 사건 정보와 모순되지 않고 아래 '기존 사용값'의
               기조에 맞는다면 합리적으로 추정해 제시하고, reason에 무엇을 근거로 한 추정인지 밝힌다.
               제안은 사용자가 검토해 취사선택하므로, 확신이 없다는 이유로 생략할 필요가 없다.
            5. 그럼에도 정할 근거가 전혀 없는 필드는 **생략하지 말고** value를 빈 문자열("")로 두고
               reason에 그 이유를 한국어 한 문장으로 적는다. 항목 자체는 반드시 포함한다.
            6. '옵션'이 제시된 필드는 그 옵션 중 하나를 **그대로** 쓴다. 옵션에 없는 값을 만들지 마라.
            7. '형식' 지시가 있는 필드는 형식을 정확히 지킨다.
            8. reason에는 사건의 어떤 정보(설명·때·유형·관련 작품·관련 캐릭터·가까운 사건·다른 필드)에서
               추론했는지 한국어 한 문장으로 쓴다.
            9. '기존 사용값'이 제시된 필드는 그 값들이 이 세계관에서 실제로 쓰이는 표기 기조다.
               같은 뜻이면 새 표기를 만들지 말고 기존 값을 그대로 쓴다.
               기존 값으로 표현할 수 없어 새 값이 필요할 때만 새로 만들되, 기존 값들의 표기 방식·
               상세도·길이를 따른다.
            10. '이 목록의 값만 허용'이 붙은 필드는 제시된 기존 사용값 중에서만 고른다.
            11. 항목마다 confidence를 정직하게 매긴다. 잘 보이려고 높여 적지 마라 — 사용자는 이 값으로
                무엇을 검토 없이 받을지 정한다.
                high: 사건 정보에 직접적인 근거가 있다 (설명·때·관련 캐릭터가 그 값을 가리킨다)
                medium: 주어진 정보에서 무리 없이 추론된다
                low: 정보가 부족해 세계관의 기존 기조나 일반적 통념에 기댄 추측이다
            12. '사용자 지시'가 붙은 필드는 그 지시를 **최우선**으로 따른다 — 다른 근거와 어긋나면
                지시 쪽을 택하고, reason에 지시를 어떻게 반영했는지 적는다.
            13. '이미 물린 값'이 제시된 필드는 사용자가 그 값을 보고 다시 요청한 것이다.
                같은 값이나 사실상 같은 값을 되풀이하지 말고 **다른 방향**의 값을 내라.
            14. '설명'이 붙은 필드는 그 설명이 이 세계관에서 그 필드가 뜻하는 바의 정의이자 제약이다.
                설명과 어긋나는 값을 내지 마라. 설명이 옵션·형식과 충돌하면 옵션·형식을 따르고,
                무엇이 충돌했는지 reason에 적어라.
            15. '가까운 사건'은 같은 연표에 이미 적힌 다른 사건이다. 그 사건들과 앞뒤가 맞게,
                이미 정해진 흐름과 모순되지 않는 값을 내라 — 연표는 사용자가 짜 둔 사실이지
                네가 다시 지어낼 대상이 아니다.
        """.trimIndent() + confidenceFloorRule(minConfidence) + creativity.promptRule()

        /**
         * 캐릭터판과 같은 규칙이지만 **번호가 하나 밀린다**(사건판의 마지막 규칙이 15번이라 16번).
         * 두 벌로 적는 대신 캐릭터판을 부르면 번호가 어긋나 규칙 하나가 통째로 묻힌다.
         */
        private fun confidenceFloorRule(minConfidence: CharacterFieldAiSuggester.Confidence?): String =
            if (minConfidence == null) "" else "\n" + """
            16. 사용자는 근거 강도 '${minConfidence.wire}' 이상만 받기로 정했다. 그보다 낮은 추측은
                값을 내지 말고 규칙 5대로 value를 ""로 두고 reason에 근거가 얕은 이유를 적어라.
            """.trimIndent()

        /**
         * 사건 컨텍스트 블록 + 공통 `[추천할 필드]` 절.
         *
         * **끈 재료를 고지하는 것이 이 함수의 계약 절반이다** — 사용자가 꺼 둔 것도, 조회에
         * 실패한 것도, 상한에 잘린 것도 전부 `truncationNotes`로 나간다. 추천이 빈약할 때
         * 원인이 셋 중 무엇인지 사용자가 구별할 수 있어야 한다(변수 제어).
         */
        fun buildUserPrompt(
            context: EventAiContext,
            targets: List<CharacterFieldAiSuggester.FieldSpec>,
            scope: Set<EventAiMaterial> = EventAiMaterial.DEFAULT
        ): CharacterFieldAiSuggester.PromptBuild {
            val notes = mutableListOf<String>()
            // 조회 실패로 빠진 섹션 — 사용자가 끈 것과 **다른 사유**라 문구를 갈라 둔다.
            context.loadFailures.forEach { notes.add("$it 정보를 불러오지 못함") }

            fun <T> capList(list: List<T>, max: Int, label: String): List<T> =
                if (list.size > max) {
                    notes.add("$label ${list.size - max}건 생략 (상한 ${max}건)")
                    list.take(max)
                } else list

            /** 사용자가 끈 재료는 **비어 있어서 빠진 것과 갈라** 고지한다. */
            fun included(material: EventAiMaterial, values: List<String>): Boolean {
                if (values.isEmpty()) return false
                if (material in scope) return true
                notes.add("${material.label}을(를) 재료에서 제외 (설정)")
                return false
            }

            val sb = StringBuilder()
            sb.append("[사건]\n")
            val desc = context.description.trim()
            sb.append("설명: ")
            if (desc.isEmpty()) {
                sb.append("(미입력)")
            } else if (desc.length > MAX_DESCRIPTION_CHARS) {
                notes.add("사건 설명 ${MAX_DESCRIPTION_CHARS}자 초과분 생략")
                sb.append(desc.take(MAX_DESCRIPTION_CHARS)).append('…')
            } else {
                sb.append(desc)
            }
            sb.append('\n')
            if (context.dateLabel.isNotBlank()) sb.append("때: ").append(context.dateLabel.trim()).append('\n')
            if (context.eventTypeLabel.isNotBlank()) {
                sb.append("유형: ").append(context.eventTypeLabel.trim()).append('\n')
            }
            if (context.universeName.isNotBlank()) {
                sb.append("세계관: ").append(context.universeName.trim()).append('\n')
            }
            if (included(EventAiMaterial.NOVELS, context.novels)) {
                sb.append("관련 작품: ")
                    .append(capList(context.novels, MAX_NOVELS, "관련 작품").joinToString(", ")).append('\n')
            }
            if (included(EventAiMaterial.CHARACTERS, context.characters)) {
                sb.append("관련 캐릭터: ")
                    .append(capList(context.characters, MAX_CHARACTERS, "관련 캐릭터").joinToString(", ")).append('\n')
            }
            if (included(EventAiMaterial.NEIGHBOR_EVENTS, context.neighborEvents)) {
                sb.append("가까운 사건: ")
                    .append(capList(context.neighborEvents, MAX_NEIGHBORS, "가까운 사건").joinToString(" / "))
                    .append('\n')
            }

            // 추천 대상 필드는 [입력된 필드]에서 제외 — 대상의 현재 값은 필드 스펙 쪽에 실린다
            val targetNames = targets.mapTo(HashSet()) { it.name }
            val filled = context.filledFields.filter { it.first !in targetNames && it.second.isNotBlank() }
            if (included(EventAiMaterial.FILLED_FIELDS, filled.map { it.first })) {
                sb.append("[입력된 필드]\n")
                var longValues = 0
                for ((name, value) in capList(filled, CharacterFieldAiSuggester.MAX_FILLED_FIELDS, "입력된 필드")) {
                    val v = if (value.length > CharacterFieldAiSuggester.MAX_VALUE_CHARS) {
                        longValues++
                        value.take(CharacterFieldAiSuggester.MAX_VALUE_CHARS) + "…"
                    } else value
                    sb.append(name).append(": ").append(v).append('\n')
                }
                if (longValues > 0) {
                    notes.add("긴 필드값 ${longValues}건을 ${CharacterFieldAiSuggester.MAX_VALUE_CHARS}자로 절단")
                }
            }

            CharacterFieldAiSuggester.appendTargetSection(sb, targets, notes)
            return CharacterFieldAiSuggester.PromptBuild(sb.toString(), notes)
        }
    }
}

/**
 * 사건 AI 추천의 **재료 범위** — 사용자가 켜고 끄는 단위 (확정 16번 ㄱ1/ㄱ2 인앱 선택).
 *
 * ## 왜 프리셋 둘이 아니라 항목별인가
 *
 * 물었던 갈래는 *좁게(ㄱ1) / 넓게(ㄱ2)* 둘이었고 답은 *"인앱에서 고르게"*였다. 항목별
 * 토글은 그 둘을 **양 끝으로 포함하면서** 그 사이도 연다(전부 끄면 ㄱ1, 전부 켜면 ㄱ2).
 * 개발 단계에서 이것도 저것도 가능하게 두고 실사용에서 사용자가 가리게 한다는 개발 의도
 * 3번 그대로다.
 *
 * ## 여기 없는 것 — 끌 수 없는 재료
 *
 * 사건 설명·때·유형·세계관은 목록에 없다. 그것을 빼면 *"무엇에 대한 값인가"*가 사라져
 * 요청 자체가 성립하지 않는다 — 끌 수 있게 두면 **켜도 아무 일이 없는 스위치의 반대편**,
 * 즉 끄면 요청이 무의미해지는 스위치가 된다(R-24가 겨누는 자리).
 */
enum class EventAiMaterial(val key: String, val label: String) {
    NOVELS("novels", "관련 작품"),
    CHARACTERS("characters", "관련 캐릭터"),
    NEIGHBOR_EVENTS("neighbors", "가까운 사건"),
    FILLED_FIELDS("filled", "이미 입력한 필드값");

    companion object {
        /**
         * 기본은 **전부 켜짐**이다.
         *
         * 캐릭터 축이 근거를 전부 싣는 것과 같은 기조이고, 이 재료들은 이름·연도 같은 짧은
         * 목록이라 토큰 부담이 이미지 첨부([AiPromptPolicy.ATTACH_IMAGES_DEFAULT])처럼
         * 크지 않다. 기본을 좁게 두면 사용자는 **기능이 원래 그 정도인 줄 안다** —
         * 줄이는 쪽이 설정으로 열려 있으면 충분하다.
         */
        val DEFAULT: Set<EventAiMaterial> = entries.toSet()

        /** 모르는 이름은 버린다(외부 편집분 수용). 빈 문자열은 '전부 끔'이라 [DEFAULT]로 떨어뜨리지 않는다. */
        fun parse(raw: String?): Set<EventAiMaterial> {
            if (raw == null) return DEFAULT
            return tokensOf(raw).mapNotNull { token -> entries.firstOrNull { it.key.equals(token, true) } }.toSet()
        }

        /**
         * 셀에 적힌 것 중 **모르는 이름만** 골라 낸다 — 엑셀 가져오기가 그것을 사유로 돌려준다.
         *
         * 판정을 여기 두는 이유는 [parse]와 **같은 쪼개기**를 보게 하기 위해서다: 호출부가
         * 따로 쪼개면 한쪽만 아는 토큰이 생겨 *"모른다고 거절했는데 파싱은 받아들이는"* 조합이 난다.
         */
        fun unknownTokens(raw: String): List<String> =
            tokensOf(raw).filter { token -> entries.none { it.key.equals(token, true) } }

        /** 쪼개기의 단일 소스 (R-47) — 자리마다 적으면 그 자리만 따옴표·전각 쉼표를 모른다. */
        private fun tokensOf(raw: String): List<String> =
            com.novelcharacter.app.util.CsvTokens.split(raw)

        /**
         * 저장 표기 — 키를 쉼표로 잇는다. **`entries` 차례로 적는다**(집합의 순회 순서가 아니라):
         * 같은 설정이 저장할 때마다 다른 문자열이 되면 엑셀 왕복에서 값이 바뀐 것처럼 보인다.
         */
        fun serialize(scope: Set<EventAiMaterial>): String =
            com.novelcharacter.app.util.CsvTokens.join(entries.filter { it in scope }) { it.key }
    }
}
