package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldValueEntry

/**
 * 이미지 폴더 이름으로 **태그를 제안**한다 (설계 `docs/image_folder_tag_ai_2026-07.md` 3장).
 *
 * ## 무엇을 읽는가
 *
 * **폴더 이름이라는 텍스트만 읽는다 — 이미지를 모델에 보내지 않는다.** 비전 API가 아니므로
 * 비용·프라이버시 성격이 기존 AI 기능과 같다. 대상은 받아오기 계획이 고른 '그 외' 폴더뿐이며
 * (캐릭터 폴더·서랍·예약 폴더는 들어오지 않는다), 그 판정은 `FolderRoundtripPlanner`가 한다.
 *
 * ## 계약 (docs/ai_integration.md — 예외 없이 상속)
 *
 * - 온디맨드 전용. 호출측이 `hasUsableProvider()` 가드 + 실행 전 비용(요청 수) 고지.
 * - **AI 출력은 절대 자동 적용하지 않는다** — 검토 시트에서 사용자가 골라 적용한다.
 * - 검증(변수 제어): 요청하지 않은 폴더·빈 태그·길이 초과·상한 초과를 드롭하고 **드롭 수를 보고**한다.
 * - 실패해도 받아오기는 완주한다 — 태그만 빠지고 편입·배정·링크는 그대로다.
 *
 * ## 어휘 밖의 새 태그는 버리지 않는다
 *
 * 어휘(기존 이미지 태그 + '어휘에 포함' 필드 값)는 **일관성을 위한 참고**이지 허용 목록이
 * 아니다. 폴더 이름에서 새 개념이 나오는 것은 정상이며, 어휘 밖이라고 버리면 "폴더 이름을
 * 읽는다"는 목적 자체가 반쯤 사라진다. 대신 [Suggestion.isNew]로 표시해 사용자가 판단한다
 * (자율성 우선 + 변수 제어).
 *
 * 프롬프트 조립·응답 파싱·검증은 전부 [AiService] 미호출 순수 함수라 단위 테스트된다.
 */
class ImageFolderTagSuggester(private val aiService: AiService) {

    /** 태그 하나의 제안. [isNew]면 기존 어휘에 없던 값이다(검토 시트가 표식을 단다). */
    data class Suggestion(val tag: String, val isNew: Boolean)

    /** 폴더 하나에 대한 제안 묶음. */
    data class FolderSuggestion(val folder: String, val tags: List<Suggestion>)

    /**
     * 드롭 사유별 개수 — 조용히 사라지는 것이 없게 한다(R-14·R-17).
     *
     * @param unknownFolder 요청하지 않은 폴더명을 되돌린 건수(환각).
     * @param blankOrTooLong 빈 태그·공백만·길이 초과.
     * @param overPerFolderCap 폴더당 상한을 넘어 버린 건수.
     * @param vocabTruncated 프롬프트에 싣지 못하고 자른 어휘 수.
     * @param policyTruncated 기조 문구를 자른 글자 수(0이면 자르지 않았다).
     */
    data class DropTally(
        val unknownFolder: Int = 0,
        val blankOrTooLong: Int = 0,
        val overPerFolderCap: Int = 0,
        val vocabTruncated: Int = 0,
        val policyTruncated: Int = 0
    ) {
        val isEmpty: Boolean
            get() = unknownFolder == 0 && blankOrTooLong == 0 && overPerFolderCap == 0 &&
                vocabTruncated == 0 && policyTruncated == 0

        operator fun plus(other: DropTally) = DropTally(
            unknownFolder + other.unknownFolder,
            blankOrTooLong + other.blankOrTooLong,
            overPerFolderCap + other.overPerFolderCap,
            vocabTruncated + other.vocabTruncated,
            policyTruncated + other.policyTruncated
        )
    }

    /** 실행 결과 — 성공분과 실패를 **함께** 돌려준다(부분 실패에도 얻은 것은 준다). */
    data class Result(
        val suggestions: List<FolderSuggestion> = emptyList(),
        val drops: DropTally = DropTally(),
        /** 청크 단위 실패. 비어 있지 않아도 [suggestions]는 쓸 수 있다. */
        val failures: List<AiResult.Failure> = emptyList()
    )

    /** 프롬프트에 실을 어휘와, 그것을 만들며 잘라낸 양. */
    data class Vocabulary(
        val tags: List<String> = emptyList(),
        val fieldValues: List<String> = emptyList(),
        val truncated: Int = 0
    ) {
        val all: Set<String> get() = LinkedHashSet<String>(tags.size + fieldValues.size).apply {
            addAll(tags); addAll(fieldValues)
        }
    }

    companion object {

        /** 재시도해도 같은 결과인 실패 — 잔여 청크 중단 기준. 기존 두 소비자와 **같은 집합**이다. */
        private val TERMINAL_ERRORS = setOf(
            AiErrorKind.NO_PROVIDER, AiErrorKind.NO_KEY, AiErrorKind.INVALID_KEY,
            AiErrorKind.QUOTA_EXCEEDED, AiErrorKind.MODEL_NOT_FOUND
        )

        /**
         * 어휘를 만든다 — 기존 이미지 태그(빈도 상위)와 '어휘에 포함' 필드의 값.
         *
         * 필드 값 선별은 [CharacterFieldAiSuggester.selectUsageExamples]를 **그대로 쓴다**
         * (빈도 상위 2/3 + 균등 간격 1/3, 난수 없음). 같은 문제이므로 같은 함수를 쓴다 —
         * 규칙이 갈리면 같은 데이터가 화면마다 다른 어휘를 낳는다.
         *
         * @param tagCounts 태그 → 사용 횟수.
         * @param entriesByField '어휘에 포함'이 켜진 필드별 값 라이브러리 엔트리.
         */
        fun buildVocabulary(
            tagCounts: Map<String, Int>,
            entriesByField: List<List<FieldValueEntry>>
        ): Vocabulary {
            val sortedTags = tagCounts.entries
                .filter { it.key.isNotBlank() }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
            val tags = sortedTags.take(AiPromptPolicy.IMAGE_TAG_VOCAB_MAX)
            var truncated = sortedTags.size - tags.size

            val values = LinkedHashSet<String>()
            for (entries in entriesByField) {
                val picked = CharacterFieldAiSuggester.selectUsageExamples(
                    entries, limit = AiPromptPolicy.IMAGE_TAG_VALUES_PER_FIELD
                )
                truncated += (entries.count { it.value.isNotBlank() } - picked.size).coerceAtLeast(0)
                for (v in picked) {
                    if (values.size >= AiPromptPolicy.IMAGE_TAG_VALUES_TOTAL_MAX) { truncated++; continue }
                    values.add(v)
                }
            }
            // 태그와 겹치는 값은 어휘에 두 번 싣지 않는다(토큰 낭비이지 잘라낸 것이 아니다).
            val tagSet = tags.toHashSet()
            return Vocabulary(tags, values.filterNot { it in tagSet }, truncated)
        }

        /** 폴더를 요청 단위로 나눈다 — 개수 규칙의 단일 소스는 [AiPromptPolicy]다. */
        fun chunkFolders(folders: List<String>): List<List<String>> =
            folders.chunked(AiPromptPolicy.IMAGE_TAG_FOLDERS_PER_REQUEST)

        fun buildSystemPrompt(vocab: Vocabulary, policy: String): String = buildString {
            append("당신은 창작 자료 이미지의 분류를 돕는다. ")
            append("입력으로 받은 **폴더 이름들**을 읽고, 각 폴더에 어울리는 태그를 제안하라.\n")
            append("- 폴더 이름은 사용자가 이미지를 모아 둔 자리의 이름이다. 이름에서 드러나는 ")
            append("장면·소재·분위기·복장 같은 분류축을 태그로 뽑는다.\n")
            append("- 태그는 짧은 명사구로 한다(최대 ")
            append(AiPromptPolicy.IMAGE_TAG_MAX_LENGTH)
            append("자). 폴더당 최대 ")
            append(AiPromptPolicy.IMAGE_TAG_MAX_PER_FOLDER)
            append("개.\n")
            append("- 아래 기존 어휘에 맞는 표기가 있으면 **그것을 그대로 쓴다**(표기 일관성). ")
            append("맞는 것이 없으면 새 태그를 만들어도 된다.\n")
            append("- 폴더 이름에서 근거를 찾을 수 없으면 그 폴더는 빈 배열로 둔다. 지어내지 않는다.\n")
            if (vocab.tags.isNotEmpty()) {
                append("\n[기존 이미지 태그]\n")
                append(vocab.tags.joinToString(", "))
                append("\n")
            }
            if (vocab.fieldValues.isNotEmpty()) {
                append("\n[작품 데이터에서 쓰는 값]\n")
                append(vocab.fieldValues.joinToString(", "))
                append("\n")
            }
            if (policy.isNotBlank()) {
                append("\n[사용자 지침 — 위 규칙과 충돌하면 이쪽을 따른다]\n")
                append(policy)
                append("\n")
            }
            append("\n출력은 JSON만. 형식: ")
            append("""{"folders":[{"name":"폴더이름","tags":["태그1","태그2"]}]}""")
        }

        fun buildUserText(folders: List<String>, imageCounts: Map<String, Int>): String =
            folders.joinToString("\n") { name ->
                val n = imageCounts[name] ?: 0
                if (n > 0) "- $name (이미지 ${n}장)" else "- $name"
            }

        /**
         * 응답을 검증하며 읽는다. **요청한 폴더만** 통과시키고 나머지는 사유별로 센다.
         *
         * @param requested 이 요청에 실제로 보낸 폴더명. 여기 없는 이름은 환각이다.
         */
        fun parse(raw: String, requested: Collection<String>, vocab: Set<String>): Pair<List<FolderSuggestion>, DropTally> {
            val json = AiJsonExtractor.extractObject(raw) ?: return emptyList<FolderSuggestion>() to DropTally()
            val wanted = requested.toHashSet()
            val out = ArrayList<FolderSuggestion>()
            var unknown = 0
            var blank = 0
            var over = 0

            val arr = json.optJSONArray("folders") ?: return emptyList<FolderSuggestion>() to DropTally()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty() || name !in wanted) { unknown++; continue }
                // 같은 폴더를 두 번 되돌리면 뒤엣것은 버린다 — 어느 쪽이 옳은지 알 수 없다.
                if (!seen.add(name)) { unknown++; continue }

                val tagsArr = obj.optJSONArray("tags")
                val picked = LinkedHashSet<String>()
                if (tagsArr != null) {
                    for (t in 0 until tagsArr.length()) {
                        val tag = tagsArr.optString(t).trim()
                        if (tag.isEmpty() || tag.length > AiPromptPolicy.IMAGE_TAG_MAX_LENGTH) { blank++; continue }
                        if (picked.size >= AiPromptPolicy.IMAGE_TAG_MAX_PER_FOLDER) { over++; continue }
                        picked.add(tag)
                    }
                }
                if (picked.isEmpty()) continue   // 근거 없음은 결손이 아니다(프롬프트가 허용한 답)
                out.add(FolderSuggestion(name, picked.map { Suggestion(it, it !in vocab) }))
            }
            return out to DropTally(unknownFolder = unknown, blankOrTooLong = blank, overPerFolderCap = over)
        }
    }

    /**
     * 제안을 받아온다. 청크 단위로 순차 실행하며, **실패한 청크는 격리**하고 성공분과 함께
     * 돌려준다(부분 실패에도 얻은 것은 준다 — 값 라이브러리 AI 정리와 같은 패턴).
     */
    suspend fun suggest(
        folders: List<String>,
        imageCounts: Map<String, Int>,
        vocab: Vocabulary,
        policyRaw: String
    ): Result {
        if (folders.isEmpty()) return Result()
        val policy = AiPromptPolicy.clampImageTagPolicy(policyRaw)
        val policyTruncated = (policyRaw.trim().length - policy.length).coerceAtLeast(0)
        val system = buildSystemPrompt(vocab, policy)
        val known = vocab.all

        val all = ArrayList<FolderSuggestion>()
        val failures = ArrayList<AiResult.Failure>()
        var drops = DropTally(vocabTruncated = vocab.truncated, policyTruncated = policyTruncated)

        for (chunk in chunkFolders(folders)) {
            val request = AiRequest(system = system, userText = buildUserText(chunk, imageCounts))
            when (val result = aiService.complete(request)) {
                is AiResult.Success -> {
                    val (parsed, tally) = parse(result.text, chunk, known)
                    all.addAll(parsed)
                    drops += tally
                }
                is AiResult.Failure -> {
                    failures.add(result)
                    // 재시도해도 같은 결과인 실패는 남은 청크도 같은 이유로 실패한다 — 돈만 쓴다.
                    if (result.kind in TERMINAL_ERRORS) break
                }
            }
        }
        return Result(all, drops, failures)
    }
}
