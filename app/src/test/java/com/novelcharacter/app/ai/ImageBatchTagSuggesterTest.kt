package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.buildSystemPrompt
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.buildUserText
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.chunkImages
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.foldToVocabulary
import com.novelcharacter.app.ai.ImageBatchTagSuggester.Companion.parse
import com.novelcharacter.app.ai.ImageBatchTagSuggester.ParseOutcome
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
        val a = ImageBatchTagSuggester.DropTally(blankOrTooLong = 1, unreadable = 2)
        val b = ImageBatchTagSuggester.DropTally(blankOrTooLong = 3, overPerImageCap = 4)
        val sum = a + b
        assertEquals(4, sum.blankOrTooLong)
        assertEquals(2, sum.unreadable)
        assertEquals(4, sum.overPerImageCap)
        assertFalse(sum.isEmpty)
        assertTrue(ImageBatchTagSuggester.DropTally().isEmpty)
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
}
