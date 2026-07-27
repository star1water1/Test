package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.RandomConfig
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig

/**
 * 캐릭터 필드 값 AI 추천 — 생일 포함 모든 편집 가능 필드의 값을 추천 이유와 함께 제안한다.
 *
 * 계약 (docs/ai_integration.md):
 * - 온디맨드 전용. 호출측이 hasUsableProvider() 가드 + 실행 전 비용(요청 수) 고지.
 * - AI 출력은 절대 자동 적용하지 않는다 — 검토 UI에서 사용자가 선택 적용(폼 위젯에만 기입).
 * - 검증(변수 제어): 형식·옵션에 맞지 않는 제안은 드롭하고 드롭 수를 보고한다.
 * - 컨텍스트 절단은 조용히 하지 않는다 — truncationNotes로 전부 표면화 (R-14).
 *
 * 프롬프트 조립·응답 파싱은 AiService 미호출 순수 함수로 분리되어 단위 테스트된다.
 */
class CharacterFieldAiSuggester(private val aiService: AiService) {

    /** 프롬프트에 넣을 캐릭터 컨텍스트 스냅샷 — 호출측(편집 화면)이 라이브 위젯+DB에서 조립 */
    data class CharacterAiContext(
        val name: String,
        val aliases: List<String>,
        val tags: List<String>,
        val memo: String,
        /** (필드 표시명, 현재 값) — 폼의 라이브 입력값. 추천 대상 필드는 프롬프트 조립 시 제외된다 */
        val filledFields: List<Pair<String, String>>,
        val imageTags: List<String>,
        /** 활성 소속 세력명 목록 */
        val factions: List<String>,
        /** "상대이름 – 관계유형" 요약 목록 */
        val relationships: List<String>
    )

    /** 추천 대상 필드 스펙 — [fieldSpecOf]로 FieldDefinition에서 파생 */
    data class FieldSpec(
        val key: String,
        val name: String,
        val type: FieldType,
        /** SELECT/GRADE 실제 옵션 — 응답 검증의 기준 */
        val options: List<String>,
        val isBirthDate: Boolean,
        /** NUMBER 랜덤 설정의 min~max (있으면 프롬프트 힌트로만 사용) */
        val numberRange: Pair<Double, Double>?,
        /** 현재 입력값 — 덮어쓰기 제안 표시·동일값 제안 드롭 기준. 빈 필드는 "" */
        val currentValue: String,
        /** 구조화 입력·생일 등 형식 지시 문구 (프롬프트용) */
        val formatHint: String? = null
    )

    data class Suggestion(
        val fieldKey: String,
        val value: String,
        val reason: String
    )

    data class SuggestOutcome(
        val suggestions: List<Suggestion>,
        /** 형식·옵션 불일치, 미지 key, 중복 등으로 제외된 제안 수 (조용히 버리지 않고 고지) */
        val droppedCount: Int,
        /** 요청 실패·파싱 실패 메시지 — 부분 실패도 성공분과 함께 반환 */
        val failures: List<String>,
        /** 프롬프트 조립 시 절단된 컨텍스트 고지 (R-14) */
        val truncationNotes: List<String>,
        val inputTokens: Int,
        val outputTokens: Int
    )

    suspend fun suggest(
        context: CharacterAiContext,
        targets: List<FieldSpec>,
        errorMessageOf: (AiResult.Failure) -> String
    ): SuggestOutcome {
        val prompt = buildUserPrompt(context, targets)
        val request = AiRequest(
            system = buildSystemPrompt(),
            userText = prompt.text,
            maxTokens = 4096
        )
        return when (val result = aiService.complete(request)) {
            is AiResult.Success -> {
                val parsed = parseResponse(result.text, targets)
                if (parsed == null) {
                    SuggestOutcome(
                        suggestions = emptyList(),
                        droppedCount = 0,
                        failures = listOf(PARSE_FAILURE_MESSAGE),
                        truncationNotes = prompt.truncationNotes,
                        inputTokens = result.inputTokens ?: 0,
                        outputTokens = result.outputTokens ?: 0
                    )
                } else {
                    SuggestOutcome(
                        suggestions = parsed.suggestions,
                        droppedCount = parsed.droppedCount,
                        failures = emptyList(),
                        truncationNotes = prompt.truncationNotes,
                        inputTokens = result.inputTokens ?: 0,
                        outputTokens = result.outputTokens ?: 0
                    )
                }
            }
            is AiResult.Failure -> SuggestOutcome(
                suggestions = emptyList(),
                droppedCount = 0,
                failures = listOf(errorMessageOf(result)),
                truncationNotes = prompt.truncationNotes,
                inputTokens = 0,
                outputTokens = 0
            )
        }
    }

    companion object {
        // 컨텍스트 절단 상한 — 초과분은 truncationNotes로 반드시 고지 (R-14)
        const val MAX_MEMO_CHARS = 1500
        const val MAX_VALUE_CHARS = 300
        const val MAX_TAGS = 50
        const val MAX_RELATIONSHIPS = 30
        const val MAX_FILLED_FIELDS = 60

        const val PARSE_FAILURE_MESSAGE = "응답 형식을 해석할 수 없습니다 — 다시 시도해 주세요"

        /**
         * FieldDefinition → 추천 대상 스펙. CALCULATED(파생값)·알 수 없는 타입은 null.
         * BODY_SIZE 기본 B-W-H와 BIRTH_DATE 월/일 구조화는 폼 빌더(DynamicFieldFormBuilder)의
         * 자동 적용 규칙과 동일하게 형식 힌트를 만든다.
         */
        fun fieldSpecOf(field: FieldDefinition, currentValue: String): FieldSpec? {
            val type = FieldType.fromName(field.type) ?: return null
            if (type == FieldType.CALCULATED) return null
            val isBirth = SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE
            val options = when (type) {
                FieldType.SELECT -> com.novelcharacter.app.util.FieldOptionParser.parseSelectOptions(field.config)
                FieldType.GRADE -> com.novelcharacter.app.util.FieldOptionParser.parseGradeOptions(field.config)
                else -> emptyList()
            }
            val random = RandomConfig.fromConfig(field.config)
            val numberRange = if (type == FieldType.NUMBER && random.min != null && random.max != null) {
                random.min to random.max
            } else null
            val formatHint: String? = when {
                isBirth -> "MM-DD (월-일, 예: 03-15)"
                type == FieldType.MULTI_TEXT -> "콤마로 구분한 복수 값 (예: 값1, 값2)"
                else -> {
                    var structured = StructuredInputConfig.fromConfig(field.config)
                    if (type == FieldType.BODY_SIZE && !structured.enabled) {
                        structured = StructuredInputConfig(
                            enabled = true,
                            separator = "-",
                            parts = listOf(
                                StructuredInputConfig.Part("B", "cm", "number"),
                                StructuredInputConfig.Part("W", "cm", "number"),
                                StructuredInputConfig.Part("H", "cm", "number")
                            )
                        )
                    }
                    if (structured.enabled && structured.parts.isNotEmpty()) {
                        structured.parts.joinToString(structured.separator) { it.label } +
                            " 형식 (구분자 '" + structured.separator + "')"
                    } else null
                }
            }
            return FieldSpec(
                key = field.key,
                name = field.name,
                type = type,
                options = options,
                isBirthDate = isBirth,
                numberRange = numberRange,
                currentValue = currentValue,
                formatHint = formatHint
            )
        }

        fun buildSystemPrompt(): String = """
            당신은 소설 캐릭터 설정 도우미다. 주어진 캐릭터 정보를 근거로 요청된 필드의 값을 추천하라.
            규칙:
            1. 반드시 아래 JSON 스키마로만 응답하고 다른 텍스트를 덧붙이지 마라:
            {"suggestions":[{"key":"필드키","value":"추천값","reason":"근거 한 문장"}]}
            2. key는 [추천할 필드]에 제시된 key만 사용한다.
            3. '옵션'이 제시된 필드는 그 옵션 중 하나만 쓴다. 옵션에 없는 값을 만들지 마라.
            4. '형식' 지시가 있는 필드는 형식을 정확히 지킨다 (예: 생일 MM-DD → 03-15).
            5. reason에는 캐릭터의 어떤 정보(태그·메모·다른 필드·이미지 태그·소속·관계)에서 추론했는지 한국어 한 문장으로 쓴다.
            6. 근거가 부족해 추천할 수 없는 필드는 응답에서 생략한다.
        """.trimIndent()

        data class PromptBuild(val text: String, val truncationNotes: List<String>)

        fun buildUserPrompt(context: CharacterAiContext, targets: List<FieldSpec>): PromptBuild {
            val notes = mutableListOf<String>()

            fun <T> capList(list: List<T>, max: Int, label: String): List<T> =
                if (list.size > max) {
                    notes.add("$label ${list.size - max}건 생략 (상한 ${max}건)")
                    list.take(max)
                } else list

            fun capText(text: String, max: Int, label: String): String =
                if (text.length > max) {
                    notes.add("$label ${max}자 초과분 생략")
                    text.take(max) + "…"
                } else text

            val sb = StringBuilder()
            sb.append("[캐릭터]\n")
            sb.append("이름: ").append(context.name.trim().ifEmpty { "(미정)" })
            if (context.aliases.isNotEmpty()) {
                sb.append(" (이명: ").append(context.aliases.joinToString(", ")).append(')')
            }
            sb.append('\n')
            if (context.tags.isNotEmpty()) {
                sb.append("태그: ").append(capList(context.tags, MAX_TAGS, "태그").joinToString(", ")).append('\n')
            }
            if (context.memo.isNotBlank()) {
                sb.append("메모: ").append(capText(context.memo.trim(), MAX_MEMO_CHARS, "메모")).append('\n')
            }
            if (context.imageTags.isNotEmpty()) {
                sb.append("이미지 태그: ")
                    .append(capList(context.imageTags, MAX_TAGS, "이미지 태그").joinToString(", ")).append('\n')
            }
            if (context.factions.isNotEmpty()) {
                sb.append("소속 세력: ").append(context.factions.joinToString(", ")).append('\n')
            }
            if (context.relationships.isNotEmpty()) {
                sb.append("관계: ")
                    .append(capList(context.relationships, MAX_RELATIONSHIPS, "관계").joinToString(" / ")).append('\n')
            }

            // 추천 대상 필드는 [입력된 필드]에서 제외 — 대상의 현재 값은 필드 스펙 쪽에 실린다
            val targetNames = targets.mapTo(HashSet()) { it.name }
            val filled = context.filledFields.filter { it.first !in targetNames && it.second.isNotBlank() }
            if (filled.isNotEmpty()) {
                sb.append("[입력된 필드]\n")
                var longValues = 0
                for ((name, value) in capList(filled, MAX_FILLED_FIELDS, "입력된 필드")) {
                    val v = if (value.length > MAX_VALUE_CHARS) {
                        longValues++
                        value.take(MAX_VALUE_CHARS) + "…"
                    } else value
                    sb.append(name).append(": ").append(v).append('\n')
                }
                if (longValues > 0) notes.add("긴 필드값 ${longValues}건을 ${MAX_VALUE_CHARS}자로 절단")
            }

            sb.append("[추천할 필드]\n")
            for (t in targets) {
                sb.append("- key: ").append(t.key)
                    .append(" / 이름: ").append(t.name)
                    .append(" / 타입: ").append(typeLabel(t.type))
                if (t.options.isNotEmpty()) sb.append(" / 옵션: ").append(t.options.joinToString(", "))
                t.numberRange?.let { (min, max) ->
                    sb.append(" / 범위: ").append(formatNumber(min)).append('~').append(formatNumber(max))
                }
                t.formatHint?.let { sb.append(" / 형식: ").append(it) }
                if (t.currentValue.isNotBlank()) {
                    sb.append(" / 현재 값: ").append(t.currentValue.take(MAX_VALUE_CHARS))
                }
                sb.append('\n')
            }
            return PromptBuild(sb.toString(), notes)
        }

        data class ParsedSuggestions(val suggestions: List<Suggestion>, val droppedCount: Int)

        /**
         * 응답 파싱 + 실제 필드 정의 기준 검증 (AiService 미호출 — 단위 테스트 대상).
         * 드롭 규칙: 미지 key, 같은 key 중복(첫 건만 채택), 빈 값, SELECT/GRADE 옵션 불일치,
         * NUMBER 비수치(선행 숫자 추출 실패), 생일 형식·달력 위반, 현재 값과 동일한 제안.
         */
        fun parseResponse(text: String, targets: List<FieldSpec>): ParsedSuggestions? {
            val root = AiJsonExtractor.extractObject(text) ?: return null
            val arr = root.optJSONArray("suggestions") ?: return ParsedSuggestions(emptyList(), 0)
            val byKey = targets.associateBy { it.key }
            val seenKeys = mutableSetOf<String>()
            var dropped = 0
            val out = mutableListOf<Suggestion>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val key = obj.optString("key").trim()
                val rawValue = obj.optString("value").trim()
                val reason = obj.optString("reason").trim()
                if (key.isEmpty() && rawValue.isEmpty()) continue
                val spec = byKey[key]
                if (spec == null || rawValue.isEmpty()) { dropped++; continue }
                if (!seenKeys.add(key)) { dropped++; continue }
                val value = normalizeValue(rawValue, spec)
                if (value == null || value == spec.currentValue) { dropped++; continue }
                out.add(Suggestion(key, value, reason))
            }
            return ParsedSuggestions(out, dropped)
        }

        /** 타입별 값 정규화 — 통과 못 하면 null(드롭) */
        fun normalizeValue(raw: String, spec: FieldSpec): String? = when {
            spec.isBirthDate -> normalizeBirthDate(raw)
            spec.type == FieldType.SELECT || spec.type == FieldType.GRADE ->
                spec.options.firstOrNull { it == raw }
            spec.type == FieldType.NUMBER -> normalizeNumber(raw)
            else -> raw
        }

        /** "M-D" 관용 수용 + 달력 유효성(2/29 허용) 검증 후 "MM-DD" 정규화. 실패 시 null */
        fun normalizeBirthDate(raw: String): String? {
            val match = Regex("^(\\d{1,2})-(\\d{1,2})$").find(raw.trim()) ?: return null
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            if (month !in 1..12) return null
            val maxDay = when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                else -> 29
            }
            if (day !in 1..maxDay) return null
            return String.format(java.util.Locale.US, "%02d-%02d", month, day)
        }

        /** 수치 정규화 — 단위가 붙었으면("172cm") 선행 숫자만 추출. 실패 시 null */
        fun normalizeNumber(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.toDoubleOrNull() != null) return trimmed
            return Regex("^-?\\d+(?:\\.\\d+)?").find(trimmed)?.value
        }

        private fun typeLabel(type: FieldType): String = when (type) {
            FieldType.TEXT -> "텍스트"
            FieldType.NUMBER -> "숫자"
            FieldType.SELECT -> "선택형"
            FieldType.MULTI_TEXT -> "복수 텍스트"
            FieldType.GRADE -> "등급"
            FieldType.CALCULATED -> "자동 계산"
            FieldType.BODY_SIZE -> "신체 사이즈"
        }

        private fun formatNumber(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
