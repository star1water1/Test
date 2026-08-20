package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NarrativeMode
import com.novelcharacter.app.util.DuelAiContext

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
        val outputTokens: Int,
        /**
         * 재시도해도 같은 결과인 실패인가 ([AiErrorPolicy.TERMINAL]) — **일괄 초안이
         * 남은 필드를 보낼지 말지의 기준이다**(B-45). 키가 틀렸거나 한도를 넘긴 상태에서
         * 남은 필드를 계속 보내면 같은 실패를 필드 수만큼 결제한다.
         *
         * 필드 하나짜리 경로에는 '남은 것'이 없어 쓰이지 않지만, **판정을 여기 두는 것이
         * 요점이다** — 호출측이 오류 문구를 문자열로 견주기 시작하면 그 판정이 문구를
         * 바꿀 때마다 조용히 틀린다.
         */
        val terminalFailure: Boolean = false
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
        /** 창작도 (A-4) — 창작 폭이 가장 크게 체감되는 자리. 기본은 무회귀(균형). */
        creativity: AiCreativity = AiCreativity.DEFAULT,
        /**
         * 함께 보낼 캐릭터 이미지 (A-7). **서술형이 이 기능의 최대 수혜자다** — 외모 묘사는
         * 글로 적힌 정보가 가장 성긴 자리이고 그림에는 그것이 통째로 들어 있다.
         * 짧은 값 경로와 달리 요청이 하나뿐이라 이미지도 한 번만 나간다.
         */
        images: List<AiImage> = emptyList(),
        /** 사용자가 고친 양식. 넘기지 않으면 기본 양식이다 (사용자 요청 2026.08.20). */
        templates: PromptTemplates.Source = PromptTemplates.Source.DEFAULTS,
        errorMessageOf: (AiResult.Failure) -> String
    ): WriteOutcome {
        val wanted = variants.coerceIn(1, MAX_VARIANTS)
        val prompt = buildUserPrompt(
            context, field, mode, length, wanted,
            templates.templateOf(PromptTemplates.Id.NARRATIVE_USER)
        )
        // 학습된 미지원 모델 — 샘플링 없이 지시만 적용된다는 사실을 고지한다 (§6-5 ④)
        val temperatureNote =
            creativity != AiCreativity.BALANCED && aiService.isTemperatureUnsupported()
        // 이미지도 같다 (A-7) — 이미 거부를 배운 모델이면 붙였어도 나가지 않는다는 사실을 말한다
        val imagesNote = images.isNotEmpty() && aiService.isImagesUnsupported()
        val request = AiRequest(
            system = buildSystemPrompt(
                creativity, templates.templateOf(PromptTemplates.Id.NARRATIVE_SYSTEM)
            ),
            userText = prompt.text,
            maxTokens = aiService.effectiveMaxTokens(),
            temperature = aiService.temperatureFor(creativity),
            images = images,
            // 이미지 절은 이미지와 한 몸으로 간다 (B-139) — 짧은 값 경로와 같다.
            imageSystemRule = imageRule(images.size)
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
                        if (temperatureNote || result.temperatureOmitted) {
                            add(CharacterFieldAiSuggester.TEMPERATURE_UNSUPPORTED_NOTE)
                        }
                        if (imagesNote || result.imagesOmitted) {
                            add(CharacterFieldAiSuggester.IMAGES_UNSUPPORTED_NOTE)
                        }
                        // 한도로 밀려 다른 프로바이더가 이어 쓴 초안 (B-108 확정 ⓑ) —
                        // 서술형은 문체가 곧 결과물이라 누가 썼는지가 특히 중요하다.
                        AiProviderFallback.switchNoteOf(result)?.let { add(it) }
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
                failures = buildList {
                    add(errorMessageOf(result))
                    if (temperatureNote) add(CharacterFieldAiSuggester.TEMPERATURE_UNSUPPORTED_NOTE)
                    if (imagesNote) add(CharacterFieldAiSuggester.IMAGES_UNSUPPORTED_NOTE)
                },
                truncationNotes = prompt.truncationNotes,
                truncated = false,
                inputTokens = 0,
                outputTokens = 0,
                terminalFailure = AiErrorPolicy.isTerminal(result.kind)
            )
        }
    }

    /** 대상 필드 스펙 — 서술형은 옵션·형식 제약이 없으므로 짧은 값 스펙보다 단순하다. */
    data class FieldSpec(
        val key: String,
        val name: String,
        val groupName: String,
        /** 현재 입력값. [Mode.requiresExisting] 모드는 이것이 비면 성립하지 않는다. */
        val currentValue: String,
        /**
         * 사용자가 쓴 필드 설명(A-2) — 세 AI 경로가 같은 설명을 봐야 같은 필드가 경로마다
         * 다른 정의로 해석되지 않는다. 프롬프트 적재량 설정의 대상이 아니다(계약이지 힌트가 아니다).
         */
        val description: String = "",
        /**
         * 같은 필드에 **다른 캐릭터가 이미 쓴 글**(문체 참고). [selectStyleSamples]가 고르고
         * 호출측 VM이 DB에서 싣는다. 짧은 값 추천의 '기존 사용값'에 대응하는 서술형판이다 —
         * 값 목록으로는 문체(어투·시점·문장 길이·상세도)를 전할 수 없기 때문에 글로 전한다.
         */
        val styleSamples: List<String> = emptyList()
    )

    data class ParsedDrafts(val drafts: List<Draft>, val droppedCount: Int)
    data class PromptBuild(val text: String, val truncationNotes: List<String>)

    // ===== 일괄 초안 (B-45) =====

    /** 일괄 초안 대상 1건 — '이미 쓴 필드' 판정에 폼 값이 필요하므로 fieldId를 함께 나른다. */
    data class BulkDraftTarget(val fieldId: Long, val spec: FieldSpec)

    /**
     * 일괄 초안에서 빠진 사유 — 교정 경로가 다르므로 하나로 뭉뚱그리지 않는다.
     * [CharacterFieldAiSuggester.BulkExcludeCause]와 **같은 축이지만 집합이 다르다**:
     * 그쪽의 `NARRATIVE_PATH`가 여기서는 대상 그 자체이고, 대신 여기에만 [ALREADY_WRITTEN]이 있다.
     */
    enum class BulkDraftExcludeCause(val label: String) {
        /** 사용자가 필드 관리에서 AI 추천을 끔 */
        AI_DISABLED("AI 추천 꺼짐"),

        /** 개별만 받기로 둠 — 일괄에서만 빠지고 필드별 ✨은 그대로다(B-80과 같은 규약) */
        MANUAL_ONLY("개별 추천만"),

        /**
         * 이미 글이 있다 — **초안은 빈 칸을 채우는 모드다.**
         * 쓰던 글을 다루는 것(이어쓰기·다듬기·늘리기·줄이기)은 필드별 ✨의 몫이라,
         * 여기서 덮어쓸 후보를 들이밀지 않는다.
         */
        ALREADY_WRITTEN("이미 작성됨")
    }

    /**
     * 일괄 초안 대상 산출 결과. [targets] + [excluded]의 합은 **서술형 필드 전체**다 —
     * 서술형이 아닌 필드는 애초에 이 경로의 관심사가 아니라 어느 쪽에도 담기지 않는다
     * (담으면 "제외 N개"가 짧은 값 필드까지 세어 고지가 거짓이 된다).
     */
    data class BulkDraftTargets(
        val targets: List<BulkDraftTarget>,
        val excluded: Map<BulkDraftExcludeCause, List<String>>
    ) {
        val excludedCount: Int get() = excluded.values.sumOf { it.size }
    }

    companion object {
        const val DEFAULT_VARIANTS = 2
        const val MAX_VARIANTS = 3

        /** 원문 절단 상한 — 짧은 값 경로의 300자로는 성격 묘사를 근거로 쓸 수 없다. */
        const val MAX_EXISTING_CHARS = 4000

        /** 컨텍스트의 다른 필드 값 절단 상한(서술형 전용 — 짧은 값 경로보다 넉넉히). */
        const val MAX_CONTEXT_VALUE_CHARS = 800

        // ===== 문체 참고 =====

        /** 참고 1편의 길이 상한. 문체를 보이는 데 필요한 만큼만 — 전문을 실을 이유가 없다. */
        const val STYLE_SAMPLE_CHARS = 600

        /**
         * 참고로 쓸 최소 길이. 이보다 짧은 값은 문체가 아니라 메모 조각이라
         * "짧게 쓰는 게 이 작품의 기조"라는 잘못된 신호를 준다.
         */
        const val STYLE_SAMPLE_MIN_CHARS = 40

        const val PARSE_FAILURE_MESSAGE = "응답 형식을 해석할 수 없습니다 — 다시 시도해 주세요"

        const val TRUNCATED_EMPTY_MESSAGE =
            "AI 응답이 출력 상한에 걸려 잘려 초안을 만들지 못했습니다. " +
                "분량을 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        const val TRUNCATED_PARTIAL_MESSAGE =
            "AI 응답이 출력 상한에 걸려 잘렸습니다 — 마지막 초안이 문장 중간에서 끊겼을 수 있습니다. " +
                "분량을 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        /**
         * 문체 참고 선별 — 같은 필드에 **다른 캐릭터가 쓴 글** 중에서 고른다 (순수, DB 비의존).
         *
         * 왜 중앙값 길이 기준인가: 문체 참고의 목적은 "이 작품이 이 필드를 어느 정도 밀도로
         * 쓰는가"를 보이는 것이다. 가장 긴 글을 고르면 모델이 그만큼 쓰려 하고(과금·분량 폭주),
         * 가장 짧은 글을 고르면 반대로 성의 없는 결과가 나온다. **길이 중앙값에 가장 가까운
         * 글**이 그 필드의 습관을 가장 잘 대표한다. 동률은 값 문자열로 갈라 결정적으로 만든다
         * (난수 없음 — 같은 데이터면 같은 참고가 나가고 단위 테스트가 된다).
         *
         * 너무 짧은 값은 애초에 후보에서 뺀다([STYLE_SAMPLE_MIN_CHARS]) — 메모 조각을 문체로
         * 제시하면 "이 작품은 한 줄만 쓴다"는 거짓 신호가 된다.
         */
        fun selectStyleSamples(
            values: List<String>,
            limit: Int,
            sampleChars: Int = STYLE_SAMPLE_CHARS
        ): List<String> {
            if (limit <= 0) return emptyList()
            val pool = values.asSequence()
                .map { it.trim() }
                .filter { it.length >= STYLE_SAMPLE_MIN_CHARS }
                .distinct()
                .toList()
            if (pool.isEmpty()) return emptyList()
            val sortedLengths = pool.map { it.length }.sorted()
            val median = sortedLengths[sortedLengths.size / 2]
            return pool
                .sortedWith(compareBy<String> { kotlin.math.abs(it.length - median) }.thenBy { it })
                .take(limit)
                .map { if (it.length > sampleChars) it.take(sampleChars) + "…" else it }
        }

        fun fieldSpecOf(field: FieldDefinition, currentValue: String) = FieldSpec(
            key = field.key,
            name = field.name,
            groupName = field.groupName,
            currentValue = currentValue,
            description = com.novelcharacter.app.data.model.FieldDescription.fromConfig(field.config)
        )

        /** 이 필드·현재값에서 실제로 고를 수 있는 모드. 원문이 없으면 초안만 가능하다. */
        fun availableModes(currentValue: String): List<Mode> =
            if (currentValue.isBlank()) listOf(Mode.DRAFT)
            else Mode.entries.filter { it.requiresExisting }

        // ===== 일괄 초안 — 대상 규칙과 비용 (B-45) =====

        /**
         * **일괄 초안 대상 규칙의 단일 소스.**
         *
         * 짧은 값 일괄([CharacterFieldAiSuggester.bulkTargetsOf])이 `NARRATIVE_PATH`로 빼던
         * 그 필드들이 여기서는 대상이다 — **그쪽의 제외는 옳고**(산문을 "근거 한 문장" 틀에
         * 끼우면 한 줄짜리 값이 나온다), 빠진 뒤 갈 곳이 없던 것이 결함이었다.
         *
         * 서술형이 아닌 필드는 [excluded]에도 담지 않는다 — 이 경로의 관심사가 아니다.
         * 담으면 "제외 N개" 고지가 짧은 값 필드까지 세어 거짓이 된다.
         */
        fun bulkDraftTargetsOf(
            fields: List<FieldDefinition>,
            currentValues: Map<Long, String>
        ): BulkDraftTargets {
            val targets = mutableListOf<BulkDraftTarget>()
            val excluded = LinkedHashMap<BulkDraftExcludeCause, MutableList<String>>()
            fun exclude(cause: BulkDraftExcludeCause, name: String) {
                excluded.getOrPut(cause) { mutableListOf() }.add(name)
            }
            for (field in fields) {
                if (!NarrativeMode.isNarrative(field)) continue
                val current = currentValues[field.id].orEmpty()
                when {
                    FieldAiPolicy.suggestMode(field.config) == FieldAiPolicy.SuggestMode.OFF ->
                        exclude(BulkDraftExcludeCause.AI_DISABLED, field.name)
                    !FieldAiPolicy.isBulkTarget(field.config) ->
                        exclude(BulkDraftExcludeCause.MANUAL_ONLY, field.name)
                    current.isNotBlank() ->
                        exclude(BulkDraftExcludeCause.ALREADY_WRITTEN, field.name)
                    else -> targets.add(BulkDraftTarget(field.id, fieldSpecOf(field, current)))
                }
            }
            return BulkDraftTargets(targets, excluded)
        }

        /** 제외 요약 한 줄 — "이미 작성됨 2개 · AI 추천 꺼짐 1개". 제외가 없으면 null */
        fun bulkDraftExcludedSummary(excluded: Map<BulkDraftExcludeCause, List<String>>): String? {
            val parts = BulkDraftExcludeCause.entries.mapNotNull { cause ->
                excluded[cause]?.takeIf { it.isNotEmpty() }?.let { "${cause.label} ${it.size}개" }
            }
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

        /**
         * 제외 상세 — 사유별 필드명 나열. **교정 경로를 병기한다**(변수 제어) — 제외가
         * 기능 부재로 읽히면 사용자는 그 필드를 영영 손대지 못한다고 여긴다.
         * 나열 상한은 짧은 값 경로와 같은 값을 쓴다(두 고지가 같은 화면에서 이어 뜬다).
         */
        fun bulkDraftExcludedDetailLines(
            excluded: Map<BulkDraftExcludeCause, List<String>>
        ): List<String> = BulkDraftExcludeCause.entries.mapNotNull { cause ->
            val names = excluded[cause]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val shown = names.take(CharacterFieldAiSuggester.MAX_EXCLUDED_NAMES_PER_CAUSE)
            buildString {
                append(cause.label).append(' ').append(names.size).append("개: ")
                append(shown.joinToString(", "))
                if (names.size > shown.size) append(" 외 ").append(names.size - shown.size).append("개")
                when (cause) {
                    BulkDraftExcludeCause.ALREADY_WRITTEN ->
                        append(" — 필드별 ✨에서 이어쓰기·다듬기로 고칩니다")
                    BulkDraftExcludeCause.MANUAL_ONLY ->
                        append(" — 필드별 ✨을 누르면 이 필드도 초안을 받습니다")
                    BulkDraftExcludeCause.AI_DISABLED ->
                        append(" — 필드 관리에서 AI 추천을 켜면 대상이 됩니다")
                }
            }
        }

        /**
         * **일괄 초안의 요청 수 — 필드 하나에 요청 하나다.**
         *
         * 짧은 값 일괄([CharacterFieldAiSuggester.requestCountFor])은 여러 필드를 한 요청에
         * 묶어 *필드 15개 = 요청 1건*이 되지만, 서술형은 필드 하나가 출력 예산을 통째로 쓰므로
         * 묶을 수 없다. **자릿수가 다르고, 같은 문구로 고지하면 사용자가 비용을 오해한다**
         * (B-45 백로그 행이 착수 조건으로 건 것이 이것이다).
         *
         * 함수가 하는 일이 항등에 가깝지만 **단일 소스로 세운다** — 고지와 실행이 각자 세면
         * 한쪽만 고쳐졌을 때 *고지된 요청 수와 실제 과금이 어긋난다*(R-4).
         */
        fun draftRequestCount(targetCount: Int): Int = targetCount.coerceAtLeast(0)

        /**
         * 일괄 초안의 필드당 후보 수 — **1개다.**
         *
         * 필드별 ✨은 [DEFAULT_VARIANTS]개(2)를 주는데 여기만 1인 것은 비용이 아니라
         * **검토 부담** 때문이다. 후보는 한 요청 안에서 나오므로 2개로 늘려도 요청 수는
         * 그대로다 — 대신 필드 5개면 사용자가 고를 것이 10개가 되고, 그 시점에 일괄이
         * *"한 번에 깔아 두는 것"*이기를 그친다.
         *
         * 이것이 원칙 04의 **이중 경로**다: 일괄은 러프하게 깔고(후보 1개), 마음에 안 드는
         * 필드만 필드별 ✨으로 정밀 조정한다(후보 2개 + 이어쓰기·다듬기). 후보를 더 보고
         * 싶은 사용자는 그 경로가 이미 있으므로 **여기서 막는 것이 아니다.**
         *
         * 곁들여: 후보가 하나면 그 하나가 출력 예산을 통째로 쓰므로 **절단 확률도 가장 낮다.**
         */
        const val BULK_DRAFT_VARIANTS = 1

        /**
         * 초안 1편의 출력 토큰 추정치 — 분량 지시에서 역산한다.
         *
         * 근거는 짧은 값 경로의 [CharacterFieldAiSuggester.TOKENS_PER_SUGGESTION](=270,
         * *키+값+근거 한 문장*)이다. 거기서 키·값·JSON 껍데기를 덜면 **한국어 한 문장 ≈ 80토큰**이
         * 남고, 그 값에 분량 지시의 문장 수를 곱했다. 상수를 새로 지어내지 않고 이미 이 저장소가
         * 쓰고 있는 수에 맞춘 것은, **두 경로의 추정이 어긋나면 어느 쪽이 틀렸는지 알 수 없기**
         * 때문이다. JSON 껍데기 몫으로 각 단계에 여유를 얹었다.
         *
         * 정확한 값일 필요는 없다 — 이 수가 하는 일은 [draftFitsBudget] 하나이고,
         * 그것은 *"이 설정으로 돌리면 잘릴 수 있다"*를 **미리** 말하기 위한 것이다.
         */
        fun tokensPerDraft(length: Length): Int = when (length) {
            Length.SHORT -> 260    // 2~3문장
            Length.MEDIUM -> 560   // 한 문단(4~6문장)
            Length.LONG -> 1300    // 두세 문단
        }

        /**
         * 이 출력 상한으로 후보 [variants]개를 요청해도 잘리지 않겠는가.
         *
         * `false`면 **막지 않고 고지한다** — 추정이지 확정이 아니고, 상한을 낮게 두고도
         * 짧게 받으면 그만인 사용자가 있다(자율성 우선). 다만 잘린 뒤에 알리면 이미
         * 결제가 끝났으므로 **실행 전에** 말한다.
         */
        fun draftFitsBudget(
            maxTokens: Int,
            length: Length,
            variants: Int = BULK_DRAFT_VARIANTS
        ): Boolean = maxTokens >= tokensPerDraft(length) * variants.coerceAtLeast(1)

        fun buildSystemPrompt(
            creativity: AiCreativity = AiCreativity.DEFAULT,
            template: String = PromptTemplates.default(PromptTemplates.Id.NARRATIVE_SYSTEM)
        ): String = PromptTokens.expand(
            template,
            mapOf(
                PromptTemplates.T_RESPONSE to
                    PromptTemplates.responseFormat(PromptTemplates.Id.NARRATIVE_SYSTEM),
                PromptTemplates.T_CREATIVITY_RULE to creativity.promptBlock()
            )
        )

        /**
         * 이미지 첨부 지시 (A-7). 짧은 값 경로와 **다른 점 하나**: 산문에는 "몇 번째
         * 이미지"라고 적을 자리가 없다 — 그것은 작품 설정이 아니라 작업 기록이라
         * 대결 등수를 숫자로 적지 말라는 규칙 8과 같은 이유로 금지한다.
         *
         * 짧은 값 경로와 **같은 점 하나**: 이 절은 [buildSystemPrompt]가 아니라
         * [AiRequest.imageSystemRule]로 간다 (B-139 — 이미지가 빠지면 지시도 함께 빠져야 한다).
         */
        fun imageRule(imageCount: Int): String =
            if (imageCount <= 0) "" else "\n" + """
            [이미지] 이 요청에는 캐릭터의 이미지 ${imageCount}장이 순서대로 함께 실려 있다.
            글로 적힌 정보와 어긋나지 않는 선에서 그림에서 읽을 수 있는 것(외모·복장·분위기·
            소지품 등)을 묘사의 근거로 삼아라. 그림에 없는 것을 있는 것처럼 쓰지 마라.
            다만 **'이미지 1'처럼 그림을 가리키는 말을 본문에 쓰지 마라** — 본문은 그 필드에
            그대로 들어갈 작품 설정이지 작업 기록이 아니다.
            """.trimIndent()

        fun buildUserPrompt(
            context: CharacterFieldAiSuggester.CharacterAiContext,
            field: FieldSpec,
            mode: Mode,
            length: Length,
            variants: Int,
            template: String = PromptTemplates.default(PromptTemplates.Id.NARRATIVE_USER)
        ): PromptBuild {
            val notes = mutableListOf<String>()
            context.loadFailures.forEach { notes.add("$it 정보를 불러오지 못함") }

            // **사용자가 양식에서 뺀 재료는 만들지 않는다** (R-14 — 짧은 값 경로와 같은 근거).
            val used = PromptTokens.usedNames(template)

            // 대결 우열 — 짧은 값 경로와 **같은 규칙으로** 자르고 같은 문구로 고지한다
            // (com.novelcharacter.app.util.DuelAiContext가 단일 소스). 각자 자르면 같은 캐릭터가
            // 서술형과 짧은 값에서 서로 다른 축을 받는다.
            val duelText = if ("대결우열" !in used || context.duelStandings.isEmpty()) "" else {
                val duel = DuelAiContext.promptLines(context.duelStandings)
                if (duel.omitted > 0) notes.add(DuelAiContext.omittedNote(duel.omitted))
                duel.lines.joinToString(DuelAiContext.PROMPT_SEPARATOR)
            }

            // 대상 필드 자신은 컨텍스트에서 제외한다 — 원문은 [원문] 절에 따로 싣는다.
            val others =
                if ("다른필드표" !in used) emptyList()
                else context.filledFields.filter { it.first != field.name }
            val othersText = others.joinToString("\n") { (name, value) ->
                val capped = if (value.length > MAX_CONTEXT_VALUE_CHARS) {
                    notes.add("'$name' 값이 길어 ${MAX_CONTEXT_VALUE_CHARS}자까지만 참고했습니다")
                    value.take(MAX_CONTEXT_VALUE_CHARS)
                } else value
                "- $name: $capped"
            }

            // 필드 설명(A-2) — 값의 계약. 짧은 값 경로와 같은 프롬프트 상한을 쓴다.
            val descCap = CharacterFieldAiSuggester.MAX_DESCRIPTION_PROMPT_CHARS
            val descText = (if ("필드설명" in used) field.description.trim() else "").let { desc ->
                when {
                    desc.isEmpty() -> ""
                    desc.length > descCap -> {
                        notes.add("'${field.name}' 필드 설명 ${descCap}자 초과분 생략")
                        desc.take(descCap) + "…"
                    }
                    else -> desc
                }
            }

            val existingText = if ("원문" !in used || !mode.requiresExisting) "" else {
                if (field.currentValue.length > MAX_EXISTING_CHARS) {
                    notes.add("원문이 길어 ${MAX_EXISTING_CHARS}자까지만 참고했습니다")
                    field.currentValue.take(MAX_EXISTING_CHARS)
                } else field.currentValue
            }

            // 문체 참고 — 짧은 값 추천의 '기존 사용값'에 대응하는 서술형판.
            // 기본 양식에서 [원문] 다음에 두어 "이 인물의 글 > 작품의 문체" 순으로
            // 우선순위가 드러나게 한다(차례는 사용자가 바꿀 수 있다).
            val styleText = field.styleSamples
                .mapIndexed { i, sample -> "${i + 1}) $sample" }
                .joinToString("\n")

            // 자리가 없어 안 실린 재료 (R-14의 뒷면 — 짧은 값 경로와 같은 근거).
            notes.addAll(
                PromptTemplates.unusedMaterialNotes(
                    used,
                    mapOf(
                        "이명목록" to context.aliases.isNotEmpty(),
                        "태그목록" to context.tags.isNotEmpty(),
                        "소속세력" to context.factions.isNotEmpty(),
                        "관계요약" to context.relationships.isNotEmpty(),
                        "대결우열" to context.duelStandings.isNotEmpty(),
                        "이미지태그" to context.imageTags.isNotEmpty(),
                        "메모" to context.memo.isNotBlank(),
                        "다른필드표" to context.filledFields.any { it.first != field.name },
                        "필드설명" to field.description.isNotBlank(),
                        // 원문은 이어쓰기·다듬기의 **전제**다 — 자리가 없으면 그 모드가 성립하지 않는다.
                        "원문" to (mode.requiresExisting && field.currentValue.isNotBlank()),
                        "문체표본" to field.styleSamples.isNotEmpty()
                    )
                )
            )

            val text = PromptTokens.expand(
                template,
                mapOf(
                    "캐릭터명" to context.name,
                    "이명목록" to context.aliases.joinToString(", "),
                    "태그목록" to context.tags.joinToString(", "),
                    "소속세력" to context.factions.joinToString(", "),
                    "관계요약" to context.relationships.joinToString(" / "),
                    "대결우열" to duelText,
                    "이미지태그" to context.imageTags.joinToString(", "),
                    "메모" to context.memo.trim(),
                    "다른필드표" to othersText,
                    "대상필드" to "[${field.groupName}] ${field.name}",
                    "필드설명" to descText,
                    "원문" to existingText,
                    "문체표본" to styleText,
                    "모드지시" to instructionFor(mode),
                    "분량힌트" to length.hint,
                    "초안개수" to variants.toString()
                )
            )
            return PromptBuild(text, notes)
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
