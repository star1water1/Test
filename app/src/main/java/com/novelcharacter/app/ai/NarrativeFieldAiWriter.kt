package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 서술형 필드(긴 글) 작성 보조 — 초안 · 이어쓰기 · 다듬기 · 늘리기 · 줄이기.
 *
 * [CharacterFieldAiSuggester]와 **경로를 나눈 이유**: 그쪽은 짧은 값 전용 설계다
 * (요청당 여러 필드를 묶고, 근거는 한 문장, 컨텍스트 값은 300자로 절단). 산문을 그 틀에
 * 끼워 넣으면 둘 다 망가진다. 그래서 여기는 **필드 하나 전용**이고 출력 예산을 통째로 쓴다.
 *
 * 계약 (docs/ai_integration.md):
 * - 온디맨드 전용. 호출측이 hasUsableProvider() 가드 + 실행 전 비용 고지.
 * - **원문을 덮어쓰지 않는다.** 결과는 후보로 돌려주고 사용자가 고른 것만 폼 위젯에 기입한다.
 * - 절단은 조용히 두지 않는다 — 잘렸으면 그 사실과 교정 경로를 함께 돌려준다.
 * - 프롬프트 조립·응답 파싱은 AiService 미호출 순수 함수로 분리해 단위 테스트한다.
 */
class NarrativeFieldAiWriter(private val aiService: AiService) {

    /** 무엇을 시킬 것인가. 빈 칸 채우기보다 **이미 쓴 글을 다루는 쪽**이 실사용에서 더 잦다. */
    enum class Mode(val key: String) {
        /** 빈 필드에 새로 쓴다. */
        DRAFT("draft"),

        /** 쓰다 만 글의 뒤를 잇는다. */
        CONTINUE("continue"),

        /** 이미 쓴 글을 다듬는다(내용 유지, 표현 개선). */
        POLISH("polish"),

        /** 더 자세히. */
        EXPAND("expand"),

        /** 더 짧게. */
        SHORTEN("shorten");

        /** 원문이 없으면 성립하지 않는 모드 — UI가 빈 필드에서 감추는 기준이자 계산의 가드. */
        val requiresExisting: Boolean get() = this != DRAFT
    }

    /** 분량 지시. 절대 글자 수를 강제하지 않고 지침으로만 준다(모델이 잘라먹지 않도록). */
    enum class Length(val key: String, val hint: String) {
        SHORT("short", "2~3문장"),
        MEDIUM("medium", "한 문단(4~6문장)"),
        LONG("long", "두세 문단");
    }

    data class Draft(val text: String)

    data class WriteOutcome(
        /** 사용자가 고를 후보들. 비어 있으면 [failures]에 사유가 있다. */
        val drafts: List<Draft>,
        /** 형식·빈 응답 등으로 버린 후보 수 (조용히 버리지 않고 고지) */
        val droppedCount: Int,
        val failures: List<String>,
        /** 프롬프트 조립 시 절단된 컨텍스트 고지 (R-14) */
        val truncationNotes: List<String>,
        /** 출력이 상한에 걸려 잘렸는지 — 후보가 문장 중간에서 끊겼을 수 있다. */
        val truncated: Boolean,
        val inputTokens: Int,
        val outputTokens: Int
    )

    /**
     * 후보 [variants]개를 **한 요청**으로 받는다 — 요청을 나누면 과금 횟수가 배로 늘고,
     * 후보끼리 서로 다르게 쓰라는 지시도 못 준다.
     */
    suspend fun write(
        context: CharacterFieldAiSuggester.CharacterAiContext,
        field: FieldSpec,
        mode: Mode,
        length: Length,
        variants: Int = DEFAULT_VARIANTS,
        errorMessageOf: (AiResult.Failure) -> String
    ): WriteOutcome {
        val wanted = variants.coerceIn(1, MAX_VARIANTS)
        val prompt = buildUserPrompt(context, field, mode, length, wanted)
        val request = AiRequest(
            system = buildSystemPrompt(),
            userText = prompt.text,
            maxTokens = aiService.effectiveMaxTokens()
        )
        return when (val result = aiService.complete(request)) {
            is AiResult.Success -> {
                val parsed = parseResponse(result.text, wanted)
                WriteOutcome(
                    drafts = parsed.drafts,
                    droppedCount = parsed.droppedCount,
                    failures = buildList {
                        if (parsed.drafts.isEmpty()) {
                            add(if (result.truncated) TRUNCATED_EMPTY_MESSAGE else PARSE_FAILURE_MESSAGE)
                        } else if (result.truncated) {
                            add(TRUNCATED_PARTIAL_MESSAGE)
                        }
                    },
                    truncationNotes = prompt.truncationNotes,
                    truncated = result.truncated,
                    inputTokens = result.inputTokens ?: 0,
                    outputTokens = result.outputTokens ?: 0
                )
            }
            is AiResult.Failure -> WriteOutcome(
                drafts = emptyList(),
                droppedCount = 0,
                failures = listOf(errorMessageOf(result)),
                truncationNotes = prompt.truncationNotes,
                truncated = false,
                inputTokens = 0,
                outputTokens = 0
            )
        }
    }

    /** 대상 필드 스펙 — 서술형은 옵션·형식 제약이 없으므로 짧은 값 스펙보다 단순하다. */
    data class FieldSpec(
        val key: String,
        val name: String,
        val groupName: String,
        /** 현재 입력값. [Mode.requiresExisting] 모드는 이것이 비면 성립하지 않는다. */
        val currentValue: String
    )

    data class ParsedDrafts(val drafts: List<Draft>, val droppedCount: Int)
    data class PromptBuild(val text: String, val truncationNotes: List<String>)

    companion object {
        const val DEFAULT_VARIANTS = 2
        const val MAX_VARIANTS = 3

        /** 원문 절단 상한 — 짧은 값 경로의 300자로는 성격 묘사를 근거로 쓸 수 없다. */
        const val MAX_EXISTING_CHARS = 4000

        /** 컨텍스트의 다른 필드 값 절단 상한(서술형 전용 — 짧은 값 경로보다 넉넉히). */
        const val MAX_CONTEXT_VALUE_CHARS = 800

        const val PARSE_FAILURE_MESSAGE = "응답 형식을 해석할 수 없습니다 — 다시 시도해 주세요"

        const val TRUNCATED_EMPTY_MESSAGE =
            "AI 응답이 출력 상한에 걸려 잘려 초안을 만들지 못했습니다. " +
                "분량을 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        const val TRUNCATED_PARTIAL_MESSAGE =
            "AI 응답이 출력 상한에 걸려 잘렸습니다 — 마지막 초안이 문장 중간에서 끊겼을 수 있습니다. " +
                "분량을 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        fun fieldSpecOf(field: FieldDefinition, currentValue: String) = FieldSpec(
            key = field.key,
            name = field.name,
            groupName = field.groupName,
            currentValue = currentValue
        )

        /** 이 필드·현재값에서 실제로 고를 수 있는 모드. 원문이 없으면 초안만 가능하다. */
        fun availableModes(currentValue: String): List<Mode> =
            if (currentValue.isBlank()) listOf(Mode.DRAFT)
            else Mode.entries.filter { it.requiresExisting }

        fun buildSystemPrompt(): String = """
            당신은 소설 캐릭터 설정을 함께 쓰는 작가 보조다. 주어진 캐릭터 정보와 지시에 따라
            지정된 필드에 들어갈 **한국어 산문**을 쓴다.
            규칙:
            1. 반드시 아래 JSON 스키마로만 응답하고 다른 텍스트를 덧붙이지 마라:
            {"drafts":["초안1","초안2"]}
            2. drafts의 각 원소는 그 필드에 **그대로 들어갈 본문**이다. 제목·머리말·따옴표·
               "초안1:" 같은 라벨을 넣지 마라.
            3. 요청된 개수만큼 쓰되, 후보끼리는 **서로 뚜렷하게 다른 방향**이어야 한다.
            4. 주어진 캐릭터 정보와 모순되는 내용을 지어내지 마라. 정보가 없는 부분은
               단정하지 말고 여지를 남긴다.
            5. 원문이 주어진 경우 그 인물·설정·문체를 유지한다.
        """.trimIndent()

        fun buildUserPrompt(
            context: CharacterFieldAiSuggester.CharacterAiContext,
            field: FieldSpec,
            mode: Mode,
            length: Length,
            variants: Int
        ): PromptBuild {
            val notes = mutableListOf<String>()
            context.loadFailures.forEach { notes.add("$it 정보를 불러오지 못함") }

            val sb = StringBuilder()
            sb.append("[캐릭터]\n")
            sb.append("이름: ").append(context.name).append('\n')
            if (context.aliases.isNotEmpty()) sb.append("이명: ").append(context.aliases.joinToString(", ")).append('\n')
            if (context.tags.isNotEmpty()) sb.append("태그: ").append(context.tags.joinToString(", ")).append('\n')
            if (context.factions.isNotEmpty()) sb.append("소속: ").append(context.factions.joinToString(", ")).append('\n')
            if (context.relationships.isNotEmpty()) {
                sb.append("관계: ").append(context.relationships.joinToString(" / ")).append('\n')
            }
            if (context.imageTags.isNotEmpty()) {
                sb.append("이미지 태그: ").append(context.imageTags.joinToString(", ")).append('\n')
            }
            if (context.memo.isNotBlank()) sb.append("메모: ").append(context.memo).append('\n')

            // 대상 필드 자신은 컨텍스트에서 제외한다 — 원문은 아래 [원문] 절에 따로 싣는다.
            val others = context.filledFields.filter { it.first != field.name }
            if (others.isNotEmpty()) {
                sb.append("\n[다른 필드]\n")
                others.forEach { (name, value) ->
                    val capped = if (value.length > MAX_CONTEXT_VALUE_CHARS) {
                        notes.add("'$name' 값이 길어 ${MAX_CONTEXT_VALUE_CHARS}자까지만 참고했습니다")
                        value.take(MAX_CONTEXT_VALUE_CHARS)
                    } else value
                    sb.append("- ").append(name).append(": ").append(capped).append('\n')
                }
            }

            sb.append("\n[대상 필드]\n")
            sb.append("[").append(field.groupName).append("] ").append(field.name).append('\n')

            if (mode.requiresExisting) {
                val existing = if (field.currentValue.length > MAX_EXISTING_CHARS) {
                    notes.add("원문이 길어 ${MAX_EXISTING_CHARS}자까지만 참고했습니다")
                    field.currentValue.take(MAX_EXISTING_CHARS)
                } else field.currentValue
                sb.append("\n[원문]\n").append(existing).append('\n')
            }

            sb.append("\n[지시]\n")
            sb.append(instructionFor(mode)).append('\n')
            sb.append("분량: ").append(length.hint).append('\n')
            sb.append("서로 다른 방향의 초안 ").append(variants).append("개를 쓴다.\n")

            return PromptBuild(sb.toString(), notes)
        }

        private fun instructionFor(mode: Mode): String = when (mode) {
            Mode.DRAFT -> "이 필드의 본문을 새로 쓴다."
            Mode.CONTINUE -> "[원문]의 **뒤를 이어서** 쓴다. 원문은 다시 쓰지 말고 이어질 부분만 쓴다."
            Mode.POLISH -> "[원문]의 **내용은 유지**하고 표현만 다듬어 전체를 다시 쓴다."
            Mode.EXPAND -> "[원문]을 바탕으로 더 자세하게 확장해 전체를 다시 쓴다."
            Mode.SHORTEN -> "[원문]의 핵심만 남겨 더 짧게 다시 쓴다."
        }

        /**
         * 응답 → 초안 목록. 공백·중복 후보는 버리고 개수를 [ParsedDrafts.droppedCount]로 돌려준다.
         * 모델이 라벨("초안1:")이나 따옴표를 붙이는 흔한 이탈은 거부 대신 정리해 수용한다.
         */
        fun parseResponse(text: String, wanted: Int): ParsedDrafts {
            val obj = AiJsonExtractor.extractObject(text) ?: return ParsedDrafts(emptyList(), 0)
            val arr = obj.optJSONArray("drafts") ?: return ParsedDrafts(emptyList(), 0)
            val out = mutableListOf<Draft>()
            var dropped = 0
            for (i in 0 until arr.length()) {
                val raw = arr.opt(i) as? String
                val cleaned = raw?.let { cleanDraft(it) }
                if (cleaned.isNullOrBlank()) { dropped++; continue }
                if (out.any { it.text == cleaned }) { dropped++; continue }
                if (out.size >= wanted) { dropped++; continue }
                out.add(Draft(cleaned))
            }
            return ParsedDrafts(out, dropped)
        }

        private val LABEL_RE = Regex("""^\s*(초안|draft)\s*\d*\s*[:：.]\s*""", RegexOption.IGNORE_CASE)

        /** 라벨·감싼 따옴표 제거. 본문 안의 따옴표는 건드리지 않는다. */
        fun cleanDraft(raw: String): String {
            var t = raw.trim().replace(LABEL_RE, "").trim()
            if (t.length >= 2 &&
                ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('“') && t.endsWith('”')))
            ) {
                t = t.substring(1, t.length - 1).trim()
            }
            return t
        }
    }
}
