package com.novelcharacter.app.ai

import com.novelcharacter.app.util.stringOr


/**
 * 이미지 폴더 이름으로 **태그를 제안**한다 (설계 `docs/image_folder_tag_ai_2026-07.md` 3장).
 *
 * ## 무엇을 읽는가
 *
 * **폴더 이름이라는 텍스트만 읽는다 — 이 기능은 이미지를 모델에 보내지 않는다.** 대상은
 * 받아오기 계획이 고른 '그 외' 폴더뿐이며 (캐릭터 폴더·서랍·예약 폴더는 들어오지 않는다),
 * 그 판정은 `FolderRoundtripPlanner`가 한다.
 *
 * **범위를 이 기능으로 좁혀 적는 이유 (2026.08.07 · B-120):** 종전 문구는 *"비전 API가
 * 아니므로 비용·프라이버시 성격이 기존 AI 기능과 같다"*로 **앱 전체를 두고 한 약속**이었는데,
 * 그 약속은 이제 참이 아니다 — 필드 추천·서술형 작성이 캐릭터 이미지를 실제로 보낸다(A-7).
 * 여기 남은 문장은 **이 서제스터 하나의 성질**이고 그것은 그대로 참이다. 앱 전체의 현행
 * 사실은 `docs/ai_integration.md` A-7이 든다.
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
 * **단 '어휘 밖'의 판정은 정확 일치가 아니다** — 공백·대소문자만 다른 것은 기존 표기로
 * 접고 새 태그로 세지 않는다([ImageTagVocabulary.fold] · B-127). 표기가 갈리는 것과
 * 새 개념이 나오는 것은 다른 일이고, 앞엣것까지 새 태그로 부르면 필터가 둘로 쪼개진다.
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
        val failures: List<AiResult.Failure> = emptyList(),
        /**
         * 실패가 아닌 고지 — 지금은 프로바이더 자동 전환 한 줄이다 (B-108 확정 ⓑ).
         * [failures]가 [AiResult.Failure] 타입이라 문자열 고지를 담을 자리가 아니고,
         * 담으면 화면이 *"실패했습니다"*로 렌더한다.
         */
        val notes: List<String> = emptyList()
    )

    companion object {

        /** 재시도해도 같은 결과인 실패 — 잔여 청크 중단 기준. 집합은 [AiErrorPolicy]가 단일 소스다. */
        private val TERMINAL_ERRORS = AiErrorPolicy.TERMINAL

        /** 폴더를 요청 단위로 나눈다 — 개수 규칙의 단일 소스는 [AiPromptPolicy]다. */
        fun chunkFolders(folders: List<String>): List<List<String>> =
            folders.chunked(AiPromptPolicy.IMAGE_TAG_FOLDERS_PER_REQUEST)

        fun buildSystemPrompt(
            vocab: ImageTagVocabulary.Vocabulary,
            policy: String,
            template: String = PromptTemplates.default(PromptTemplates.Id.FOLDER_TAG_SYSTEM)
        ): String = PromptTokens.expand(
            template,
            mapOf(
                PromptTemplates.T_RESPONSE to
                    PromptTemplates.responseFormat(PromptTemplates.Id.FOLDER_TAG_SYSTEM),
                "태그최대길이" to AiPromptPolicy.IMAGE_TAG_MAX_LENGTH.toString(),
                "최대개수" to AiPromptPolicy.IMAGE_TAG_MAX_PER_FOLDER.toString(),
                "기존태그어휘" to vocab.tags.joinToString(", "),
                "필드값어휘" to vocab.fieldValues.joinToString(", "),
                "사용자지침" to policy
            )
        )

        fun buildUserText(
            folders: List<String>,
            imageCounts: Map<String, Int>,
            template: String = PromptTemplates.default(PromptTemplates.Id.FOLDER_TAG_USER)
        ): String {
            val list = folders.joinToString("\n") { name ->
                val n = imageCounts[name] ?: 0
                if (n > 0) "- $name (이미지 ${n}장)" else "- $name"
            }
            return PromptTokens.expand(template, mapOf("폴더목록" to list))
        }

        /**
         * 응답을 검증하며 읽는다. **요청한 폴더만** 통과시키고 나머지는 사유별로 센다.
         *
         * **어휘 대조는 [ImageBatchTagSuggester.foldToVocabulary]와 같은 규칙이다** — 공백·대소문자를
         * 무시해 일치하면 **기존 표기로 접는다**(B-127, 확정 14장 8번 ⓐ). 종전에는 이쪽만
         * `it !in vocab`이라 정확 일치로만 봐서, 폴더 `물의 정령`이 태그 `물의정령`을 두고도
         * **새 태그로 갈렸다** — 같은 앱의 두 AI 태깅 경로가 같은 말에 다른 태그를 만드는 것이다.
         * 어휘 조립은 [ImageTagVocabulary] 하나로 합쳐 두었는데 **대조 규칙만 두 벌로 남아 있었다.**
         *
         * 접힌 것은 새 태그가 아니므로 [Suggestion.isNew] 표식도 붙지 않는다.
         *
         * @param requested 이 요청에 실제로 보낸 폴더명. 여기 없는 이름은 환각이다.
         * @param vocab 접기 대조와 `새 태그` 판정에 함께 쓰는 어휘.
         */
        fun parse(
            raw: String,
            requested: Collection<String>,
            vocab: ImageTagVocabulary.Vocabulary
        ): Pair<List<FolderSuggestion>, DropTally> {
            val json = AiJsonExtractor.extractObject(raw) ?: return emptyList<FolderSuggestion>() to DropTally()
            val wanted = requested.toHashSet()
            val folding = vocab.forFolding
            val known = vocab.all
            val out = ArrayList<FolderSuggestion>()
            var unknown = 0
            var blank = 0
            var over = 0

            val arr = json.optJSONArray("folders") ?: return emptyList<FolderSuggestion>() to DropTally()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.stringOr("name").trim()
                if (name.isEmpty() || name !in wanted) { unknown++; continue }
                // 같은 폴더를 두 번 되돌리면 뒤엣것은 버린다 — 어느 쪽이 옳은지 알 수 없다.
                if (!seen.add(name)) { unknown++; continue }

                val tagsArr = obj.optJSONArray("tags")
                val picked = LinkedHashMap<String, Suggestion>()
                if (tagsArr != null) {
                    for (t in 0 until tagsArr.length()) {
                        val answered = tagsArr.stringOr(t).trim()
                        if (answered.isEmpty() ||
                            answered.length > AiPromptPolicy.IMAGE_TAG_MAX_LENGTH) { blank++; continue }
                        val folded = ImageTagVocabulary.fold(answered, folding)
                        val tag = folded ?: answered
                        // 접은 뒤에 같아진 것은 드롭이 아니다 — 같은 말을 두 번 받은 것뿐이다.
                        if (picked.containsKey(tag)) continue
                        if (picked.size >= AiPromptPolicy.IMAGE_TAG_MAX_PER_FOLDER) { over++; continue }
                        picked[tag] = Suggestion(tag, isNew = folded == null && tag !in known)
                    }
                }
                if (picked.isEmpty()) continue   // 근거 없음은 결손이 아니다(프롬프트가 허용한 답)
                out.add(FolderSuggestion(name, picked.values.toList()))
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
        vocab: ImageTagVocabulary.Vocabulary,
        policyRaw: String,
        /** 사용자가 고친 양식. 넘기지 않으면 기본 양식이다 (사용자 요청 2026.08.20). */
        templates: PromptTemplates.Source = PromptTemplates.Source.DEFAULTS
    ): Result {
        if (folders.isEmpty()) return Result()
        val policy = AiPromptPolicy.clampImageTagPolicy(policyRaw)
        val policyTruncated = (policyRaw.trim().length - policy.length).coerceAtLeast(0)
        val system = buildSystemPrompt(
            vocab, policy, templates.templateOf(PromptTemplates.Id.FOLDER_TAG_SYSTEM)
        )
        val userTemplate = templates.templateOf(PromptTemplates.Id.FOLDER_TAG_USER)

        val all = ArrayList<FolderSuggestion>()
        val failures = ArrayList<AiResult.Failure>()
        val notes = ArrayList<String>()
        var drops = DropTally(vocabTruncated = vocab.truncated, policyTruncated = policyTruncated)

        for (chunk in chunkFolders(folders)) {
            val request = AiRequest(system = system, userText = buildUserText(chunk, imageCounts, userTemplate))
            when (val result = aiService.complete(request)) {
                is AiResult.Success -> {
                    // 한도로 밀려 다른 프로바이더가 답한 청크 (B-108 확정 ⓑ).
                    AiProviderFallback.switchNoteOf(result)
                        ?.let { if (it !in notes) notes.add(it) }
                    val (parsed, tally) = parse(result.text, chunk, vocab)
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
        return Result(all, drops, failures, notes)
    }
}
