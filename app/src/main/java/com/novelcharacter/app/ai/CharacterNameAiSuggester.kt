package com.novelcharacter.app.ai

import com.novelcharacter.app.util.NameBankMatch

/**
 * 캐릭터 **이름 추천** (B-123 · 설계 정본 `docs/feature_roadmap_2026-08.md` 7장, 계약 `ai_integration` A-8).
 *
 * [CharacterFieldAiSuggester]에 얹지 못한 이유는 대상이 **필드가 아니기 때문**이다 — 이름은
 * `FieldDefinition`이 아니라 엔티티 컬럼(`name`·`firstName`·`lastName`·`anotherName`)이라
 * 그쪽의 대상식(`bulkTargetsOf`) 밖이다. 서술형([NarrativeFieldAiWriter])이 전용 경로를 가진
 * 것과 같은 이유로 여기도 전용이다.
 *
 * ## 라운드 — 멀티턴 없이 성립한다 (설계 7-2)
 *
 * 현행 AI 계층은 단일 턴뿐이다. 그래도 *"계속 피드백해서 소통"*이 성립하는 것은 **상태를
 * 요청에 동봉**하기 때문이다: 유지(♥)·회피(✕)·자유 지시를 매 라운드 프롬프트에 실어 보낸다
 * (필드 추천의 `userInstruction`+`rejectedValues`가 이미 검증한 문법이며, 새로 만드는 것은
 * 상태의 종류가 아니라 **크기**다). 라운드가 쌓여도 요청은 라운드당 1건이다.
 *
 * ## 계약 (docs/ai_integration.md)
 *
 * - 온디맨드 전용. 호출측이 `hasUsableProvider()` 가드 + 실행 전 비용 고지.
 * - **AI 출력을 DB에 직접 쓰지 않는다.** 후보는 폼 위젯([Mode]가 정한 칸)에만 들어가고,
 *   이름은행에는 사용자가 [은행에 담기]를 눌러야 들어간다.
 * - 절단·드롭·되풀이는 조용히 두지 않고 개수로 고지한다(R-14).
 * - 프롬프트 조립·응답 파싱·라운드 상태는 [AiService] 미호출 순수 함수라 단위 테스트된다.
 *
 * **이름의 같음은 [NameBankMatch]가 정한다** — 되풀이 판정도, 은행 중복도 그 함수다.
 * 여기서 규칙을 새로 적으면 *담을 때는 중복인데 저장 때는 아닌* 상태가 난다(설계 8-5 ⓐ).
 */
class CharacterNameAiSuggester(private val aiService: AiService) {

    /**
     * 무엇을 짓는가. **적용 칸이 모드마다 다르다** — 이 열거가 그 대응의 단일 소스다.
     *
     * [GIVEN]·[SURNAME]이 있는 이유는 실사용이다(설계 7-3): 가문명이 반복되는 작품에서
     * 프셀류트가에 새 식구를 들일 때 필요한 것은 **이름뿐**이고, 전체 이름을 받으면 성을
     * 매번 지우게 된다.
     */
    enum class Mode(val key: String) {
        /** 전체 이름 → `name` */
        FULL("full"),

        /** 성 고정 + 이름만 → `firstName` */
        GIVEN("given"),

        /** 이름 고정 + 성만 → `lastName` */
        SURNAME("surname"),

        /** 이명·칭호 → `anotherName`(콤마 목록에 덧붙인다) */
        ALIAS("alias");

        /** 고정 부분이 있어야 성립하는 모드인가 — 없으면 화면이 아예 보여 주지 않는다(R-24). */
        val needsFixedPart: Boolean get() = this == GIVEN || this == SURNAME

        companion object {
            fun fromKey(key: String?): Mode = entries.firstOrNull { it.key == key } ?: FULL
        }
    }

    /** 후보에 사용자가 붙인 표식. */
    enum class Mark {
        NONE,

        /** ♥ — *"이 결을 따르라"*. 다음 라운드 프롬프트의 [유지] 목록이 된다. */
        HEART,

        /**
         * ✕ — *"이쪽은 피하라"*. **사라지지 않는다**: 다음 라운드의 회피 목록이자
         * [은행에 담기]의 재료다(이 캐릭터에게 아닌 이름이 다른 캐릭터에겐 맞을 수 있다).
         */
        REJECT
    }

    /** 후보 하나 — 이름과 짧은 근거. */
    data class Candidate(val name: String, val reason: String)

    /** 라운드 하나의 산출. [notices]는 그 라운드에서 조용히 버린 것이 없음을 보이는 줄들이다. */
    data class Round(
        /** 1부터. 화면의 *"3라운드"*가 이 값이다. */
        val ordinal: Int,
        val candidates: List<Candidate>,
        /** 이 라운드에 실은 자유 지시(없으면 빈 문자열) — 지난 라운드를 접었을 때 무엇을 시켰는지 남긴다. */
        val instruction: String,
        val notices: List<String>
    )

    /**
     * **세션 풀** — 시트가 살아 있는 동안 모든 라운드의 후보가 표식과 함께 쌓인다(설계 7-4).
     *
     * 유료 응답이라 **화면이 아니라 ViewModel이 든다**(R-38). 회전에 사라지면 그 자리에서
     * 돈이 사라진다.
     */
    data class Pool(
        val mode: Mode = Mode.FULL,
        val rounds: List<Round> = emptyList(),
        /** 정규화된 이름 → 표식. 표기가 달라도 같은 이름이면 한 표식이다. */
        val marks: Map<String, Mark> = emptyMap()
    ) {
        val candidates: List<Candidate> get() = rounds.flatMap { it.candidates }

        val isEmpty: Boolean get() = rounds.isEmpty()

        fun markOf(name: String): Mark = marks[NameBankMatch.normalize(name)] ?: Mark.NONE

        /** 표식을 바꾼다. [Mark.NONE]은 지운다 — 빈 표식을 담아 두면 회전 저장만 커진다. */
        fun withMark(name: String, mark: Mark): Pool {
            val key = NameBankMatch.normalize(name)
            val next = LinkedHashMap(marks)
            if (mark == Mark.NONE) next.remove(key) else next[key] = mark
            return copy(marks = next)
        }

        /** 표식이 [mark]인 후보 이름 — **먼저 나온 순서**(♥ 선반의 차례가 라운드 차례다). */
        fun namesMarked(mark: Mark): List<String> =
            candidates.map { it.name }.filter { markOf(it) == mark }

        fun countMarked(round: Round, mark: Mark): Int =
            round.candidates.count { markOf(it.name) == mark }

        /**
         * 이번 응답에서 **처음 보는 것만** 남긴다. 되풀이는 개수로 돌려준다 — 재요청이 같은
         * 답을 주면 사용자는 돈만 내고 아무것도 못 얻으므로, 그 사실이 화면에 보여야 한다.
         */
        fun freshOnly(incoming: List<Candidate>): Fresh {
            val seen = candidates.mapTo(HashSet()) { NameBankMatch.normalize(it.name) }
            val kept = ArrayList<Candidate>(incoming.size)
            var repeated = 0
            for (c in incoming) {
                val key = NameBankMatch.normalize(c.name)
                if (!seen.add(key)) { repeated++; continue }
                kept.add(c)
            }
            return Fresh(kept, repeated)
        }

        fun addRound(candidates: List<Candidate>, instruction: String, notices: List<String>): Pool =
            copy(rounds = rounds + Round(rounds.size + 1, candidates, instruction, notices))

        /**
         * 모드를 바꾼다 — **풀은 비운다.** 전체 이름과 이명은 같은 목록에 섞일 수 없다
         * (담기의 성별·출처도, 적용 칸도 다르다). 비우는 것을 화면이 먼저 확인받는다.
         */
        fun switchedTo(mode: Mode): Pool = Pool(mode = mode)
    }

    data class Fresh(val candidates: List<Candidate>, val repeated: Int)

    /** 한 라운드의 실행 결과 — 화면이 고지할 것이 전부 들어 있다. */
    data class RoundOutcome(
        val candidates: List<Candidate>,
        /** 형식 이탈·중복·개수 초과로 버린 응답 항목 수 */
        val droppedCount: Int,
        val failures: List<String>,
        /** 프롬프트 조립에서 잘라낸 것 (R-14) */
        val truncationNotes: List<String>,
        val truncated: Boolean,
        val inputTokens: Int,
        val outputTokens: Int
    )

    /**
     * 프롬프트에 실을 재료. 캐릭터 컨텍스트는 **기존 조립기를 그대로 재사용**하고
     * (`CharacterAiContextBuilder` — 설계 7-3), 이름 결의 견본만 이쪽이 더한다.
     */
    data class NameContext(
        /** 편집 폼에서 온 캐릭터 컨텍스트. 은행의 [AI로 이름 만들기]로 열면 **null**이다. */
        val character: CharacterFieldAiSuggester.CharacterAiContext? = null,
        /** 세계관 이름 — 은행 진입에서는 결을 가리키는 유일한 축이다. */
        val universeName: String = "",
        /**
         * 같은 세계관 기존 캐릭터 이름. 상한·선별은 [selectNameSamples]가 하므로
         * 호출측은 조회한 것을 그대로 넘기면 된다.
         */
        val existingNames: List<String> = emptyList(),
        /** 이름은행의 미사용 엔트리 이름(이미 쌓아 둔 취향도 결의 일부다 — 설계 7-3). */
        val bankNames: List<String> = emptyList(),
        /** [Mode.GIVEN]에서 고정된 성. */
        val fixedSurname: String = "",
        /** [Mode.SURNAME]에서 고정된 이름. */
        val fixedGiven: String = "",
        /** 성별(GENDER 역할 필드 값). 프롬프트의 결 재료이자 담기의 기본 성별이다. */
        val gender: String = ""
    )

    /** 라운드 요청 — 다발 크기와 상태(유지·회피·지시)가 전부 여기 있다. */
    data class RoundRequest(
        val mode: Mode,
        val batchSize: Int = AiPromptPolicy.NAME_SUGGEST_BATCH_DEFAULT,
        val keep: List<String> = emptyList(),
        val avoid: List<String> = emptyList(),
        val instruction: String = ""
    )

    data class PromptBuild(val text: String, val truncationNotes: List<String>)

    data class ParsedNames(val candidates: List<Candidate>, val droppedCount: Int)

    /**
     * 한 라운드를 실행한다 — **요청 1건**이다(다발 크기와 무관하게 나뉘지 않는다).
     *
     * 나누면 과금 횟수가 배로 늘고, *"후보끼리 겹치지 마라"*는 지시를 줄 수 없어 같은 이름이
     * 여러 요청에서 되돌아온다.
     */
    suspend fun suggest(
        context: NameContext,
        request: RoundRequest,
        /** 창작도 (A-4) — 이름은 창작 폭이 가장 크게 드러나는 자리라 기본값을 그대로 쓴다. */
        creativity: AiCreativity = AiCreativity.DEFAULT,
        errorMessageOf: (AiResult.Failure) -> String
    ): RoundOutcome {
        val wanted = AiPromptPolicy.clampNameSuggestBatch(request.batchSize)
        val prompt = buildUserPrompt(context, request.copy(batchSize = wanted))
        // 학습된 미지원 모델 — 샘플링 없이 지시만 적용된다는 사실을 고지한다 (A-4)
        val temperatureNote =
            creativity != AiCreativity.BALANCED && aiService.isTemperatureUnsupported()
        val aiRequest = AiRequest(
            system = buildSystemPrompt(creativity),
            userText = prompt.text,
            maxTokens = aiService.effectiveMaxTokens(),
            temperature = aiService.temperatureFor(creativity)
        )
        return when (val result = aiService.complete(aiRequest)) {
            is AiResult.Success -> {
                val parsed = parseResponse(result.text, wanted)
                RoundOutcome(
                    candidates = parsed.candidates,
                    droppedCount = parsed.droppedCount,
                    failures = buildList {
                        if (parsed.candidates.isEmpty()) {
                            add(if (result.truncated) TRUNCATED_EMPTY_MESSAGE else PARSE_FAILURE_MESSAGE)
                        } else if (result.truncated) {
                            add(TRUNCATED_PARTIAL_MESSAGE)
                        }
                        if (temperatureNote || result.temperatureOmitted) {
                            add(CharacterFieldAiSuggester.TEMPERATURE_UNSUPPORTED_NOTE)
                        }
                        // 한도로 밀려 다른 프로바이더가 낸 이름 (B-108 확정 ⓑ).
                        AiProviderFallback.switchNoteOf(result)?.let { add(it) }
                    },
                    truncationNotes = prompt.truncationNotes,
                    truncated = result.truncated,
                    inputTokens = result.inputTokens ?: 0,
                    outputTokens = result.outputTokens ?: 0
                )
            }
            is AiResult.Failure -> RoundOutcome(
                candidates = emptyList(),
                droppedCount = 0,
                failures = buildList {
                    add(errorMessageOf(result))
                    if (temperatureNote) add(CharacterFieldAiSuggester.TEMPERATURE_UNSUPPORTED_NOTE)
                },
                truncationNotes = prompt.truncationNotes,
                truncated = false,
                inputTokens = 0,
                outputTokens = 0
            )
        }
    }

    // ===== 은행에 담기 (설계 7-4) =====

    /**
     * 담을 후보 하나의 처분.
     *
     * [usedByCharacter]는 **거르는 사유가 아니라 표식**이다(설계 7-4 ⓒ) — 이미 누군가 쓰는
     * 이름이어도 창고에 두는 것 자체는 사용자의 자유다. 거르는 것은 **은행 중복**뿐이다.
     */
    data class DepositItem(
        val name: String,
        val rejected: Boolean,
        val usedByCharacter: Boolean
    )

    /**
     * [은행에 담기] 계획 — 무엇을 담고 무엇을 왜 걸렀는지.
     *
     * 개수를 세는 이유는 R-14다: 27개를 담겠다고 눌렀는데 14개만 들어가면, 그 차이가
     * 화면에 없으면 사용자는 **13개가 어디로 갔는지 영영 모른다**.
     */
    data class DepositPlan(
        val items: List<DepositItem>,
        /** 은행에 같은 이름이 이미 있어 뺀 수 */
        val filteredInBank: Int,
        /** 풀 안에서 표기만 다른 같은 이름이라 뺀 수 */
        val filteredDuplicate: Int
    ) {
        /** 기본으로 체크되는 것 — ✕는 해제로 둔다(포함할 수는 있다). */
        val defaultChecked: List<DepositItem> get() = items.filter { !it.rejected }
    }

    companion object {

        /** 이름 견본 상한 — 넘친 수는 고지한다(R-14). 설계 7-3이 정한 30이다. */
        const val MAX_NAME_SAMPLES = 30

        /** 은행 견본 상한 — *"상한 소량"*(설계 7-3). 세계관 견본보다 작게 둔다. */
        const val MAX_BANK_SAMPLES = 12

        /**
         * 프롬프트에 실을 유지·회피 목록 상한.
         *
         * **설계가 말하지 않은 자리다.** 회피 목록은 라운드마다 자라 — 30개씩 다섯 라운드면
         * 150개다 — 그대로 실으면 입력 토큰이 후보 본문보다 커지고, 어느 순간 출력이 밀린다.
         * **최근 것을 남긴다**: 방금 거절한 것이 다음 답에 다시 올 확률이 가장 높다.
         * 잘라낸 수는 고지한다(R-14) — 조용히 자르면 *"거절했는데 또 나온다"*가 된다.
         */
        const val MAX_KEEP_IN_PROMPT = 30
        const val MAX_AVOID_IN_PROMPT = 60

        /** 자유 지시 상한 — 한 줄 지시가 프롬프트를 삼키지 않게 한다. */
        const val MAX_INSTRUCTION_CHARS = 300

        /**
         * 이름 한 개의 길이 상한. 넘으면 이름이 아니라 문장이라 버린다 —
         * 폼의 이름 칸에 문단이 들어가는 것을 막는 것은 파서의 몫이다.
         */
        const val MAX_NAME_CHARS = 40

        /** 근거 한 줄 상한 — 행에 들어갈 만큼만 남긴다. */
        const val MAX_REASON_CHARS = 60

        /** 컨텍스트로 실을 다른 필드 수·값 길이 — 이름의 결에 필요한 만큼만. */
        const val MAX_CONTEXT_FIELDS = 12
        const val MAX_CONTEXT_VALUE_CHARS = 40

        const val PARSE_FAILURE_MESSAGE = "응답 형식을 해석할 수 없습니다 — 다시 시도해 주세요"

        const val TRUNCATED_EMPTY_MESSAGE =
            "AI 응답이 출력 상한에 걸려 잘려 이름을 만들지 못했습니다. " +
                "다발 크기를 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        const val TRUNCATED_PARTIAL_MESSAGE =
            "AI 응답이 출력 상한에 걸려 잘렸습니다 — 마지막 후보가 빠졌을 수 있습니다. " +
                "다발 크기를 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        /**
         * 견본 고르기 — **고르게 흩어 뽑는다**(순수, 난수 없음).
         *
         * 앞에서 30개를 자르면 *가장 먼저 만든 캐릭터들*만 실려, 작품이 뒤로 가며 바꾼 표기
         * 관행이 견본에서 통째로 빠진다. 등간격으로 뽑으면 목록 전체가 대표된다.
         * 난수를 쓰지 않는 것은 **같은 데이터면 같은 견본이 나가야** 하기 때문이다
         * (재현 가능한 프롬프트 = 단위 테스트 가능한 프롬프트).
         */
        fun selectNameSamples(names: List<String>, limit: Int = MAX_NAME_SAMPLES): List<String> {
            if (limit <= 0) return emptyList()
            val pool = names.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            if (pool.size <= limit) return pool
            // 등간격 인덱스 — 첫 원소와 마지막 근방까지 고르게 걸친다.
            return (0 until limit).map { i -> pool[(i.toLong() * pool.size / limit).toInt()] }
        }

        fun buildSystemPrompt(creativity: AiCreativity = AiCreativity.DEFAULT): String = """
            당신은 소설 캐릭터의 이름을 짓는 작가 보조다. 주어진 세계관의 결과 지시에 맞는
            이름 후보를 만든다.
            규칙:
            1. 반드시 아래 JSON 스키마로만 응답하고 다른 텍스트를 덧붙이지 마라:
            {"names":[{"name":"이름","why":"짧은 근거"}]}
            2. name은 캐릭터에게 그대로 붙일 이름이다. 설명·번호·따옴표·괄호 주석을 붙이지 마라.
            3. why는 한 문장 이내로 **무엇을 따랐는지**만 적는다(어감·표기 관행·지시 반영).
            4. 요청한 개수만큼 만들고, 후보끼리 서로 겹치거나 한 글자만 다르게 하지 마라.
            5. [이름 견본]이 주어지면 그 표기 관행(음절 수·어감·성씨 유무·쓰는 문자)을 따르되
               견본을 그대로 베끼거나 한 글자만 바꿔 내지 마라.
            6. [유지]는 사용자가 마음에 들어 한 이름이다. 그 결을 따르되 같은 이름을 다시 내지 마라.
            7. [회피]에 있는 이름과 그 사소한 변형은 내지 마라.
            8. 주어진 캐릭터 정보와 어긋나는 이름을 만들지 마라. 정보가 없는 부분은 지어내지 않는다.
        """.trimIndent() + creativity.promptRule()

        fun buildUserPrompt(context: NameContext, request: RoundRequest): PromptBuild {
            val notes = mutableListOf<String>()
            val sb = StringBuilder()

            if (context.universeName.isNotBlank()) {
                sb.append("[세계관] ").append(context.universeName).append('\n')
            }

            val character = context.character
            if (character != null) {
                character.loadFailures.forEach { notes.add("$it 정보를 불러오지 못함") }
                sb.append("\n[캐릭터]\n")
                if (character.name.isNotBlank()) sb.append("현재 이름: ").append(character.name).append('\n')
                if (context.gender.isNotBlank()) sb.append("성별: ").append(context.gender).append('\n')
                if (character.aliases.isNotEmpty()) {
                    sb.append("이명: ").append(character.aliases.joinToString(", ")).append('\n')
                }
                if (character.tags.isNotEmpty()) {
                    sb.append("태그: ").append(character.tags.joinToString(", ")).append('\n')
                }
                if (character.factions.isNotEmpty()) {
                    sb.append("소속: ").append(character.factions.joinToString(", ")).append('\n')
                }
                // 다른 필드 값은 **결의 재료로만** 짧게 싣는다 — 종족·출신·계급이 이름을 정한다.
                // 대결 우열은 싣지 않는다(설계 7장 밖): 우열은 이름의 결과 무관하고 토큰만 쓴다.
                val fields = character.filledFields.filter { it.second.isNotBlank() }
                if (fields.isNotEmpty()) {
                    val shown = fields.take(MAX_CONTEXT_FIELDS)
                    if (fields.size > shown.size) {
                        notes.add("캐릭터 필드 ${fields.size - shown.size}개는 싣지 않았습니다")
                    }
                    shown.forEach { (name, value) ->
                        val capped = if (value.length > MAX_CONTEXT_VALUE_CHARS) {
                            value.take(MAX_CONTEXT_VALUE_CHARS) + "…"
                        } else value
                        sb.append("- ").append(name).append(": ").append(capped).append('\n')
                    }
                }
            }

            val samples = selectNameSamples(context.existingNames)
            if (samples.isNotEmpty()) {
                val total = context.existingNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().size
                if (total > samples.size) notes.add("이름 견본 ${total - samples.size}개 생략")
                sb.append("\n[이름 견본] 같은 세계관의 기존 캐릭터 이름이다. 표기 관행만 따르고 그대로 쓰지 마라.\n")
                sb.append(samples.joinToString(", ")).append('\n')
            }

            val bankSamples = selectNameSamples(context.bankNames, MAX_BANK_SAMPLES)
            if (bankSamples.isNotEmpty()) {
                sb.append("\n[모아 둔 이름] 사용자가 이름은행에 쌓아 둔 취향이다. 결의 참고로만 쓴다.\n")
                sb.append(bankSamples.joinToString(", ")).append('\n')
            }

            val keep = capList(request.keep, MAX_KEEP_IN_PROMPT)
            if (keep.shown.isNotEmpty()) {
                if (keep.omitted > 0) notes.add("유지 목록 ${keep.omitted}개는 싣지 않았습니다")
                sb.append("\n[유지] ").append(keep.shown.joinToString(", ")).append('\n')
            }
            val avoid = capList(request.avoid, MAX_AVOID_IN_PROMPT)
            if (avoid.shown.isNotEmpty()) {
                if (avoid.omitted > 0) notes.add("회피 목록 ${avoid.omitted}개는 싣지 않았습니다")
                sb.append("[회피] ").append(avoid.shown.joinToString(", ")).append('\n')
            }

            sb.append("\n[지시]\n")
            sb.append(modeInstruction(request.mode, context)).append('\n')
            val free = request.instruction.trim()
            if (free.isNotEmpty()) {
                val capped = if (free.length > MAX_INSTRUCTION_CHARS) {
                    notes.add("추가 지시 ${MAX_INSTRUCTION_CHARS}자 초과분 생략")
                    free.take(MAX_INSTRUCTION_CHARS)
                } else free
                sb.append("추가 지시: ").append(capped).append('\n')
            }
            sb.append("서로 다른 후보 ").append(request.batchSize).append("개를 만든다.\n")

            return PromptBuild(sb.toString(), notes)
        }

        private data class Capped(val shown: List<String>, val omitted: Int)

        /** 뒤(최근)를 남기고 앞을 자른다 — 방금 거절한 것이 다음 답에 다시 올 확률이 가장 높다. */
        private fun capList(values: List<String>, limit: Int): Capped {
            val clean = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (clean.size <= limit) return Capped(clean, 0)
            return Capped(clean.takeLast(limit), clean.size - limit)
        }

        fun modeInstruction(mode: Mode, context: NameContext): String = when (mode) {
            Mode.FULL -> "이 세계관에 어울리는 캐릭터 이름 전체를 짓는다."
            Mode.GIVEN ->
                "성은 '${context.fixedSurname.trim()}'으로 고정이다. **이름(성을 뺀 부분)만** 짓는다. " +
                    "응답의 name에 성을 붙이지 마라."
            Mode.SURNAME ->
                "이름은 '${context.fixedGiven.trim()}'으로 고정이다. **성(가문명)만** 짓는다. " +
                    "응답의 name에 이름을 붙이지 마라."
            Mode.ALIAS ->
                "본명이 아니라 **이명·칭호**를 짓는다(예: 시간의 용사). 사람들이 그를 부르는 별칭이다."
        }

        /**
         * 응답 → 후보 목록. 형식 이탈은 **거부가 아니라 정리해 수용한다** — 유료 응답이라
         * 고칠 수 있는 것을 버리지 않는다(따옴표·번호 접두는 흔한 이탈이다).
         *
         * 버리는 것은 셋뿐이고 전부 개수로 돌려준다: 빈 이름 · 응답 안 중복 · 요청 개수 초과.
         */
        fun parseResponse(text: String, wanted: Int): ParsedNames {
            val obj = AiJsonExtractor.extractObject(text) ?: return ParsedNames(emptyList(), 0)
            val arr = obj.optJSONArray("names") ?: return ParsedNames(emptyList(), 0)
            val out = mutableListOf<Candidate>()
            val seen = HashSet<String>()
            var dropped = 0
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                // 문자열만 준 응답도 받는다 — {"names":["아리네"]}는 흔한 이탈이고 뜻은 명백하다.
                val rawName = when (item) {
                    is String -> item
                    is org.json.JSONObject -> item.optString("name")
                    else -> null
                }
                val name = cleanName(rawName)
                if (name.isNullOrBlank()) { dropped++; continue }
                if (!seen.add(NameBankMatch.normalize(name))) { dropped++; continue }
                if (out.size >= wanted) { dropped++; continue }
                val reason = (item as? org.json.JSONObject)?.optString("why").orEmpty().trim()
                    .let { if (it.length > MAX_REASON_CHARS) it.take(MAX_REASON_CHARS) + "…" else it }
                out.add(Candidate(name, reason))
            }
            return ParsedNames(out, dropped)
        }

        private val NUMBER_PREFIX_RE = Regex("""^\s*\d+\s*[.)：:]\s*""")

        /** 감싼 따옴표·번호 접두를 걷는다. 이름 안의 공백·문장부호는 건드리지 않는다. */
        fun cleanName(raw: String?): String? {
            if (raw == null) return null
            var t = raw.trim().replace(NUMBER_PREFIX_RE, "").trim()
            if (t.length >= 2 &&
                ((t.startsWith('"') && t.endsWith('"')) ||
                    (t.startsWith('\'') && t.endsWith('\'')) ||
                    (t.startsWith('“') && t.endsWith('”')))
            ) {
                t = t.substring(1, t.length - 1).trim()
            }
            if (t.length > MAX_NAME_CHARS) return null
            return t
        }

        /**
         * [은행에 담기] 계획 (설계 7-4).
         *
         * 거르는 축은 둘뿐이다 — **은행 중복**과 **풀 안 표기 중복**. 이미 캐릭터가 쓰는
         * 이름은 거르지 않고 [DepositItem.usedByCharacter]로 표시한다: 창고에 두는 것과
         * 지금 쓰이는 것은 다른 사실이고, 어느 쪽이 쓸모 있는지는 사용자가 가린다(자율성).
         *
         * 같음의 판정은 전부 [NameBankMatch]다 — 저장 시 자동 대조와 한 벌이어야 한다.
         */
        fun depositPlan(
            pool: Pool,
            bankNames: List<String>,
            characterNames: Collection<String>
        ): DepositPlan {
            val inBank = bankNames.mapTo(HashSet()) { NameBankMatch.normalize(it) }
            val used = characterNames.mapTo(HashSet()) { NameBankMatch.normalize(it) }
            val seen = HashSet<String>()
            val items = ArrayList<DepositItem>()
            var filteredInBank = 0
            var filteredDuplicate = 0
            for (c in pool.candidates) {
                val key = NameBankMatch.normalize(c.name)
                if (key.isEmpty()) continue
                if (key in inBank) { filteredInBank++; continue }
                if (!seen.add(key)) { filteredDuplicate++; continue }
                items.add(
                    DepositItem(
                        name = c.name.trim(),
                        rejected = pool.markOf(c.name) == Mark.REJECT,
                        usedByCharacter = key in used
                    )
                )
            }
            return DepositPlan(items, filteredInBank, filteredDuplicate)
        }
    }
}
