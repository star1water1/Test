package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry

/**
 * 필드 데이터 라이브러리 AI 정리 — 유사값 병합·오탈자 교정(병합으로 모델링)·카테고리 제안.
 *
 * 계약 (docs/ai_integration.md):
 * - 온디맨드 전용. 호출측이 hasUsableProvider() 가드 + 실행 전 비용(요청 수) 고지.
 * - AI 출력은 절대 자동 적용하지 않는다 — 검토 체크리스트에서 사용자가 선택 적용.
 * - 검증(변수 제어): 존재하지 않는 값을 참조하는 제안은 드롭하고 드롭 수를 보고한다.
 *
 * 프롬프트 조립·응답 파싱은 AiService 미호출 순수 함수로 분리되어 단위 테스트된다.
 */
class FieldLibraryAiOrganizer(private val aiService: AiService) {

    data class MergeSuggestion(
        val canonical: String,
        val variants: List<String>,
        val reason: String
    )

    data class CategorySuggestion(
        val value: String,
        val category: String
    )

    data class OrganizeOutcome(
        val merges: List<MergeSuggestion>,
        val categories: List<CategorySuggestion>,
        /** 실존하지 않는 값 참조·중복 등으로 제외된 제안 수 (변수 제어: 조용히 버리지 않고 고지) */
        val droppedCount: Int,
        /** 청크별 실패 메시지 — 부분 실패도 성공분과 함께 반환 */
        val failures: List<String>,
        val totalInputTokens: Int,
        val totalOutputTokens: Int
    )

    suspend fun organize(
        fd: FieldDefinition,
        entries: List<FieldValueEntry>,
        /** 사용자가 고친 양식. 넘기지 않으면 기본 양식이다 (사용자 요청 2026.08.20). */
        templates: PromptTemplates.Source = PromptTemplates.Source.DEFAULTS,
        errorMessageOf: (AiResult.Failure) -> String
    ): OrganizeOutcome {
        val visible = entries.sortedByDescending { it.usageCount }
        val chunks = chunkEntries(visible)
        val merges = mutableListOf<MergeSuggestion>()
        val categories = mutableListOf<CategorySuggestion>()
        val failures = mutableListOf<String>()
        var dropped = 0
        var inputTokens = 0
        var outputTokens = 0

        val maxTokens = aiService.effectiveMaxTokens()
        for (chunk in chunks) {
            val request = AiRequest(
                system = buildSystemPrompt(
                    fd, templates.templateOf(PromptTemplates.Id.VALUE_LIBRARY_SYSTEM)
                ),
                userText = buildUserPrompt(
                    chunk, templates.templateOf(PromptTemplates.Id.VALUE_LIBRARY_USER)
                ),
                maxTokens = maxTokens
            )
            when (val result = aiService.complete(request)) {
                is AiResult.Success -> {
                    inputTokens += result.inputTokens ?: 0
                    outputTokens += result.outputTokens ?: 0
                    // 한도로 밀려 다른 프로바이더가 답한 구간 (B-108 확정 ⓑ) — 구간마다
                    // 뜰 수 있으므로 같은 줄은 한 번만 남긴다.
                    AiProviderFallback.switchNoteOf(result)
                        ?.let { if (it !in failures) failures.add(it) }
                    val parsed = parseResponse(result.text, entries)
                    if (parsed == null) {
                        // 잘린 것과 형식이 깨진 것은 원인도 교정 경로도 다르다 (CharacterFieldAiSuggester와 동일 규약).
                        failures.add(
                            if (result.truncated) TRUNCATED_MESSAGE
                            else "응답 형식을 해석할 수 없어 일부 구간을 건너뛰었습니다"
                        )
                    } else {
                        dropped += parsed.droppedCount
                        merges.addAll(parsed.merges)
                        categories.addAll(parsed.categories)
                        if (result.truncated) failures.add(TRUNCATED_MESSAGE)
                    }
                }
                is AiResult.Failure -> {
                    failures.add(errorMessageOf(result))
                    // 인증·설정류 오류는 다음 청크도 같은 결과 — 요청 낭비 방지를 위해 중단
                    if (result.kind in TERMINAL_ERRORS) break
                }
            }
        }

        // 청크 간 겹침 정리: 같은 variant가 여러 병합 그룹에 나오면 후순위 드롭
        val seenVariants = mutableSetOf<String>()
        val dedupedMerges = merges.filter { m ->
            val fresh = m.variants.none { it in seenVariants }
            if (fresh) seenVariants.addAll(m.variants) else dropped++
            fresh
        }
        val seenCategoryValues = mutableSetOf<String>()
        val dedupedCategories = categories.filter { c ->
            val fresh = seenCategoryValues.add(c.value)
            if (!fresh) dropped++
            fresh
        }

        return OrganizeOutcome(dedupedMerges, dedupedCategories, dropped, failures, inputTokens, outputTokens)
    }

    companion object {
        /** 요청당 값 상한 — 응답 토큰 한도(4096) 안에서 병합/분류 결과가 잘리지 않는 규모 */
        const val CHUNK_MAX_VALUES = 120
        const val CHUNK_MAX_CHARS = 6000

        /** 출력 상한에 걸려 잘린 경우 — 원인과 교정 경로를 정확히 말한다(재시도는 같은 결과다). */
        const val TRUNCATED_MESSAGE =
            "AI 응답이 출력 상한에 걸려 잘려 일부 구간을 건너뛰었습니다 — " +
                "설정 → AI 연동에서 출력 토큰 상한을 올리면 한 번에 더 많이 정리할 수 있습니다."

        /** 재시도해도 같은 결과인 실패 — 잔여 청크 중단 기준. 집합은 [AiErrorPolicy]가 단일 소스다. */
        private val TERMINAL_ERRORS = AiErrorPolicy.TERMINAL

        fun chunkEntries(entries: List<FieldValueEntry>): List<List<FieldValueEntry>> {
            if (entries.isEmpty()) return emptyList()
            val chunks = mutableListOf<List<FieldValueEntry>>()
            var current = mutableListOf<FieldValueEntry>()
            var chars = 0
            for (e in entries) {
                // 설명 몫까지 센다 — 프롬프트에 실리는 것을 여기서 안 세면
                // [CHUNK_MAX_CHARS]가 거짓이 되고, **비용 고지가 함께 거짓이 된다**
                // (`AiOrganizeSheet`가 이 함수의 청크 수로 요청 수를 고지한다).
                val len = e.value.length + e.aliasesJson.length + e.description.length + 16
                if (current.isNotEmpty() && (current.size >= CHUNK_MAX_VALUES || chars + len > CHUNK_MAX_CHARS)) {
                    chunks.add(current)
                    current = mutableListOf()
                    chars = 0
                }
                current.add(e)
                chars += len
            }
            if (current.isNotEmpty()) chunks.add(current)
            return chunks
        }

        fun buildSystemPrompt(
            fd: FieldDefinition,
            template: String = PromptTemplates.default(PromptTemplates.Id.VALUE_LIBRARY_SYSTEM)
        ): String {
            // 필드 설명(A-2) — 세 AI 경로가 같은 설명을 본다. 정리 요청은 필드 하나 전용이라
            // 절단 없이 전문(저장 상한 1000자)을 싣는다 — 자르면 고지 채널 없이 조용한 결손이 된다.
            val description = com.novelcharacter.app.data.model.FieldDescription.fromConfig(fd.config)
            return PromptTokens.expand(
                template,
                mapOf(
                    PromptTemplates.T_RESPONSE to
                        PromptTemplates.responseFormat(PromptTemplates.Id.VALUE_LIBRARY_SYSTEM),
                    "필드명" to fd.name,
                    "필드타입" to fd.type.toString(),
                    "필드설명" to description
                )
            )
        }

        fun buildUserPrompt(
            chunk: List<FieldValueEntry>,
            template: String = PromptTemplates.default(PromptTemplates.Id.VALUE_LIBRARY_USER)
        ): String {
            val list = chunk.joinToString("\n") { e ->
                buildString {
                    append(e.value)
                    append(" (사용 ").append(e.usageCount).append("회")
                    val aliases = e.aliases()
                    if (aliases.isNotEmpty()) append(" · 별칭: ").append(aliases.joinToString(", "))
                    if (e.category.isNotBlank()) append(" · 분류: ").append(e.category)
                    // 값의 뜻 (B-46) — **병합 판단의 직접 근거다.** 사용자가 '북부'와 '북부지방'을
                    // 뜻으로 갈라 두었는데 그것을 안 보내면 모델이 둘을 합치라고 제안하고,
                    // 사용자가 그 제안을 받으면 **구별이 사라진다.** 지시문 양식이 이미
                    // *"이 설명을 병합·분류 판단의 근거로 삼아라"*라고 말하고 있었다.
                    // 줄바꿈은 접는다 — 엔트리를 줄 단위로 잇는 형식이라 원문 개행이 행을 쪼갠다.
                    val meaning = e.description.replace('\n', ' ').replace('\r', ' ').trim()
                    if (meaning.isNotEmpty()) append(" · 뜻: ").append(meaning)
                    append(")")
                }
            }
            return PromptTokens.expand(template, mapOf("값목록" to list))
        }

        data class ParsedResponse(
            val merges: List<MergeSuggestion>,
            val categories: List<CategorySuggestion>,
            val droppedCount: Int
        )

        /**
         * 응답 파싱 + 실제 라이브러리 상태 검증 (AiService 미호출 — 단위 테스트 대상).
         * 드롭 규칙: canonical/variant/value가 실존 canonical이 아니면 제외(환각),
         * variant가 이미 어떤 엔트리의 별칭이면 제외(기병합), canonical==variant 제외.
         */
        fun parseResponse(text: String, entries: List<FieldValueEntry>): ParsedResponse? {
            val root = AiJsonExtractor.extractObject(text) ?: return null
            val canonicalSet = entries.map { it.value }.toSet()
            val aliasSet = entries.flatMap { it.aliases() }.toSet()
            var dropped = 0

            val merges = mutableListOf<MergeSuggestion>()
            val mergesArr = root.optJSONArray("merges")
            if (mergesArr != null) {
                for (i in 0 until mergesArr.length()) {
                    val obj = mergesArr.optJSONObject(i) ?: continue
                    val canonical = obj.optString("canonical").trim()
                    val reason = obj.optString("reason").trim()
                    val variantsArr = obj.optJSONArray("variants")
                    val rawVariants = (0 until (variantsArr?.length() ?: 0))
                        .mapNotNull { variantsArr?.optString(it)?.trim() }
                        .filter { it.isNotEmpty() }
                    if (canonical.isEmpty() || canonical !in canonicalSet) {
                        if (canonical.isNotEmpty() || rawVariants.isNotEmpty()) dropped++
                        continue
                    }
                    val validVariants = rawVariants.filter { v ->
                        val valid = v != canonical && v in canonicalSet && v !in aliasSet
                        if (!valid) dropped++
                        valid
                    }
                    if (validVariants.isNotEmpty()) {
                        merges.add(MergeSuggestion(canonical, validVariants, reason))
                    }
                }
            }

            val categories = mutableListOf<CategorySuggestion>()
            val categoriesArr = root.optJSONArray("categories")
            if (categoriesArr != null) {
                for (i in 0 until categoriesArr.length()) {
                    val obj = categoriesArr.optJSONObject(i) ?: continue
                    val value = obj.optString("value").trim()
                    val category = obj.optString("category").trim()
                    if (value.isEmpty() || category.isEmpty()) continue
                    if (value !in canonicalSet) {
                        dropped++
                        continue
                    }
                    categories.add(CategorySuggestion(value, category))
                }
            }

            return ParsedResponse(merges, categories, dropped)
        }
    }

    private fun parseResponse(text: String, entries: List<FieldValueEntry>) =
        Companion.parseResponse(text, entries)
}
