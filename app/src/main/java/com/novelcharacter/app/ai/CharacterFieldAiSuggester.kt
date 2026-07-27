package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.FieldValueLibraryConfig
import com.novelcharacter.app.data.model.RandomConfig
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.util.FieldValueTokenizer

/**
 * 캐릭터 필드 값 AI 추천 — 생일 포함 모든 편집 가능 필드의 값을 추천 이유와 함께 제안한다.
 *
 * 계약 (docs/ai_integration.md):
 * - 온디맨드 전용. 호출측이 hasUsableProvider() 가드 + 실행 전 비용(요청 수) 고지.
 * - AI 출력은 절대 자동 적용하지 않는다 — 검토 UI에서 사용자가 선택 적용(폼 위젯에만 기입).
 * - 검증(변수 제어): 형식·옵션에 맞지 않는 제안은 드롭하고 드롭 수를 보고한다.
 * - 컨텍스트 절단은 조용히 하지 않는다 — truncationNotes로 전부 표면화 (R-14).
 *
 * **표기 기조(일관성)**: 캐릭터 한 명의 정보만 주면 모델은 이 작품이 그 필드를 어떤 표기·
 * 상세도로 써 왔는지 알 길이 없어, 기존 값들과 어긋난 표기("짙은 밤하늘빛 흑청색")를
 * 만들어 낸다. 그래서 각 대상 필드마다 **값 라이브러리(field_value_entries)의 기존 값 예시**를
 * 함께 싣는다 — 라이브러리는 이미 필드별로 중복 없이(UNIQUE) 정규화된 카탈로그이고
 * 사용 빈도까지 들고 있어, 캐릭터 전체를 훑지 않고 1쿼리로 "이 작품의 기조"를 얻는다.
 * 값 전량이 아니라 [selectUsageExamples]가 고른 소수만 실어 토큰을 통제한다.
 * 응답 쪽도 같은 카탈로그로 접는다: 별칭 표기는 canonical로 교정하고(값 분열 방지),
 * `inputMode=restricted` 필드는 저장 시 검증과 같은 규칙으로 목록 밖 값을 드롭한다.
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
        val relationships: List<String>,
        /** 조회에 실패해 프롬프트에서 빠진 섹션명 — 절단 고지와 같은 경로로 표면화 (변수 제어) */
        val loadFailures: List<String> = emptyList()
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
        val formatHint: String? = null,
        /** 구조화 입력 검증용 구분자·파트 수 — 폼이 구조화 위젯을 렌더하는 필드(TEXT/BODY_SIZE)에만 설정 */
        val structuredSeparator: String? = null,
        val structuredPartCount: Int? = null,
        /** 값 라이브러리 조회 키. 0이면 라이브러리 연동 없음(순수 파싱 테스트 등) */
        val fieldId: Long = 0L,
        /**
         * 이 필드가 값 라이브러리 카탈로그 대상이며 제안이 켜져 있는가.
         * `inputMode=free`(제안 끔)는 사용자가 "이 필드엔 기존 값을 들이대지 마라"고 정한 것이므로
         * 용례도 접기도 하지 않는다 — 자율성은 AI 경로에서도 같은 뜻이어야 한다.
         */
        val libraryEligible: Boolean = false,
        /** 콤마 복수 토큰 필드인가 — 용례·별칭 접기·허용 검증을 토큰 단위로 수행 */
        val multiToken: Boolean = false,
        /** `inputMode=restricted` — 저장 시 검증과 같은 규칙을 추천에도 적용 */
        val restrictedToLibrary: Boolean = false,
        /** 프롬프트에 실을 기존 사용값 예시 (canonical, 중복 없음) — [withLibraryUsage]가 채운다 */
        val usageExamples: List<String> = emptyList(),
        /** 라이브러리에 등재된(숨김 제외) 값 종수 — "N종 중 M개" 고지용 */
        val usageTotal: Int = 0,
        /** 변형 표기(값·별칭) → canonical 접기 표. 숨김 엔트리도 포함(저장 검증과 동일 집합) */
        val canonicalByVariant: Map<String, String> = emptyMap()
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

    /**
     * 대상 필드를 [MAX_TARGETS_PER_REQUEST] 단위로 청킹해 순차 요청한다.
     * 필드 수십 개에서도 응답이 maxTokens에 절단되지 않고(받쳐주는 확장성),
     * 파싱 실패·요청 실패는 해당 청크만 격리되어 성공분과 함께 반환된다.
     * 키·프로바이더 등 결정적 실패는 잔여 청크를 중단한다 (FieldLibraryAiOrganizer 선례).
     */
    suspend fun suggest(
        context: CharacterAiContext,
        targets: List<FieldSpec>,
        errorMessageOf: (AiResult.Failure) -> String
    ): SuggestOutcome {
        val suggestions = mutableListOf<Suggestion>()
        var dropped = 0
        val failures = mutableListOf<String>()
        val truncationNotes = mutableListOf<String>()
        var inputTokens = 0
        var outputTokens = 0

        val maxTokens = aiService.effectiveMaxTokens()
        for (chunk in chunkTargets(targets, maxTokens)) {
            val prompt = buildUserPrompt(context, chunk)
            // 청크별 targetNames 차이로 문구가 다를 수 있어 완전 중복만 접는다 (고지 과다는 무해 방향)
            prompt.truncationNotes.forEach { if (it !in truncationNotes) truncationNotes.add(it) }
            val request = AiRequest(
                system = buildSystemPrompt(),
                userText = prompt.text,
                maxTokens = maxTokens
            )
            when (val result = aiService.complete(request)) {
                is AiResult.Success -> {
                    inputTokens += result.inputTokens ?: 0
                    outputTokens += result.outputTokens ?: 0
                    val parsed = parseResponse(result.text, chunk)
                    if (parsed == null) {
                        // 잘린 응답은 형식 오류가 아니다 — 원인과 교정 경로를 정확히 말해야 한다.
                        // 종전에는 둘 다 "형식 오류 — 다시 시도해 주세요"로 떨어져, 재시도해도
                        // 결정적으로 같은 결과가 나오는 길로 사용자를 보냈다.
                        failures.add(if (result.truncated) truncatedMessage(chunk.size) else PARSE_FAILURE_MESSAGE)
                    } else {
                        suggestions.addAll(parsed.suggestions)
                        dropped += parsed.droppedCount
                        // 형식은 살아남았어도 잘렸다면 일부 제안이 빠진 것이다 — 조용히 두지 않는다.
                        if (result.truncated) failures.add(truncatedPartialMessage(chunk.size, parsed.suggestions.size))
                    }
                }
                is AiResult.Failure -> {
                    failures.add(errorMessageOf(result))
                    if (result.kind in TERMINAL_ERRORS) break
                }
            }
        }
        return SuggestOutcome(
            suggestions = suggestions,
            droppedCount = dropped,
            failures = failures,
            truncationNotes = truncationNotes,
            inputTokens = inputTokens,
            outputTokens = outputTokens
        )
    }

    companion object {
        // 컨텍스트 절단 상한 — 초과분은 truncationNotes로 반드시 고지 (R-14)
        const val MAX_MEMO_CHARS = 1500
        const val MAX_VALUE_CHARS = 300
        const val MAX_TAGS = 50
        const val MAX_RELATIONSHIPS = 30
        const val MAX_FILLED_FIELDS = 60

        // ===== 기존 사용값 예시 (표기 기조 전달) 상한 =====
        // 필드 하나당 대략 (개수 × 값 길이) 토큰이 늘어난다. 12개 × 짧은 값이면 필드당 ~50토큰,
        // 요청당 대상 15개 기준 ~750토큰 — 출력 토큰과 달리 한 번만 실리는 입력 비용이라
        // 일관성 이득 대비 감당 가능한 크기다.
        const val MAX_USAGE_EXAMPLES = 12

        /**
         * 예시 하나의 길이 상한. 이보다 긴 값은 '표기 기조'가 아니라 산문이다 —
         * 서술형으로 쓰이는 TEXT 필드(성격·배경)는 라이브러리에 문단이 통째로 등재되므로
         * 이 상한이 없으면 예시 한 줄이 프롬프트를 삼킨다.
         * 단 `restricted` 필드는 목록 자체가 계약이라 이 상한을 적용하지 않는다.
         */
        const val MAX_USAGE_EXAMPLE_VALUE_CHARS = 40

        /** 필드 하나의 예시 총 길이 상한 */
        const val MAX_USAGE_EXAMPLE_TOTAL_CHARS = 240

        /**
         * 제안 1건(key + value + 근거 한 문장)의 출력 토큰 추정치.
         * 종전 상수 `MAX_TARGETS_PER_REQUEST = 15`는 "maxTokens 4096 대비"라는 주석만 있고
         * 상한이 바뀌면 근거를 잃었다. 이제 **상한에서 역산**하며, 4096 ÷ 270 = 15로
         * 기존 동작과 정확히 일치한다(회귀 없음).
         */
        const val TOKENS_PER_SUGGESTION = 270

        /** 한 요청에 담는 대상 수의 절대 상한 — 상한을 크게 잡아도 프롬프트가 무한정 길어지지 않게. */
        const val HARD_MAX_TARGETS_PER_REQUEST = 60

        /** 요청당 추천 대상 수 — 출력 상한에서 파생된다. */
        fun targetsPerRequest(maxTokens: Int): Int =
            AiTokenPolicy.itemsPerRequest(maxTokens, TOKENS_PER_SUGGESTION, HARD_MAX_TARGETS_PER_REQUEST)

        // 재시도해도 같은 결과인 실패 — 잔여 청크 중단 기준 (FieldLibraryAiOrganizer와 동일 집합)
        private val TERMINAL_ERRORS = setOf(
            AiErrorKind.NO_PROVIDER, AiErrorKind.NO_KEY, AiErrorKind.INVALID_KEY,
            AiErrorKind.QUOTA_EXCEEDED, AiErrorKind.MODEL_NOT_FOUND
        )

        const val PARSE_FAILURE_MESSAGE = "응답 형식을 해석할 수 없습니다 — 다시 시도해 주세요"

        /** 잘려서 아무것도 못 건진 경우 — '다시 시도'는 같은 결과를 부르므로 안내하지 않는다. */
        fun truncatedMessage(targetCount: Int): String =
            "AI 응답이 출력 상한에 걸려 잘렸습니다(대상 ${targetCount}개). " +
                "한 번에 추천할 필드를 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        /** 잘렸지만 일부는 건진 경우 — 몇 개가 빠졌는지 수로 알린다(R-14). */
        fun truncatedPartialMessage(targetCount: Int, received: Int): String =
            "AI 응답이 출력 상한에 걸려 잘렸습니다 — 대상 ${targetCount}개 중 ${received}개만 받았습니다. " +
                "한 번에 추천할 필드를 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        /** 개수 기준 단일 분할 — 비용 고지의 요청 수 계산과 반드시 일치해야 한다 (사전 고지 정확성) */
        fun chunkTargets(
            targets: List<FieldSpec>,
            maxTokens: Int = AiTokenPolicy.DEFAULT_REQUEST
        ): List<List<FieldSpec>> = targets.chunked(targetsPerRequest(maxTokens))

        /** [chunkTargets]와 같은 규칙의 요청 수 — 비용 고지용 */
        fun requestCountFor(
            targetCount: Int,
            maxTokens: Int = AiTokenPolicy.DEFAULT_REQUEST
        ): Int {
            if (targetCount <= 0) return 0
            val per = targetsPerRequest(maxTokens)
            return (targetCount + per - 1) / per
        }

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
            // 구조화 입력은 폼이 실제로 파트 위젯을 렌더하는 타입(TEXT/BODY_SIZE)에만 유효 —
            // 그 외 타입(NUMBER 등)의 config 잔존 structuredInput은 힌트·검증 모두 무시한다
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
            val structuredActive = !isBirth && type != FieldType.MULTI_TEXT &&
                (type == FieldType.TEXT || type == FieldType.BODY_SIZE) &&
                structured.enabled && structured.parts.isNotEmpty()
            val formatHint: String? = when {
                isBirth -> "MM-DD (월-일, 예: 03-15)"
                type == FieldType.MULTI_TEXT -> "콤마로 구분한 복수 값 (예: 값1, 값2)"
                structuredActive ->
                    structured.parts.joinToString(structured.separator) { it.label } +
                        " 형식 (구분자 '" + structured.separator + "')"
                else -> null
            }
            // 값 라이브러리 연동 판정은 라이브러리 자신의 단일 소스를 그대로 쓴다 —
            // 여기서 타입 목록을 다시 적으면 라이브러리 대상이 바뀔 때 AI 경로만 어긋난다.
            val libraryConfig = FieldValueLibraryConfig.fromConfig(field.config)
            val libraryEligible = FieldValueTokenizer.supportsLibrary(field) && libraryConfig.isSuggestEnabled
            return FieldSpec(
                key = field.key,
                name = field.name,
                type = type,
                options = options,
                isBirthDate = isBirth,
                numberRange = numberRange,
                currentValue = currentValue,
                formatHint = formatHint,
                structuredSeparator = if (structuredActive) structured.separator else null,
                structuredPartCount = if (structuredActive) structured.parts.size else null,
                fieldId = field.id,
                libraryEligible = libraryEligible,
                multiToken = FieldValueTokenizer.isMultiToken(field),
                restrictedToLibrary = libraryEligible && libraryConfig.isRestricted
            )
        }

        /**
         * 라이브러리 엔트리를 스펙에 싣는다 (순수 — DB 조회는 호출측 VM이 1쿼리로 배치 수행).
         *
         * - 예시는 **숨김 제외**(숨김의 계약이 "입력 제안에서 제외"이고 AI 추천도 입력 제안이다)
         * - 접기 표는 **숨김 포함**(숨김 값도 저장 가능한 값이라 restricted 검증 집합과 같아야 한다)
         * - 별칭보다 canonical이 우선한다(다른 엔트리의 별칭이 이 값과 겹쳐도 자기 자신으로 접힌다)
         *
         * [exampleLimit]은 사용자 설정([AiPromptSettings.usageExampleCount])이다. **0이어도
         * 접기 표는 그대로 만든다** — 설정이 줄이는 것은 프롬프트 적재량(토큰)이지, 별칭 교정과
         * restricted 검증(정확성)이 아니다. 같은 이유로 restricted 필드는 허용 목록이 곧 계약이라
         * 설정이 0이어도 기본 개수만큼은 싣는다(목록을 안 주고 목록 밖이라 드롭할 수는 없다).
         */
        fun withLibraryUsage(
            spec: FieldSpec,
            entries: List<FieldValueEntry>,
            exampleLimit: Int = MAX_USAGE_EXAMPLES
        ): FieldSpec {
            if (!spec.libraryEligible || entries.isEmpty()) return spec
            val canonical = HashMap<String, String>(entries.size * 2)
            for (e in entries) {
                for (alias in e.aliases()) {
                    val a = alias.trim()
                    if (a.isNotEmpty()) canonical.putIfAbsent(a, e.value)
                }
            }
            for (e in entries) canonical[e.value] = e.value  // canonical이 별칭을 이긴다
            val visible = entries.filterNot { it.isHidden }.filter { it.value.isNotBlank() }
            val limit =
                if (spec.restrictedToLibrary) exampleLimit.coerceAtLeast(MAX_USAGE_EXAMPLES) else exampleLimit
            return spec.copy(
                usageExamples = selectUsageExamples(
                    visible,
                    limit = limit,
                    maxValueChars = if (spec.restrictedToLibrary) Int.MAX_VALUE else MAX_USAGE_EXAMPLE_VALUE_CHARS
                ),
                usageTotal = visible.size,
                canonicalByVariant = canonical
            )
        }

        /**
         * 예시 선별 — "중복 없이 몇 개"의 구체 규칙.
         *
         * 최다 사용값만 상위 N개 자르면 그 필드의 **폭**이 안 보여서, 모델이 1위 값만 되풀이하거나
         * 반대로 롱테일의 표기 관례를 못 배운다. 그래서 앞 2/3은 빈도 상위(주류 기조),
         * 나머지 1/3은 잔여 목록을 **균등 간격으로 훑어**(분포의 폭) 채운다.
         * 난수를 쓰지 않으므로 같은 데이터면 항상 같은 예시가 나가고 단위 테스트가 가능하다.
         *
         * 정렬은 usageCount 내림차순 → 값 오름차순. usageCount는 최종 일관성 캐시라
         * 라이브러리 화면에 한 번도 안 들어간 사용자는 **전부 0**일 수 있다. 그때 앞 2/3을
         * 그대로 자르면 '가나다순 앞쪽'만 뽑히는 편향이 되므로, 전부 0이면 빈도 구간을 두지 않고
         * 목록 전체를 균등 간격으로 훑는다 (여기서 재계산하지 않는 이유: 재계산은 필드마다
         * 값 전량 스캔+쓰기라, 읽기 경로인 추천이 짊어질 비용이 아니다 — docs/field_value_library.md).
         */
        fun selectUsageExamples(
            entries: List<FieldValueEntry>,
            limit: Int = MAX_USAGE_EXAMPLES,
            maxValueChars: Int = MAX_USAGE_EXAMPLE_VALUE_CHARS,
            maxTotalChars: Int = MAX_USAGE_EXAMPLE_TOTAL_CHARS
        ): List<String> {
            if (limit <= 0) return emptyList()
            val usable = entries.filter { it.value.isNotBlank() && it.value.length <= maxValueChars }
            val sorted = usable.asSequence()
                .sortedWith(compareByDescending<FieldValueEntry> { it.usageCount }.thenBy { it.value })
                .map { it.value }
                .distinct()
                .toList()
            val picked = if (sorted.size <= limit) {
                sorted
            } else {
                // 빈도 정보가 전혀 없으면 상위 구간이라는 개념 자체가 성립하지 않는다 → 전량 균등 훑기
                val headCount = if (usable.none { it.usageCount > 0 }) 0 else (limit * 2 + 2) / 3
                val tailCount = limit - headCount
                val rest = sorted.drop(headCount)
                // rest.size > tailCount 이 보장되므로 간격 ≥ 1 — 같은 값이 두 번 뽑히지 않는다
                val step = rest.size.toDouble() / tailCount
                sorted.take(headCount) +
                    (0 until tailCount).map { rest[(it * step).toInt().coerceAtMost(rest.lastIndex)] }
            }
            // 총 길이 상한 — 넘치는 뒤쪽(덜 대표적인 쪽)부터 자른다. 최소 1개는 남긴다.
            val out = mutableListOf<String>()
            var chars = 0
            for (value in picked) {
                if (out.isNotEmpty() && chars + value.length + 2 > maxTotalChars) break
                out.add(value)
                chars += value.length + 2
            }
            return out
        }

        /**
         * 별칭 표기 → canonical 접기. 라이브러리가 "변형 표기 → 정규값"으로 정의한 매핑을
         * 그대로 쓴다 — 모델이 '흑발'이라 답해도 이 작품의 정규값이 '검은 머리'면 그쪽으로 넣어야
         * 통계·검색·restricted 검증이 갈라지지 않는다. 미등록 표기는 손대지 않는다(새 값 허용).
         */
        fun foldToLibrary(raw: String, spec: FieldSpec): String {
            if (spec.canonicalByVariant.isEmpty()) return raw
            if (!spec.multiToken) return spec.canonicalByVariant[raw.trim()] ?: raw
            val tokens = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return raw
            // 서로 다른 별칭이 같은 canonical로 접히면 중복이 생긴다 — 접은 뒤 중복 제거
            return FieldValueTokenizer.join(tokens.map { spec.canonicalByVariant[it] ?: it }.distinct())
        }

        /**
         * restricted 필드의 허용 검증 — 저장 시 가드(FieldValueRules.validateRestricted)와 같은 집합.
         * 라이브러리가 **비어 있으면 제한하지 않는다**: 허용 목록을 준 적이 없는데 목록 밖이라고
         * 드롭하면 유료 응답을 통째로 버리는 셈이고, 저장 가드도 그 경우 '추가하고 저장'을 연다.
         */
        fun isAllowedByLibrary(value: String, spec: FieldSpec): Boolean {
            if (!spec.restrictedToLibrary || spec.canonicalByVariant.isEmpty()) return true
            val tokens = if (spec.multiToken) {
                value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf(value.trim())
            }
            return tokens.isNotEmpty() && tokens.all { it in spec.canonicalByVariant }
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
            7. '기존 사용값'이 제시된 필드는 그 값들이 이 작품에서 실제로 쓰이는 표기 기조다.
               같은 뜻이면 새 표기를 만들지 말고 기존 값을 그대로 쓴다.
               기존 값으로 표현할 수 없어 새 값이 필요할 때만 새로 만들되, 기존 값들의 표기 방식·
               상세도·길이를 따른다 (예: 기존이 '흑발, 은발'이면 '짙은 밤하늘빛 흑청색'은 안 된다).
            8. '이 목록의 값만 허용'이 붙은 필드는 제시된 기존 사용값 중에서만 고른다.
        """.trimIndent()

        data class PromptBuild(val text: String, val truncationNotes: List<String>)

        fun buildUserPrompt(context: CharacterAiContext, targets: List<FieldSpec>): PromptBuild {
            val notes = mutableListOf<String>()
            // 조회 실패로 빠진 섹션 — 절단과 같은 경로로 반드시 고지 (조용한 결손 금지, R-14)
            context.loadFailures.forEach { notes.add("$it 정보를 불러오지 못함") }

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
                // 표기 기조 — 이 작품이 이 필드를 실제로 어떻게 써 왔는지. 전량이 아니라 선별분이므로
                // 몇 종 중 몇 개인지 함께 적어 모델이 "이게 전부"라고 오해하지 않게 한다.
                if (t.usageExamples.isNotEmpty()) {
                    sb.append(" / 기존 사용값")
                    if (t.usageExamples.size < t.usageTotal) {
                        sb.append("(총 ").append(t.usageTotal).append("종 중 ")
                            .append(t.usageExamples.size).append("개 예시)")
                    }
                    sb.append(": ").append(t.usageExamples.joinToString(", "))
                    if (t.restrictedToLibrary) sb.append(" (이 목록의 값만 허용)")
                }
                // restricted 필드의 허용 목록을 다 싣지 못했으면 조용히 두지 않는다 —
                // 목록 밖 제안은 드롭되므로 사용자가 결손을 알아야 한다 (R-14).
                if (t.restrictedToLibrary && t.usageExamples.size < t.usageTotal) {
                    notes.add(
                        "'${t.name}'의 허용 값 ${t.usageTotal}종 중 ${t.usageExamples.size}개만 예시로 전달 " +
                            "(목록 밖 제안은 제외됨)"
                    )
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

        /**
         * 타입별 값 정규화 — 통과 못 하면 null(드롭).
         *
         * 라이브러리 접기를 **타입 검증보다 먼저** 한다: SELECT 옵션 '검은 머리'에 대해 모델이
         * 별칭 '흑발'을 답한 경우, 접고 나서 옳은 옵션으로 통과시키는 편이 드롭보다 낫다.
         * 접은 뒤 restricted 허용 검증을 마지막에 적용한다(접힌 canonical 기준으로 판정).
         */
        fun normalizeValue(raw: String, spec: FieldSpec): String? {
            val folded = foldToLibrary(raw, spec)
            val typed = when {
                spec.isBirthDate -> normalizeBirthDate(folded)
                spec.type == FieldType.SELECT || spec.type == FieldType.GRADE ->
                    spec.options.firstOrNull { it == folded }
                spec.type == FieldType.NUMBER -> normalizeNumber(folded)
                spec.structuredPartCount != null -> normalizeStructured(folded, spec)
                else -> folded
            } ?: return null
            return if (isAllowedByLibrary(typed, spec)) typed else null
        }

        /**
         * 구조화 입력 검증 — 파트 수만큼 구분자로 나뉘고 전 파트가 비어 있지 않아야 통과.
         * 형식 위반 값이 첫 파트에 통째로 들어가 "값--" 꼴로 저장되는 것을 막는다 (KDoc 계약).
         * 분리 규칙은 위젯 쪽(StructuredInputConfig.splitValue)과 동일: 빈 구분자 "-" 폴백, 파트 trim.
         */
        fun normalizeStructured(raw: String, spec: FieldSpec): String? {
            val count = spec.structuredPartCount ?: return raw
            val sep = (spec.structuredSeparator ?: "-").ifEmpty { "-" }
            val parts = raw.trim().split(sep, limit = count).map { it.trim() }
            if (parts.size != count || parts.any { it.isEmpty() }) return null
            return parts.joinToString(sep)
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
