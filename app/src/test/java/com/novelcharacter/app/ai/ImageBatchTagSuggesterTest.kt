package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.buildSystemPrompt
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.buildUserText
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.chunkImages
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.foldToVocabulary
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.parse
import com.novelcharacter.app.ai.ImageBatchTagSuggester.ParseOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이미지 일괄 AI 태깅의 순수 계약 (B-121 · 설계 feature_roadmap 2-3).
 *
 * **이 파일의 방어선은 건수가 아니라 그 안의 셋이다:**
 *
 * ① **번호가 어긋나면 배치를 통째로 접는다.** 절반만 믿고 붙이는 것이 곧 오배정이고,
 *    오배정은 생략보다 나쁘다(R-1). 이 판정이 무너지면 태그가 **남의 이미지에** 붙는데,
 *    붙고 나면 어느 것이 잘못 붙은 것인지 사용자가 알아낼 방법이 없다.
 * ② **파일 이름을 프롬프트에 싣지 않는다** — 의도적인 침묵이라 시험이 없으면 다음 사람이
 *    "이름도 주면 더 잘 맞히지 않나"로 되돌린다. 그러면 모델이 그림 대신 이름을 읽고
 *    이 기능이 조용히 폴더 제안기로 퇴화한다(이미지 값은 그대로 내면서).
 * ③ **접기 임계가 상한보다 작다** — 같아지면 검토 화면의 접기가 영원히 안 도는 죽은 길이 된다.
 */
class ImageBatchTagSuggesterTest {

    private fun vocab(vararg tags: String) = ImageTagVocabulary.Vocabulary(tags = tags.toList())

    private fun ok(raw: String, batchSize: Int, v: ImageTagVocabulary.Vocabulary = vocab()) =
        parse(raw, batchSize, v) as ParseOutcome.Ok

    private fun rejected(raw: String, batchSize: Int) =
        (parse(raw, batchSize, vocab()) as ParseOutcome.Rejected).kind

    // ── 배치 나누기 ──

    @Test fun chunkImages_splitsByUserBatchSize() {
        val paths = (1..7).map { "p$it" }
        assertEquals(listOf(3, 3, 1), chunkImages(paths, 3).map { it.size })
    }

    @Test fun chunkImages_clampsOutOfRangeBatchSize() {
        val paths = (1..5).map { "p$it" }
        // 0·음수는 하한 1로, 상한 초과는 10으로 — 슬라이더 밖 저장값이 들어와도 죽지 않는다.
        assertEquals(5, chunkImages(paths, 0).size)
        assertEquals(1, chunkImages(paths, 999).size)
    }

    @Test fun chunkImages_emptyInputMakesNoRequest() {
        assertTrue(chunkImages(emptyList(), 5).isEmpty())
    }

    @Test fun requestCount_matchesChunking() {
        // 고지와 실제가 갈리면 안 된다 — 같은 입력에 두 계산이 같은 수를 내는지 잰다.
        for (n in 1..23) for (per in 1..10) {
            assertEquals(
                "n=$n per=$per",
                chunkImages((1..n).map { "p$it" }, per).size,
                AiPromptPolicy.imageTagBatchRequestCount(n, per)
            )
        }
        assertEquals(0, AiPromptPolicy.imageTagBatchRequestCount(0, 5))
    }

    // ── ① 번호 봉인 ──

    @Test fun parse_mapsTagsByIndex() {
        val out = ok("""{"images":[{"index":2,"tags":["전투"]},{"index":1,"tags":["일상"]}]}""", 2)
        assertEquals(listOf("일상"), out.tagsByIndex[1]?.map { it.tag })
        assertEquals(listOf("전투"), out.tagsByIndex[2]?.map { it.tag })
    }

    @Test fun parse_rejectsBatchWhenIndexAboveRange() {
        assertEquals(
            BatchFailKind.INDEX_OUT_OF_RANGE,
            rejected("""{"images":[{"index":1,"tags":["a"]},{"index":4,"tags":["b"]}]}""", 3)
        )
    }

    @Test fun parse_rejectsBatchWhenIndexIsZeroOrNegative() {
        // 1부터라고 프롬프트가 못 박았다 — 0이 오면 모델이 0-based로 답한 것이고,
        // 그대로 밀어 넣으면 **전부 한 칸씩 밀린 채** 붙는다(가장 나쁜 오배정).
        assertEquals(BatchFailKind.INDEX_OUT_OF_RANGE, rejected("""{"images":[{"index":0,"tags":["a"]}]}""", 3))
        assertEquals(BatchFailKind.INDEX_OUT_OF_RANGE, rejected("""{"images":[{"index":-1,"tags":["a"]}]}""", 3))
    }

    @Test fun parse_rejectsBatchWhenIndexMissing() {
        assertEquals(BatchFailKind.INDEX_OUT_OF_RANGE, rejected("""{"images":[{"tags":["a"]}]}""", 3))
    }

    @Test fun parse_rejectsBatchWhenIndexRepeats() {
        assertEquals(
            BatchFailKind.INDEX_DUPLICATED,
            rejected("""{"images":[{"index":1,"tags":["a"]},{"index":1,"tags":["b"]}]}""", 3)
        )
    }

    @Test fun parse_rejectsWhenNoJsonOrNoImagesArray() {
        assertEquals(BatchFailKind.NO_JSON, rejected("죄송합니다, 이미지를 볼 수 없습니다.", 2))
        assertEquals(BatchFailKind.NO_JSON, rejected("""{"folders":[{"name":"a","tags":["b"]}]}""", 2))
    }

    @Test fun parse_missingIndexIsNotAFailure() {
        // 답하지 않은 장은 결손이 아니다 — "근거 없으면 빈 배열"을 프롬프트가 허용했고,
        // 빠진 것은 **오배정이 아니다**. 접는 것은 어긋난 번호뿐이다.
        val out = ok("""{"images":[{"index":2,"tags":["전투"]}]}""", 3)
        assertEquals(setOf(2), out.tagsByIndex.keys)
    }

    // ── 어휘 접기 ──

    @Test fun fold_matchesIgnoringSpaceAndCase() {
        assertEquals("물의정령", foldToVocabulary("물의 정령", listOf("물의정령")))
        assertEquals("SD", foldToVocabulary("sd", listOf("SD")))
        assertNull(foldToVocabulary("화염정령", listOf("물의정령")))
    }

    @Test fun parse_foldsToExistingSpellingAndDropsNewMark() {
        val out = ok("""{"images":[{"index":1,"tags":["물의 정령"]}]}""", 1, vocab("물의정령"))
        val tag = out.tagsByIndex.getValue(1).single()
        assertEquals("물의정령", tag.tag)
        assertFalse("접힌 것은 새 태그가 아니다", tag.isNew)
    }

    @Test fun parse_marksTagsOutsideVocabularyAsNew() {
        val out = ok("""{"images":[{"index":1,"tags":["전투","신규개념"]}]}""", 1, vocab("전투"))
        val tags = out.tagsByIndex.getValue(1)
        assertFalse(tags[0].isNew)
        assertTrue(tags[1].isNew)
    }

    @Test fun parse_foldedDuplicatesAreNotCountedAsDrops() {
        // `물의정령`과 `물의 정령`이 함께 오면 접힌 뒤 같은 말이다 — 버린 것이 아니라
        // 같은 것을 두 번 받은 것이라 드롭 집계에 넣지 않는다(고지가 거짓이 된다).
        val out = ok("""{"images":[{"index":1,"tags":["물의정령","물의 정령"]}]}""", 1, vocab("물의정령"))
        assertEquals(1, out.tagsByIndex.getValue(1).size)
        assertTrue(out.drops.isEmpty)
    }

    // ── 드롭 집계 (R-14) ──

    @Test fun parse_countsBlankAndOverlongTags() {
        val long = "가".repeat(AiPromptPolicy.IMAGE_TAG_MAX_LENGTH + 1)
        val out = ok("""{"images":[{"index":1,"tags":["","  ","$long","전투"]}]}""", 1)
        assertEquals(listOf("전투"), out.tagsByIndex.getValue(1).map { it.tag })
        assertEquals(3, out.drops.blankOrTooLong)
    }

    @Test fun parse_countsTagsOverPerImageCap() {
        val many = (1..AiPromptPolicy.IMAGE_TAG_MAX_PER_IMAGE + 3).joinToString(",") { "\"태그$it\"" }
        val out = ok("""{"images":[{"index":1,"tags":[$many]}]}""", 1)
        assertEquals(AiPromptPolicy.IMAGE_TAG_MAX_PER_IMAGE, out.tagsByIndex.getValue(1).size)
        assertEquals(3, out.drops.overPerImageCap)
    }

    @Test fun dropTally_addsComponentwise() {
        val a = ImageBatchTagSuggester.DropTally(blankOrTooLong = 1, unreadable = 2, blocked = 5)
        val b = ImageBatchTagSuggester.DropTally(blankOrTooLong = 3, overPerImageCap = 4, blocked = 2)
        val sum = a + b
        assertEquals(4, sum.blankOrTooLong)
        assertEquals(2, sum.unreadable)
        assertEquals(4, sum.overPerImageCap)
        assertEquals(7, sum.blocked)
        assertFalse(sum.isEmpty)
        assertTrue(ImageBatchTagSuggester.DropTally().isEmpty)
    }

    @Test fun dropTally_blockedAloneIsNotEmpty() {
        // **막힌 것만 있는 집계가 '빈 것'으로 접히면 고지가 통째로 사라진다** — 봉쇄는
        // 조용히 일어나는 축이라(사용자가 한 일이 없다) 이 한 줄이 유일한 알림 경로다.
        assertFalse(ImageBatchTagSuggester.DropTally(blocked = 1).isEmpty)
    }

    @Test fun dropTally_mergeRetryDoesNotInflateBlocked() {
        // 가드는 경로만 보므로 되받아도 **같은 장이 또 막힌다** — 더하면 1장이 2장이 된다.
        val tally = ImageBatchTagSuggester.DropTally(blocked = 3)
        val merged = ImageBatchTagSuggester.DropTally().mergeRetry(tally).mergeRetry(tally)
        assertEquals(3, merged.blocked)
    }

    // ── ② 프롬프트 — 무엇을 말하고 무엇을 말하지 않는가 ──

    @Test fun userText_carriesOnlyNumbering_notFileNames() {
        val text = buildUserText(3)
        assertTrue(text.contains("이미지 1"))
        assertTrue(text.contains("이미지 3"))
        // 호출측이 파일 이름을 넘길 자리 자체가 없다는 것이 이 시험의 요점이다.
        assertFalse(text.contains(".png"))
        assertFalse(text.contains("/"))
    }

    @Test fun systemPrompt_statesOneBasedNumberingAndCaps() {
        val p = buildSystemPrompt(vocab(), "")
        assertTrue("번호 규칙이 없으면 봉인이 성립하지 않는다", p.contains("index"))
        assertTrue(p.contains("1부터"))
        assertTrue(p.contains(AiPromptPolicy.IMAGE_TAG_MAX_PER_IMAGE.toString()))
        assertTrue(p.contains(AiPromptPolicy.IMAGE_TAG_MAX_LENGTH.toString()))
    }

    @Test fun systemPrompt_omitsEmptySections() {
        val p = buildSystemPrompt(vocab(), "")
        assertFalse(p.contains("[기존 이미지 태그]"))
        assertFalse(p.contains("[사용자 지침"))
    }

    @Test fun systemPrompt_carriesVocabularyAndPolicy() {
        val p = buildSystemPrompt(
            ImageTagVocabulary.Vocabulary(tags = listOf("전투"), fieldValues = listOf("은발")),
            "인물 위주로"
        )
        assertTrue(p.contains("전투"))
        assertTrue(p.contains("은발"))
        assertTrue(p.contains("인물 위주로"))
    }

    @Test fun failKinds_separateTruncationFromIndexAccidents() {
        // 잘림과 번호 사고는 처방이 다르다 — 뭉뚱그리면 고지가 틀린 곳을 고치라고 시킨다.
        // enum이 두 값을 실제로 갖는지 잰다(합치는 것이 자연스러워 보이는 자리라 못을 박는다).
        val kinds = BatchFailKind.values().toSet()
        assertTrue(BatchFailKind.RESPONSE_TRUNCATED in kinds)
        assertTrue(BatchFailKind.NO_JSON in kinds)
        assertTrue(BatchFailKind.IMAGES_UNSUPPORTED in kinds)
    }

    // ── ③ 접기 임계 ──

    @Test fun collapseThreshold_isBelowPerImageCap() {
        assertTrue(
            "접기 임계가 상한 이상이면 검토 화면의 접기가 영원히 안 돈다",
            AiPromptPolicy.IMAGE_TAG_ROW_COLLAPSE_AT < AiPromptPolicy.IMAGE_TAG_MAX_PER_IMAGE
        )
    }

    @Test fun batchSize_clampsToSliderRange() {
        assertEquals(AiPromptPolicy.IMAGE_TAG_BATCH_MIN, AiPromptPolicy.clampImageTagBatch(0))
        assertEquals(AiPromptPolicy.IMAGE_TAG_BATCH_MAX, AiPromptPolicy.clampImageTagBatch(50))
        assertEquals(5, AiPromptPolicy.clampImageTagBatch(5))
        assertTrue(
            AiPromptPolicy.IMAGE_TAG_BATCH_DEFAULT in
                AiPromptPolicy.IMAGE_TAG_BATCH_MIN..AiPromptPolicy.IMAGE_TAG_BATCH_MAX
        )
    }

    // ── ④ 되받기가 앞의 성공분을 살린다 (B-140) ──
    //
    // 이 구간의 방어선은 건수가 아니라 셋이다:
    //  ⓐ **앞의 성공분이 살아남는다** — 이것이 무너지면 25장을 5요청에 돌려 1요청만 접혔을 때
    //     20장분 제안이 사라지고, 되받으려면 성공한 4요청을 **다시 결제**해야 한다.
    //  ⓑ **다시 물은 것만 걷어낸다** — 되받기가 중간에 취소되면 시작조차 못 한 장이 남는데,
    //     그 장의 앞선 실패까지 지우면 되받을 길이 사라지고 사용자는 성공한 줄 안다.
    //  ⓒ **프롬프트가 만든 집계는 더하지 않는다** — 같은 어휘를 두 번 실어 같은 자리에서
    //     잘린 것이라, 더하면 한 번 일어난 일이 되받은 횟수만큼 부풀어 고지가 거짓말을 한다.

    private fun suggestion(path: String, vararg tags: String) =
        ImageBatchTagSuggester.ImageSuggestion(path, tags.map { ImageBatchTagSuggester.Suggestion(it, false) })

    private fun failure(kind: BatchFailKind, vararg paths: String) =
        ImageBatchTagSuggester.BatchFailure(paths.toList(), kind)

    @Test fun mergeRetry_keepsSuggestionsAlreadyPaidFor() {
        val previous = ImageBatchTagSuggester.Result(
            suggestions = listOf(suggestion("a", "숲"), suggestion("b", "밤")),
            failures = listOf(failure(BatchFailKind.INDEX_OUT_OF_RANGE, "c", "d"))
        )
        val retry = ImageBatchTagSuggester.Result(suggestions = listOf(suggestion("c", "검")))

        val merged = ImageBatchTagSuggester.mergeRetry(previous, retry)

        // ⓐ 앞 실행에서 받아 둔 a·b가 그대로 있고, 되받은 c가 더해졌다.
        assertEquals(listOf("a", "b", "c"), merged.suggestions.map { it.path })
    }

    @Test fun mergeRetry_dropsFailuresThatWereReasked() {
        val previous = ImageBatchTagSuggester.Result(
            failures = listOf(failure(BatchFailKind.INDEX_DUPLICATED, "c", "d"))
        )
        // 둘 다 다시 물었고 둘 다 답이 나왔다(하나는 성공, 하나는 새 실패).
        val retry = ImageBatchTagSuggester.Result(
            suggestions = listOf(suggestion("c", "검")),
            failures = listOf(failure(BatchFailKind.REQUEST_FAILED, "d"))
        )

        val merged = ImageBatchTagSuggester.mergeRetry(previous, retry)

        // 앞선 실패는 통째로 사라지고 되받기의 실패만 남는다 — 같은 장을 두 번 세지 않는다.
        assertEquals(1, merged.failures.size)
        assertEquals(BatchFailKind.REQUEST_FAILED, merged.failures[0].kind)
        assertEquals(listOf("d"), merged.failures[0].paths)
    }

    @Test fun mergeRetry_keepsFailureForPathsThatWereNeverReasked() {
        val previous = ImageBatchTagSuggester.Result(
            failures = listOf(failure(BatchFailKind.NO_JSON, "c", "d", "e"))
        )
        // 되받기가 c만 처리하고 취소됐다 — d·e는 **시작조차 못 했다**.
        val retry = ImageBatchTagSuggester.Result(
            suggestions = listOf(suggestion("c", "검")),
            cancelled = true
        )

        val merged = ImageBatchTagSuggester.mergeRetry(previous, retry)

        // ⓑ 앞선 실패가 d·e만 남긴 채 살아 있어야 한다. 통째로 지우면 되받을 길이 사라지고,
        //    그대로 두면 이미 성공한 c를 또 보내 돈을 쓴다.
        assertEquals(1, merged.failures.size)
        assertEquals(listOf("d", "e"), merged.failures[0].paths)
        assertTrue("취소는 둘 중 하나만 서도 선다", merged.cancelled)
    }

    @Test fun mergeRetry_laterAnswerWinsForSamePath() {
        val previous = ImageBatchTagSuggester.Result(suggestions = listOf(suggestion("a", "낡은답")))
        val retry = ImageBatchTagSuggester.Result(suggestions = listOf(suggestion("a", "새답")))

        val merged = ImageBatchTagSuggester.mergeRetry(previous, retry)

        assertEquals(1, merged.suggestions.size)
        assertEquals(listOf("새답"), merged.suggestions[0].tags.map { it.tag })
    }

    @Test fun mergeRetry_doesNotInflatePromptShapedTallies() {
        // 같은 어휘·같은 기조를 두 번 실었으니 잘린 것도 **같은 것 한 번**이다.
        val tally = ImageBatchTagSuggester.DropTally(
            blankOrTooLong = 2, overPerImageCap = 1,
            unreadable = 3, vocabTruncated = 120, policyTruncated = 40
        )
        val previous = ImageBatchTagSuggester.Result(drops = tally)
        val retry = ImageBatchTagSuggester.Result(drops = tally)

        val merged = ImageBatchTagSuggester.mergeRetry(previous, retry).drops

        // 응답이 만든 것은 더한다 — 두 실행의 응답은 서로 다른 응답이다.
        assertEquals(4, merged.blankOrTooLong)
        assertEquals(2, merged.overPerImageCap)
        // ⓒ 프롬프트·파일이 만든 것은 큰 쪽을 든다(더하면 120이 240이 된다).
        assertEquals(120, merged.vocabTruncated)
        assertEquals(40, merged.policyTruncated)
        assertEquals(3, merged.unreadable)
    }

    @Test fun mergeRetry_emptyRetryChangesNothing() {
        // 되받기가 한 건도 답을 못 냈으면 앞 결과가 **글자 그대로** 남아야 한다 —
        // 여기서 뭔가 사라지면 그것이 곧 B-140이 고친 그 손실이다.
        val previous = ImageBatchTagSuggester.Result(
            suggestions = listOf(suggestion("a", "숲")),
            failures = listOf(failure(BatchFailKind.NO_JSON, "c", "d"))
        )

        val merged = ImageBatchTagSuggester.mergeRetry(previous, ImageBatchTagSuggester.Result())

        assertEquals(previous.suggestions.map { it.path }, merged.suggestions.map { it.path })
        assertEquals(previous.failures, merged.failures)
    }

    // ── ⑤ 되받아 볼 만한 것만 되받는다 ──

    @Test fun retryablePaths_skipsSingleImageBatches() {
        val result = ImageBatchTagSuggester.Result(
            failures = listOf(
                failure(BatchFailKind.NO_JSON, "a", "b"),   // 두 장 — 번호 사고가 성립한다
                failure(BatchFailKind.NO_JSON, "c")         // 한 장 — 이미 1장씩 보낸 것이다
            )
        )

        // 1장짜리를 1장으로 다시 보내는 것은 **같은 요청을 한 번 더 결제하는 것**이다.
        assertEquals(listOf("a", "b"), ImageBatchTagSuggester.retryablePaths(result, setOf(BatchFailKind.NO_JSON)))
    }

    @Test fun retryablePaths_skipsKindsThatWouldFailAgain() {
        val result = ImageBatchTagSuggester.Result(
            failures = listOf(failure(BatchFailKind.REQUEST_FAILED, "a", "b"))
        )
        // 키 오류·할당량 소진은 1장으로 보내도 같은 답이라 길을 열면 돈만 더 쓴다.
        assertTrue(
            ImageBatchTagSuggester.retryablePaths(result, setOf(BatchFailKind.NO_JSON)).isEmpty()
        )
    }

    @Test fun retryablePaths_afterMergeOffersOnlyTheMultiImageLeftovers() {
        // 합친 결과에는 **5장짜리 배치의 실패와 1장짜리 되받기의 실패가 섞인다.**
        // 판 단위 설정값 하나로는 그 둘을 가를 수 없다 — 배치에 실렸던 장수로 재야 한다.
        val previous = ImageBatchTagSuggester.Result(
            failures = listOf(
                failure(BatchFailKind.NO_JSON, "a", "b", "c"),
                failure(BatchFailKind.NO_JSON, "d", "e")
            )
        )
        val retry = ImageBatchTagSuggester.Result(
            suggestions = listOf(suggestion("a", "숲"), suggestion("b", "밤")),
            failures = listOf(failure(BatchFailKind.NO_JSON, "c")),   // 1장씩 보낸 되받기의 실패
            cancelled = true                                          // d·e는 시작 못 했다
        )

        val merged = ImageBatchTagSuggester.mergeRetry(previous, retry)
        val retryable = ImageBatchTagSuggester.retryablePaths(merged, setOf(BatchFailKind.NO_JSON))

        // d·e만 다시 권한다 — c는 이미 1장으로 보내 봤으므로 또 권하면 돈만 쓴다.
        assertEquals(listOf("d", "e"), retryable)
    }

    // ── ④ 버릴 답에 과금하지 않는다 (B-157) ──
    //
    // **이 절의 증명은 반환값이 아니라 '죽지 않았다'는 사실이다.** 하네스의 AiService 스텁은
    // `complete`가 예외를 던지므로, 요청을 만들면 시험이 그 자리에서 죽는다. 가짜 성공을
    // 돌려주는 스텁이었다면 *"요청은 갔지만 결과가 같다"*와 구별할 수 없었다.

    private fun suggester(imagesUnsupported: Boolean) =
        ImageBatchTagSuggester(AiService().apply { this.imagesUnsupported = imagesUnsupported })

    /** 준비기가 불렸는지까지 센다 — 보내지 않을 그림을 디코드하는 비용도 함께 없어져야 한다. */
    private class CountingLoader : ImageBatchTagSuggester.ImageLoader {
        var calls = 0
        override suspend fun prepare(paths: List<String>): ImageBatchTagSuggester.LoadResult {
            calls++
            return ImageBatchTagSuggester.LoadResult(
                loaded = paths.map { ImageBatchTagSuggester.Loaded(it, AiImage(base64 = "ZmFrZQ==")) }
            )
        }
    }

    @Test fun suggest_foldsWithoutRequestWhenModelIsKnownToRejectImages() = runBlocking {
        val loader = CountingLoader()
        val result = suggester(imagesUnsupported = true).suggest(
            paths = listOf("a", "b", "c"), perRequest = 2, vocab = vocab(), policyRaw = "",
            loader = loader
        )

        // 첫 배치를 접고 멈춘다 — 학습된 사실이라 남은 배치도 같은 결과다.
        assertEquals(1, result.failures.size)
        assertEquals(BatchFailKind.IMAGES_UNSUPPORTED, result.failures.first().kind)
        assertEquals(listOf("a", "b"), result.failures.first().paths)
        assertTrue(result.suggestions.isEmpty())
        // 준비기조차 부르지 않는다.
        assertEquals(0, loader.calls)
    }

    @Test fun suggest_stillTriesOnceWhenNothingIsLearnedYet() = runBlocking {
        // **배우는 유일한 경로가 그 첫 실행이다** — 가드가 학습 전까지 막으면 영영 못 배운다.
        // 그래서 여기서는 요청 경로로 들어가고, 스텁의 `complete`가 던져 그 사실이 드러난다.
        val loader = CountingLoader()
        val threw = runCatching {
            suggester(imagesUnsupported = false).suggest(
                paths = listOf("a"), perRequest = 1, vocab = vocab(), policyRaw = "", loader = loader
            )
        }.exceptionOrNull()

        assertTrue("학습 전에는 요청 경로로 가야 한다", threw is UnsupportedOperationException)
        assertEquals(1, loader.calls)
    }

    @Test fun suggest_reportsProgressForTheFoldedBatch() = runBlocking {
        // 접힌 배치도 진행도에서는 '끝난 요청'이다 — 세지 않으면 눈금이 총계에 닿지 못하고
        // 멈춘 것처럼 보인다(R-26의 결정형 진행도가 깨진다).
        val seen = ArrayList<Pair<Int, Int>>()
        suggester(imagesUnsupported = true).suggest(
            paths = listOf("a", "b"), perRequest = 1, vocab = vocab(), policyRaw = "",
            loader = CountingLoader(),
            onProgress = { done, total, _, _ -> seen.add(done to total) }
        )
        assertEquals(listOf(0 to 2, 1 to 2), seen)
    }
}
