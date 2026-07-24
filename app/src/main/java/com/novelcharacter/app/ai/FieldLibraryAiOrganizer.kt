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

        for (chunk in chunks) {
            val request = AiRequest(
                system = buildSystemPrompt(fd),
                userText = buildUserPrompt(chunk),
                maxTokens = 4096
            )
            when (val result = aiService.complete(request)) {
                is AiResult.Success -> {
                    inputTokens += result.inputTokens ?: 0
                    outputTokens += result.outputTokens ?: 0
                    val parsed = parseResponse(result.text, entries)
                    if (parsed == null) {
                        failures.add("응답 형식을 해석할 수 없어 일부 구간을 건너뛰었습니다")
                    } else {
                        dropped += parsed.droppedCount
                        merges.addAll(parsed.merges)
                        categories.addAll(parsed.categories)
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

        private val TERMINAL_ERRORS = setOf(
            AiErrorKind.NO_PROVIDER, AiErrorKind.NO_KEY, AiErrorKind.INVALID_KEY,
            AiErrorKind.QUOTA_EXCEEDED, AiErrorKind.MODEL_NOT_FOUND
        )

        fun chunkEntries(entries: List<FieldValueEntry>): List<List<FieldValueEntry>> {
            if (entries.isEmpty()) return emptyList()
            val chunks = mutableListOf<List<FieldValueEntry>>()
            var current = mutableListOf<FieldValueEntry>()
            var chars = 0
            for (e in entries) {
                val len = e.value.length + e.aliasesJson.length + 16
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

        fun buildSystemPrompt(fd: FieldDefinition): String = """
            당신은 소설 캐릭터 관리 앱의 데이터 정리 도우미다.
            필드 '${fd.name}'(타입 ${fd.type})의 값 목록에서 다음을 찾아라:
            1. merges: 같은 대상을 가리키는 변형 표기(오탈자, 띄어쓰기, 표기 차이)를 하나의 canonical로 병합.
               canonical은 목록에 실제로 존재하는 값 중 가장 적절한 것을 고른다.
            2. categories: 값들을 묶을 수 있는 상위 카테고리 제안 (확실한 것만).
            반드시 아래 JSON 스키마로만 응답하고 다른 텍스트를 덧붙이지 마라:
            {"merges":[{"canonical":"값","variants":["변형1","변형2"],"reason":"근거"}],"categories":[{"value":"값","category":"카테고리"}]}
            병합·분류할 것이 없으면 빈 배열을 반환한다. 목록에 없는 값을 만들어내지 마라.
        """.trimIndent()

        fun buildUserPrompt(chunk: List<FieldValueEntry>): String =
            chunk.joinToString("\n") { e ->
                buildString {
                    append(e.value)
                    append(" (사용 ").append(e.usageCount).append("회")
                    val aliases = e.aliases()
                    if (aliases.isNotEmpty()) append(" · 별칭: ").append(aliases.joinToString(", "))
                    if (e.category.isNotBlank()) append(" · 분류: ").append(e.category)
                    append(")")
                }
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
