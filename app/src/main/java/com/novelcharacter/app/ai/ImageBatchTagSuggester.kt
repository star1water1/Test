package com.novelcharacter.app.ai

/**
 * 이미지 **내용**을 보고 태그를 제안한다 — 일괄 (B-121, 설계 `docs/feature_roadmap_2026-08.md` 2-3).
 *
 * ## 무엇을 읽는가 — 형제 기능과 갈리는 지점
 *
 * [ImageFolderTagSuggester]는 **폴더 이름이라는 텍스트만** 읽는다. 이쪽은 **그림 자체를 보낸다**
 * (A-7의 멀티모달 기반 위에 선다). 그래서 같은 '이미지 태그'라도 성질이 다르다 — 비용이 장수에
 * 비례하고, 이미지가 기기 밖으로 나간다.
 *
 * ## 오배정 방어 — 이 기능의 실제 위험은 비용이 아니다
 *
 * 한 요청에 여러 장을 실으므로 **응답의 태그가 남의 이미지에 붙을 수 있다.** 그래서 번호로
 * 봉인한다: 프롬프트가 각 장을 `이미지 1`, `이미지 2`…로 부르고 응답은 그 번호를 되돌려야 한다.
 * 번호가 범위를 벗어나거나 겹치면 **그 배치를 통째로 접는다** — 절반만 믿고 붙이는 것이 곧
 * 오배정이고, *오배정은 생략보다 나쁘다*(R-1이 세운 순서). 접힌 배치는 [Result.failures]로
 * 돌아가고 화면이 '1장씩 다시 보내기'를 연다(장수 1이면 번호 사고가 원리적으로 없다).
 *
 * ## 이미지가 빠진 응답은 쓰지 않는다
 *
 * 모델이 이미지를 거부하면 [AiService]는 A-7의 관행대로 **글만 보내 재시도**하고 성공을
 * 돌려준다. 다른 기능에서는 그것이 옳다(그림은 곁들이라 글만으로도 답이 선다). **여기서는
 * 아니다** — 그림이 근거의 전부라, 글만 보고 나온 태그는 지어낸 것이다. 그래서
 * [AiResult.Success.imagesOmitted]가 서면 그 배치를 [BatchFailKind.IMAGES_UNSUPPORTED]로
 * 접고 **남은 배치도 돌리지 않는다**(학습된 사실이라 뒤에도 같은 일이 난다 — 돈만 쓴다).
 *
 * 프롬프트 조립·응답 파싱·검증·접기는 전부 [AiService] 미호출 순수 함수라 단위 테스트된다.
 */
class ImageBatchTagSuggester(private val aiService: AiService) {

    /** 태그 하나의 제안. [isNew]면 기존 어휘에 없던 값이다(검토 시트가 표식을 단다). */
    data class Suggestion(val tag: String, val isNew: Boolean)

    /** 이미지 한 장에 대한 제안 묶음. [path]는 요청에 실은 그 경로다. */
    data class ImageSuggestion(val path: String, val tags: List<Suggestion>)

    /**
     * 드롭 사유별 개수 — 조용히 사라지는 것이 없게 한다(R-14·R-17).
     *
     * @param blankOrTooLong 빈 태그·공백만·길이 초과.
     * @param overPerImageCap 이미지당 상한을 넘어 버린 건수.
     * @param unreadable 파일이 없거나 읽지 못해 요청에 싣지 못한 장수.
     * @param blocked 앱 저장소 밖이라 **보내지 않은** 장수 (B-141). [unreadable]과 갈라 세는
     *   이유는 처방이 다르기 때문이다 — 못 읽은 것은 파일을 확인할 일이고, 막힌 것은 그
     *   그림을 앱에 들여야 할 일이다(같은 판단이 [BatchFailKind.RESPONSE_TRUNCATED]를 갈랐다).
     * @param vocabTruncated 프롬프트에 싣지 못하고 자른 어휘 수.
     * @param policyTruncated 기조 문구를 자른 글자 수(0이면 자르지 않았다).
     */
    data class DropTally(
        val blankOrTooLong: Int = 0,
        val overPerImageCap: Int = 0,
        val unreadable: Int = 0,
        val blocked: Int = 0,
        val vocabTruncated: Int = 0,
        val policyTruncated: Int = 0
    ) {
        val isEmpty: Boolean
            get() = blankOrTooLong == 0 && overPerImageCap == 0 && unreadable == 0 &&
                blocked == 0 && vocabTruncated == 0 && policyTruncated == 0

        operator fun plus(other: DropTally) = DropTally(
            blankOrTooLong + other.blankOrTooLong,
            overPerImageCap + other.overPerImageCap,
            unreadable + other.unreadable,
            blocked + other.blocked,
            vocabTruncated + other.vocabTruncated,
            policyTruncated + other.policyTruncated
        )

        /**
         * **되받은 실행의 집계를 앞 실행 위에 얹는다** — [plus]와 규칙이 다르다(B-140).
         *
         * [plus]는 *한 실행 안에서 배치를 쌓는* 셈이라 전부 더하는 것이 맞다. 되받기는 다르다:
         * **같은 파일·같은 프롬프트를 다시 부르는 것**이라, 그대로 더하면 한 번 일어난 일이
         * 되받은 횟수만큼 부풀어 고지가 거짓말을 한다(R-14가 세운 것은 *개수로 알린다*이지
         * *많아 보이게 한다*가 아니다).
         *
         * 그래서 축을 둘로 가른다:
         * - **응답이 만든 것**([blankOrTooLong]·[overPerImageCap])은 **더한다** — 두 실행의
         *   응답은 서로 다른 응답이고, 각각이 실제로 그만큼 버렸다.
         * - **프롬프트와 파일이 만든 것**([vocabTruncated]·[policyTruncated]·[unreadable]·
         *   [blocked])은 **큰 쪽을 든다** — 어휘·기조는 두 실행이 같은 것을 싣고 같은 자리에서
         *   잘리고, 못 읽는 파일은 다시 불러도 여전히 못 읽으며 **막히는 경로는 다시 불러도
         *   여전히 막힌다**(가드가 경로만 보므로 판정이 실행마다 같다). 더하면 어휘 120개를
         *   한 번 자른 것이 되받기 한 번에 240개가 된다.
         */
        fun mergeRetry(retry: DropTally) = DropTally(
            blankOrTooLong = blankOrTooLong + retry.blankOrTooLong,
            overPerImageCap = overPerImageCap + retry.overPerImageCap,
            unreadable = maxOf(unreadable, retry.unreadable),
            blocked = maxOf(blocked, retry.blocked),
            vocabTruncated = maxOf(vocabTruncated, retry.vocabTruncated),
            policyTruncated = maxOf(policyTruncated, retry.policyTruncated)
        )
    }

    /** 배치 하나가 통째로 접힌 사유. 전부 "절반만 믿는 것보다 낫다"는 같은 판단의 갈래다. */
    enum class BatchFailKind {
        /** 응답에서 JSON을 못 찾았거나 `images` 배열이 없다. */
        NO_JSON,

        /** 되돌아온 번호가 요청 범위 밖이다 — 어느 장을 가리키는지 알 수 없다. */
        INDEX_OUT_OF_RANGE,

        /** 같은 번호가 두 번 왔다 — 어느 쪽이 그 장의 답인지 알 수 없다. */
        INDEX_DUPLICATED,

        /** 이 배치의 이미지를 한 장도 읽지 못해 보낼 것이 없었다. */
        IMAGES_UNREADABLE,

        /**
         * 이 배치가 **통째로 앱 저장소 밖**이라 보낼 것이 없었다 (B-141).
         * [IMAGES_UNREADABLE]과 갈라 두는 이유는 [DropTally.blocked]와 같다 — 파일은 멀쩡히
         * 있으므로 *"읽지 못했다"*고 말하면 사용자가 없는 문제를 찾는다.
         */
        IMAGES_BLOCKED,

        /** 모델이 이미지를 받지 않아 글만 나갔다 — 그림이 근거의 전부인 기능이라 버린다. */
        IMAGES_UNSUPPORTED,

        /**
         * 출력이 상한에 걸려 잘렸다. 잘린 JSON은 어차피 파싱에 실패하는데, [NO_JSON]으로
         * 뭉뚱그리면 고지가 *"번호가 어긋났다"*는 **틀린 사유**를 말하고 사용자는 엉뚱한 곳을
         * 고치려 든다. 이쪽의 처방은 **장수를 줄이는 것**이라 사유를 갈라 둔다.
         */
        RESPONSE_TRUNCATED,

        /** 요청 자체가 실패했다([failure]에 원인). */
        REQUEST_FAILED
    }

    /** 접힌 배치. [paths]는 그 배치가 담고 있던 이미지 — 재시도 경로가 이것을 쓴다. */
    data class BatchFailure(
        val paths: List<String>,
        val kind: BatchFailKind,
        val failure: AiResult.Failure? = null
    )

    /**
     * 실행 결과 — 성공분과 실패를 **함께** 돌려준다.
     * 끝난 배치의 제안은 살린다(B-108 ⓕ의 관행 — 완결된 몫은 버리지 않는다).
     */
    data class Result(
        val suggestions: List<ImageSuggestion> = emptyList(),
        val drops: DropTally = DropTally(),
        val failures: List<BatchFailure> = emptyList(),
        /** 사용자가 취소해 **시작하지 않은** 배치가 있는가. 중단 시점까지의 제안은 위에 있다. */
        val cancelled: Boolean = false,
        /**
         * 실패가 아닌 고지 — 지금은 프로바이더 자동 전환 한 줄이다 (B-108 확정 ⓑ).
         *
         * [failures]에 섞지 않는 이유: 그쪽은 **되받아 볼 경로**([retryablePaths])와 실패 요약을
         * 정하는 타입이라, 성공한 배치의 고지를 넣으면 *"이 배치를 1장씩 다시 보내시겠습니까"*가
         * 뜬다 — 이미 답을 받은 배치에 대고 돈을 더 쓰라는 권유가 된다.
         */
        val notes: List<String> = emptyList()
    )

    /** 배치 하나의 파싱 결과 — 통째로 접히거나, 번호별 태그를 준다. */
    sealed class ParseOutcome {
        data class Ok(val tagsByIndex: Map<Int, List<Suggestion>>, val drops: DropTally) : ParseOutcome()
        data class Rejected(val kind: BatchFailKind) : ParseOutcome()
    }

    companion object {

        /** 재시도해도 같은 결과인 실패 — 잔여 배치 중단 기준. 형제 서제스터와 **같은 집합**이다. */
        private val TERMINAL_ERRORS = setOf(
            AiErrorKind.NO_PROVIDER, AiErrorKind.NO_KEY, AiErrorKind.INVALID_KEY,
            AiErrorKind.QUOTA_EXCEEDED, AiErrorKind.MODEL_NOT_FOUND
        )

        /** 이미지를 요청 단위로 나눈다. 장수는 사용자 설정이고 범위는 [AiPromptPolicy]가 든다. */
        fun chunkImages(paths: List<String>, perRequest: Int): List<List<String>> =
            if (paths.isEmpty()) emptyList()
            else paths.chunked(AiPromptPolicy.clampImageTagBatch(perRequest))

        /**
         * **되받아 볼 만한 경로** — '1장씩 다시 보내기'가 무엇을 다시 부를지 정한다.
         *
         * 조건이 둘이고 **둘 다 필요하다**:
         * - 사유가 [kinds]에 있을 것 — 키 오류·할당량 소진처럼 다시 불러도 같은 답인 실패는
         *   길을 열면 사용자가 돈만 더 쓴다(호출측이 그 집합을 든다).
         * - **그 배치에 두 장 이상 실려 있었을 것** — 되받기는 1장씩 보내는 것이고, 1장이
         *   실렸던 배치를 1장으로 다시 보내는 것은 **같은 요청을 한 번 더 결제하는 것**이다.
         *   번호 사고는 여러 장이 한 요청에 실려서 나는 것이라, 애초에 성립하지 않는다.
         *
         * 판 단위 설정값(`perRequest`)이 아니라 **배치에 실렸던 장수로** 재는 이유: 되받기의
         * 결과를 [mergeRetry]로 합치고 나면 한 결과 안에 **5장짜리 배치의 실패와 1장짜리
         * 되받기의 실패가 섞인다.** 판 하나에 값 하나를 매기면 그 둘을 가를 수 없어, 되받기를
         * 또 권하거나(돈) 남은 5장짜리의 길을 닫는다(기회).
         */
        fun retryablePaths(result: Result, kinds: Set<BatchFailKind>): List<String> =
            result.failures
                .filter { it.kind in kinds && it.paths.size > 1 }
                .flatMap { it.paths }
                .distinct()

        /**
         * **'1장씩 다시 보내기'의 결과를 앞 실행 위에 얹는다** (B-140).
         *
         * [Result]가 *"끝난 배치의 제안은 살린다"*를 선언해 두고도 **화면 계층에서 뒤집히고
         * 있었다** — 되받기가 앞 실행을 통째로 갈아치워, 25장을 5요청으로 돌려 4요청이 성공한
         * 뒤 1요청만 접혔을 때 **20장분 제안이 소멸했다.** 되받으려면 이미 결제한 4요청을
         * 다시 결제해야 했다. 되받기는 접힌 배치만 다시 부르므로 **앞의 성공분은 손댈 이유가
         * 없다**(완결된 몫은 버리지 않는다 — B-108 ⓕ).
         *
         * 규칙 셋:
         * - **되받은 쪽이 이긴다** — 한 경로에 두 답이 있으면 나중 것이 그 장의 답이다.
         * - **처분은 '실제로 다시 물은 것'에만 한다** — 되받기가 중간에 취소되면 시작조차 못 한
         *   장이 남는다. 그 장의 앞선 실패까지 지우면 **되받을 길이 사라지고**(재시도 목록은
         *   실패에서 나온다) 사용자는 그 장이 성공한 줄 안다. 그래서 [retry]가 답을 낸
         *   경로(성공이든 새 실패든)만 앞 결과에서 걷어낸다.
         * - **취소는 둘 중 하나만 서도 선다** — 앞 실행이 취소돼 시작조차 못 한 배치는
         *   되받기가 성공해도 여전히 시작되지 않은 채다.
         *
         * 집계 합산은 [DropTally.mergeRetry]가 따로 든다(더하는 축과 큰 쪽을 드는 축이 갈린다).
         */
        fun mergeRetry(previous: Result, retry: Result): Result {
            // 되받기가 답을 낸 경로 — 성공했든 새로 접혔든 "다시 물어 답이 나온" 것이다.
            val answered = (retry.suggestions.map { it.path } + retry.failures.flatMap { it.paths }).toSet()

            val keptFailures = previous.failures.mapNotNull { failure ->
                val remaining = failure.paths.filterNot { it in answered }
                when {
                    remaining.isEmpty() -> null                       // 전부 다시 물었다
                    remaining.size == failure.paths.size -> failure   // 손댈 것이 없다
                    else -> failure.copy(paths = remaining)           // 일부만 다시 물었다
                }
            }
            return Result(
                suggestions = previous.suggestions.filterNot { it.path in answered } + retry.suggestions,
                drops = previous.drops.mergeRetry(retry.drops),
                failures = keptFailures + retry.failures,
                cancelled = previous.cancelled || retry.cancelled,
                // 앞 실행에서 프로바이더가 바뀌었다는 사실은 되받기로 사라지지 않는다 —
                // 살아남은 제안 중에 그 프로바이더가 낸 것이 섞여 있다(B-108 ⓑ).
                notes = (previous.notes + retry.notes).distinct()
            )
        }

        /**
         * 어휘에 같은 말이 이미 있으면 **그 표기로 접는다** — 공백·대소문자 무시 일치
         * ([CharacterFieldAiSuggester.matchOption]이 SELECT 값에 이미 쓰는 그 규칙).
         *
         * 이것이 없으면 `물의정령`과 `물의 정령`이 **두 태그로 갈려** 필터도 통계도 둘로 쪼개진다.
         * 접힌 것은 새 태그가 아니므로 `새 태그` 표식도 붙지 않는다.
         */
        fun foldToVocabulary(tag: String, vocab: List<String>): String? =
            CharacterFieldAiSuggester.matchOption(tag, vocab)

        fun buildSystemPrompt(vocab: ImageTagVocabulary.Vocabulary, policy: String): String = buildString {
            append("당신은 창작 자료 이미지의 분류를 돕는다. ")
            append("입력으로 받은 **이미지들**을 보고, 각 이미지에 어울리는 태그를 제안하라.\n")
            append("- 이미지는 보낸 순서대로 `이미지 1`, `이미지 2`…다. ")
            append("**응답의 index는 그 번호를 그대로 쓴다**(1부터 시작).\n")
            append("- 그림에서 실제로 보이는 것만 태그로 삼는다 — 인물의 외형·복장·배경·구도·분위기 같은 ")
            append("분류축이다. 보이지 않는 설정(이름·나이·관계)은 지어내지 않는다.\n")
            append("- 태그는 짧은 명사구로 한다(최대 ")
            append(AiPromptPolicy.IMAGE_TAG_MAX_LENGTH)
            append("자). 이미지당 최대 ")
            append(AiPromptPolicy.IMAGE_TAG_MAX_PER_IMAGE)
            append("개.\n")
            append("- 아래 기존 어휘에 맞는 표기가 있으면 **그것을 그대로 쓴다**(표기 일관성). ")
            append("맞는 것이 없으면 새 태그를 만들어도 된다.\n")
            append("- 근거를 찾을 수 없는 이미지는 빈 배열로 둔다. 지어내지 않는다.\n")
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
            append("""{"images":[{"index":1,"tags":["태그1","태그2"]}]}""")
        }

        /**
         * 사용자 메시지 — **번호만 말한다.**
         *
         * 파일 이름을 싣지 않는 것은 의도한 침묵이다. 이름을 주면 모델이 그림 대신 이름을 읽고
         * 답할 수 있는데(`전투씬_01.png`), 그러면 이 기능은 조용히 [ImageFolderTagSuggester]로
         * 퇴화한다 — 이미지를 보낸 값을 내지 못하면서 이미지 값만 낸다. 이름에서 뽑는 몫은
         * 그쪽 기능이 이미 한다.
         */
        fun buildUserText(count: Int): String = buildString {
            append("이미지 ")
            append(count)
            append("장을 보낸다. 순서대로 이미지 1부터 이미지 ")
            append(count)
            append("까지다. 각각에 태그를 제안하라.")
        }

        /**
         * 응답을 검증하며 읽는다. **번호 봉인이 여기 있다.**
         *
         * @param batchSize 이 요청에 실제로 실은 이미지 장수. 유효 번호는 `1..batchSize`다.
         * @param vocab 접기·`새 태그` 판정에 쓸 어휘.
         */
        fun parse(raw: String, batchSize: Int, vocab: ImageTagVocabulary.Vocabulary): ParseOutcome {
            val json = AiJsonExtractor.extractObject(raw) ?: return ParseOutcome.Rejected(BatchFailKind.NO_JSON)
            val arr = json.optJSONArray("images") ?: return ParseOutcome.Rejected(BatchFailKind.NO_JSON)

            val folding = vocab.forFolding
            val known = vocab.all
            val byIndex = LinkedHashMap<Int, List<Suggestion>>()
            var blank = 0
            var over = 0

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                // 번호가 없는 항목은 '어느 장인지 모르는 답'이다 — 범위 밖과 같은 처분.
                val index = obj.optInt("index", INDEX_ABSENT)
                if (index !in 1..batchSize) return ParseOutcome.Rejected(BatchFailKind.INDEX_OUT_OF_RANGE)
                if (byIndex.containsKey(index)) return ParseOutcome.Rejected(BatchFailKind.INDEX_DUPLICATED)

                val tagsArr = obj.optJSONArray("tags")
                val picked = LinkedHashMap<String, Suggestion>()
                if (tagsArr != null) {
                    for (t in 0 until tagsArr.length()) {
                        val raw1 = tagsArr.optString(t).trim()
                        if (raw1.isEmpty() || raw1.length > AiPromptPolicy.IMAGE_TAG_MAX_LENGTH) { blank++; continue }
                        val folded = foldToVocabulary(raw1, folding)
                        val tag = folded ?: raw1
                        // 접은 뒤에 같아진 것은 드롭이 아니다 — 같은 말을 두 번 받은 것뿐이다.
                        if (picked.containsKey(tag)) continue
                        if (picked.size >= AiPromptPolicy.IMAGE_TAG_MAX_PER_IMAGE) { over++; continue }
                        picked[tag] = Suggestion(tag, isNew = folded == null && tag !in known)
                    }
                }
                byIndex[index] = picked.values.toList()
            }
            return ParseOutcome.Ok(
                byIndex,
                DropTally(blankOrTooLong = blank, overPerImageCap = over)
            )
        }

        /** `optInt`가 "없음"과 0을 못 가르므로 범위 밖 값을 센티넬로 쓴다. */
        private const val INDEX_ABSENT = -1
    }

    /**
     * 실제로 실린 이미지 한 장 — **경로와 짝지어져 있다.**
     *
     * 짝을 유지하는 것이 이 기능의 안전장치다. 준비기가 이미지 목록과 '못 읽은 장수'만
     * 돌려주면, 배치 가운데 한 장이 빠졌을 때 응답의 `이미지 3`이 원본 3번과 어긋난다 —
     * 번호로 봉인해 놓고 **그 번호가 가리키는 목록을 흔드는** 셈이라 봉인이 무의미해진다.
     * 그래서 seam을 짝으로 정의한다: 번호는 언제나 이 목록의 자리다.
     */
    data class Loaded(val path: String, val image: AiImage)

    /**
     * 한 배치를 실은 결과.
     *
     * **뺀 장수를 사유별로 받는다** — 종전에는 [ImageLoader]가 목록만 돌려주고 [suggest]가
     * `요청 수 - 실린 수`로 뺐는데, 그 뺄셈은 사유를 하나로 뭉갠다. 사유를 아는 곳은
     * 준비기이고, 고지의 처방이 사유마다 갈리므로 아는 곳이 세어 넘긴다 (B-141).
     *
     * @property loaded 실제로 실은 (경로, 이미지) 짝. 순서가 곧 응답의 번호다.
     * @property unreadable 파일이 없거나 읽지 못해 뺀 장수.
     * @property blocked 앱 저장소 밖이라 보내지 않은 장수.
     */
    data class LoadResult(
        val loaded: List<Loaded>,
        val unreadable: Int = 0,
        val blocked: Int = 0
    )

    /**
     * 이미지를 준비해 요청에 싣는 몫은 호출측이다 — 이 클래스는 **비트맵을 만들지 않는다**
     * (순수 유지). 준비기는 `util.AiImagePreparer`이고, 호출측이 배치마다 그것을 돌린다.
     */
    fun interface ImageLoader {
        /** @return 실은 짝과 **뺀 사유별 장수**. 못 읽거나 막힌 경로는 목록에서 빠진다. */
        suspend fun prepare(paths: List<String>): LoadResult
    }

    /**
     * 제안을 받아온다. 배치 단위로 순차 실행하며 **실패한 배치는 격리**하고 성공분과 함께
     * 돌려준다.
     *
     * @param onProgress `(끝낸 요청 수, 총 요청 수, 끝낸 이미지 수, 총 이미지 수)` — 결정형
     *   진행도(R-26)의 재료다. 요청과 이미지를 **둘 다** 세는 이유는 사용자가 정하는 값이
     *   장수인데 시간이 드는 단위는 요청이라, 하나만 보이면 남은 시간을 가늠할 수 없어서다.
     * @param isCancelled 매 배치 앞에서 확인한다. 취소는 즉시 중단이 아니라 **더 시작하지 않음**이다.
     */
    suspend fun suggest(
        paths: List<String>,
        perRequest: Int,
        vocab: ImageTagVocabulary.Vocabulary,
        policyRaw: String,
        loader: ImageLoader,
        onProgress: suspend (doneRequests: Int, totalRequests: Int, doneImages: Int, totalImages: Int) -> Unit = { _, _, _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Result {
        if (paths.isEmpty()) return Result()
        val policy = AiPromptPolicy.clampImageTagPolicy(policyRaw)
        val policyTruncated = (policyRaw.trim().length - policy.length).coerceAtLeast(0)
        val system = buildSystemPrompt(vocab, policy)

        val batches = chunkImages(paths, perRequest)
        val totalRequests = batches.size
        val totalImages = paths.size

        val all = ArrayList<ImageSuggestion>()
        val failures = ArrayList<BatchFailure>()
        val notes = ArrayList<String>()
        var drops = DropTally(vocabTruncated = vocab.truncated, policyTruncated = policyTruncated)
        var doneRequests = 0
        var doneImages = 0
        var cancelled = false

        onProgress(0, totalRequests, 0, totalImages)
        for (batch in batches) {
            if (isCancelled()) { cancelled = true; break }

            val prepared = loader.prepare(batch)
            val loaded = prepared.loaded
            if (prepared.unreadable > 0 || prepared.blocked > 0) {
                drops += DropTally(unreadable = prepared.unreadable, blocked = prepared.blocked)
            }

            var stop = false
            if (loaded.isEmpty()) {
                // 한 장도 싣지 못했으면 보낼 것이 없다 — 요청을 만들지 않는다(돈만 쓴다).
                // **사유는 뭉뚱그리지 않는다** — 통째로 막힌 배치에 "읽지 못했다"고 말하면
                // 사용자는 멀쩡히 있는 파일을 찾아 헤맨다(B-141).
                val kind =
                    if (prepared.blocked > 0 && prepared.unreadable == 0) BatchFailKind.IMAGES_BLOCKED
                    else BatchFailKind.IMAGES_UNREADABLE
                failures.add(BatchFailure(batch, kind))
            } else {
                // **번호가 가리키는 목록은 `loaded`다** — 못 읽은 장은 batch에서 빠졌으므로
                // 원본 자리로 되돌리면 그것이 곧 오배정이다.
                val request = AiRequest(
                    system = system,
                    userText = buildUserText(loaded.size),
                    // 사용자가 올려 둔 출력 상한을 그대로 쓴다 — 기본값(2048)을 박아 두면
                    // 장수를 최대로 올린 사용자가 **설정을 올려도 계속 잘린다**(A-4의 교집합
                    // 규칙상 요청값은 천장이지 바닥이 아니다).
                    maxTokens = aiService.effectiveMaxTokens(),
                    images = loaded.map { it.image }
                )
                when (val result = aiService.complete(request)) {
                    is AiResult.Success -> {
                        // 한도로 밀려 다른 프로바이더가 답한 배치 (B-108 확정 ⓑ).
                        // 아래 갈래 **밖**에 적는다 — 어디로 떨어지든 *"누가 답했는가"*는 같고,
                        // 갈래마다 적으면 반드시 한쪽이 빠진다.
                        AiProviderFallback.switchNoteOf(result)
                            ?.let { if (it !in notes) notes.add(it) }
                        when {
                            // 그림이 근거의 전부인 기능이라 글만 나온 답은 쓰지 않는다.
                            result.imagesOmitted -> {
                                failures.add(BatchFailure(batch, BatchFailKind.IMAGES_UNSUPPORTED))
                                stop = true   // 학습된 사실이라 남은 배치도 같은 결과다
                            }
                            result.truncated ->
                                failures.add(BatchFailure(batch, BatchFailKind.RESPONSE_TRUNCATED))
                            else -> when (val parsed = parse(result.text, loaded.size, vocab)) {
                                is ParseOutcome.Ok -> {
                                    for ((index, tags) in parsed.tagsByIndex) {
                                        if (tags.isEmpty()) continue   // 근거 없음은 결손이 아니다
                                        val hit = loaded.getOrNull(index - 1) ?: continue
                                        all.add(ImageSuggestion(hit.path, tags))
                                    }
                                    drops += parsed.drops
                                }
                                is ParseOutcome.Rejected -> failures.add(BatchFailure(batch, parsed.kind))
                            }
                        }
                    }
                    is AiResult.Failure -> {
                        failures.add(BatchFailure(batch, BatchFailKind.REQUEST_FAILED, result))
                        stop = result.kind in TERMINAL_ERRORS
                    }
                }
            }

            doneRequests++
            doneImages += batch.size
            onProgress(doneRequests, totalRequests, doneImages, totalImages)
            if (stop) break
        }
        return Result(all, drops, failures, cancelled, notes)
    }
}
