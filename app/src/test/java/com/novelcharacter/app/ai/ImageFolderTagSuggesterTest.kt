package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.ImageFolderTagSuggester.Companion.buildSystemPrompt
import com.novelcharacter.app.ai.ImageTagVocabulary.build as buildVocabulary
import com.novelcharacter.app.ai.ImageFolderTagSuggester.Companion.chunkFolders
import com.novelcharacter.app.ai.ImageFolderTagSuggester.Companion.parse
import com.novelcharacter.app.data.model.FieldValueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 이미지 태그 제안의 순수 계약 (설계 image_folder_tag_ai 3장).
 *
 * 핵심: **요청하지 않은 폴더는 통과하지 못한다**(환각 차단), **어휘 밖의 새 태그는 버리지 않고
 * 표시한다**(어휘는 참고이지 허용 목록이 아니다), **드롭은 전부 사유별로 센다**(R-14·R-17).
 */
class ImageFolderTagSuggesterTest {

    private fun entry(value: String, count: Int = 0) =
        FieldValueEntry(fieldDefinitionId = 1L, value = value, usageCount = count)

    /** 접기 대조와 `새 태그` 판정에 함께 쓰는 어휘 — 태그 자리에 넣는다(필드 값보다 앞선다). */
    private fun vocab(vararg tags: String) = ImageTagVocabulary.Vocabulary(tags = tags.toList())

    // ── 어휘 조립 ──

    @Test fun buildVocabulary_ordersTagsByFrequency() {
        val v = buildVocabulary(mapOf("흑발" to 2, "전투" to 9, "일상" to 5), emptyList())
        assertEquals(listOf("전투", "일상", "흑발"), v.tags)
        assertEquals(0, v.truncated)
    }

    @Test fun buildVocabulary_dropsBlankTags() {
        val v = buildVocabulary(mapOf("전투" to 1, "" to 9, "   " to 3), emptyList())
        assertEquals(listOf("전투"), v.tags)
    }

    /** 태그와 겹치는 필드 값은 두 번 싣지 않는다 — 토큰 낭비이지 잘라낸 것이 아니다. */
    @Test fun buildVocabulary_deduplicatesFieldValuesAgainstTags() {
        val v = buildVocabulary(mapOf("흑발" to 3), listOf(listOf(entry("흑발"), entry("은발"))))
        assertEquals(listOf("흑발"), v.tags)
        assertEquals(listOf("은발"), v.fieldValues)
        assertEquals(0, v.truncated)
    }

    /** 상한을 넘긴 어휘는 조용히 사라지지 않고 개수로 남는다(R-14). */
    @Test fun buildVocabulary_reportsTruncatedCount() {
        val many = (1..AiPromptPolicy.IMAGE_TAG_VOCAB_MAX + 7).associate { "태그$it" to it }
        val v = buildVocabulary(many, emptyList())
        assertEquals(AiPromptPolicy.IMAGE_TAG_VOCAB_MAX, v.tags.size)
        assertEquals(7, v.truncated)
    }

    @Test fun buildVocabulary_capsTotalFieldValues() {
        val fields = (1..20).map { f -> (1..30).map { entry("v$f-$it", it) } }
        val v = buildVocabulary(emptyMap(), fields)
        assertTrue(v.fieldValues.size <= AiPromptPolicy.IMAGE_TAG_VALUES_TOTAL_MAX)
        assertTrue("자른 것은 세어야 한다", v.truncated > 0)
    }

    @Test fun vocabularyAll_unionsBothSources() {
        val v = buildVocabulary(mapOf("전투" to 1), listOf(listOf(entry("은발"))))
        assertEquals(setOf("전투", "은발"), v.all)
    }

    // ── 프롬프트 ──

    @Test fun systemPrompt_omitsEmptySections() {
        val p = buildSystemPrompt(ImageTagVocabulary.Vocabulary(), "")
        assertFalse(p.contains("[기존 이미지 태그]"))
        assertFalse(p.contains("[작품 데이터에서 쓰는 값]"))
        assertFalse(p.contains("[사용자 지침"))
    }

    @Test fun systemPrompt_includesPolicyWhenPresent() {
        val v = buildVocabulary(mapOf("전투" to 1), listOf(listOf(entry("은발"))))
        val p = buildSystemPrompt(v, "복장 위주로")
        assertTrue(p.contains("[기존 이미지 태그]"))
        assertTrue(p.contains("[작품 데이터에서 쓰는 값]"))
        assertTrue(p.contains("복장 위주로"))
    }

    // ── 청킹 ──

    @Test fun chunkFolders_splitsByPolicyLimit() {
        val n = AiPromptPolicy.IMAGE_TAG_FOLDERS_PER_REQUEST * 2 + 3
        val chunks = chunkFolders((1..n).map { "f$it" })
        assertEquals(3, chunks.size)
        assertEquals(AiPromptPolicy.imageTagRequestCount(n), chunks.size)
    }

    @Test fun requestCount_isZeroForNoFolders() {
        assertEquals(0, AiPromptPolicy.imageTagRequestCount(0))
        assertEquals(emptyList<List<String>>(), chunkFolders(emptyList()))
    }

    // ── 파싱·검증 ──

    @Test fun parse_acceptsRequestedFolders() {
        val (out, drops) = parse(
            """{"folders":[{"name":"여행","tags":["풍경","일상"]}]}""",
            listOf("여행"), vocab("풍경")
        )
        assertEquals(1, out.size)
        assertEquals("여행", out[0].folder)
        assertEquals(listOf("풍경", "일상"), out[0].tags.map { it.tag })
        assertTrue(drops.isEmpty)
    }

    /** 어휘 밖의 태그는 **버리지 않고 표시**한다 — 어휘는 참고이지 허용 목록이 아니다. */
    @Test fun parse_marksTagsOutsideVocabularyAsNew() {
        val (out, _) = parse(
            """{"folders":[{"name":"여행","tags":["풍경","낯선것"]}]}""",
            listOf("여행"), vocab("풍경")
        )
        assertEquals(listOf(false, true), out[0].tags.map { it.isNew })
    }

    // ── 어휘 접기 (B-127 — 확정 14장 8번 ⓐ) ──

    /**
     * **폴더판도 배치판과 같이 접는다** — 공백·대소문자 무시 일치면 기존 표기로 바꿔 단다.
     *
     * 종전에는 이쪽만 정확 일치(`it !in vocab`)라 `물의 정령`이 `물의정령`을 두고도 새 태그가
     * 됐다. 그러면 **같은 앱의 두 AI 태깅 경로가 같은 말에 다른 태그를 만든다** — 필터도
     * 통계도 그 지점에서 둘로 갈린다.
     */
    @Test fun parse_foldsTagToExistingVocabularyNotation() {
        val (out, drops) = parse(
            """{"folders":[{"name":"여행","tags":["물의 정령","SD"]}]}""",
            listOf("여행"), vocab("물의정령", "sd")
        )
        assertEquals(listOf("물의정령", "sd"), out[0].tags.map { it.tag })
        assertEquals(listOf(false, false), out[0].tags.map { it.isNew })
        assertTrue(drops.isEmpty)
    }

    /** 접을 상대가 없으면 답한 표기 그대로 남고 `새 태그`다 — 어휘는 허용 목록이 아니다. */
    @Test fun parse_keepsUnfoldableTagAsNew() {
        val (out, _) = parse(
            """{"folders":[{"name":"여행","tags":["화염 정령"]}]}""",
            listOf("여행"), vocab("물의정령")
        )
        assertEquals(listOf("화염 정령"), out[0].tags.map { it.tag })
        assertEquals(listOf(true), out[0].tags.map { it.isNew })
    }

    /** 접은 뒤에 같아진 둘은 드롭이 아니다 — 같은 말을 두 번 받은 것뿐이다(배치판과 같은 처분). */
    @Test fun parse_collapsedDuplicateIsNotADrop() {
        val (out, drops) = parse(
            """{"folders":[{"name":"여행","tags":["물의 정령","물의정령"]}]}""",
            listOf("여행"), vocab("물의정령")
        )
        assertEquals(listOf("물의정령"), out[0].tags.map { it.tag })
        assertTrue(drops.isEmpty)
    }

    /** 어휘의 필드 값 쪽으로도 접힌다 — `forFolding`이 태그+필드값이기 때문. */
    @Test fun parse_foldsToFieldValuePartOfVocabulary() {
        val v = ImageTagVocabulary.Vocabulary(tags = emptyList(), fieldValues = listOf("흑발"))
        val (out, _) = parse(
            """{"folders":[{"name":"인물","tags":["흑 발"]}]}""",
            listOf("인물"), v
        )
        assertEquals(listOf("흑발"), out[0].tags.map { it.tag })
        assertFalse(out[0].tags[0].isNew)
    }

    /** 요청하지 않은 폴더는 환각이다 — 통과시키지 않고 센다. */
    @Test fun parse_dropsUnknownFolderAndCounts() {
        val (out, drops) = parse(
            """{"folders":[{"name":"여행","tags":["풍경"]},{"name":"없는폴더","tags":["x"]}]}""",
            listOf("여행"), vocab()
        )
        assertEquals(1, out.size)
        assertEquals(1, drops.unknownFolder)
    }

    /** 같은 폴더를 두 번 되돌리면 뒤엣것은 버린다 — 어느 쪽이 옳은지 알 수 없다. */
    @Test fun parse_dropsDuplicateFolder() {
        val (out, drops) = parse(
            """{"folders":[{"name":"여행","tags":["a"]},{"name":"여행","tags":["b"]}]}""",
            listOf("여행"), vocab()
        )
        assertEquals(1, out.size)
        assertEquals(listOf("a"), out[0].tags.map { it.tag })
        assertEquals(1, drops.unknownFolder)
    }

    @Test fun parse_dropsBlankAndOverlongTags() {
        val long = "가".repeat(AiPromptPolicy.IMAGE_TAG_MAX_LENGTH + 1)
        val (out, drops) = parse(
            """{"folders":[{"name":"여행","tags":["  ","$long","풍경"]}]}""",
            listOf("여행"), vocab()
        )
        assertEquals(listOf("풍경"), out[0].tags.map { it.tag })
        assertEquals(2, drops.blankOrTooLong)
    }

    @Test fun parse_capsTagsPerFolderAndCounts() {
        val tags = (1..AiPromptPolicy.IMAGE_TAG_MAX_PER_FOLDER + 3).joinToString(",") { "\"t$it\"" }
        val (out, drops) = parse(
            """{"folders":[{"name":"여행","tags":[$tags]}]}""",
            listOf("여행"), vocab()
        )
        assertEquals(AiPromptPolicy.IMAGE_TAG_MAX_PER_FOLDER, out[0].tags.size)
        assertEquals(3, drops.overPerFolderCap)
    }

    /** 근거 없음(빈 배열)은 결손이 아니다 — 프롬프트가 명시적으로 허용한 답이다. */
    @Test fun parse_emptyTagsIsNotADrop() {
        val (out, drops) = parse("""{"folders":[{"name":"여행","tags":[]}]}""", listOf("여행"), vocab())
        assertTrue(out.isEmpty())
        assertTrue(drops.isEmpty)
    }

    @Test fun parse_toleratesGarbage() {
        val (out, drops) = parse("설명문만 있고 JSON이 없다", listOf("여행"), vocab())
        assertTrue(out.isEmpty())
        assertTrue(drops.isEmpty)
    }

    /** 코드펜스로 감싼 응답도 읽는다(AiJsonExtractor 재사용 확인). */
    @Test fun parse_readsFencedJson() {
        val (out, _) = parse(
            "```json\n{\"folders\":[{\"name\":\"여행\",\"tags\":[\"풍경\"]}]}\n```",
            listOf("여행"), vocab()
        )
        assertEquals(1, out.size)
    }

    // ── 기조 절단 ──

    @Test fun clampPolicy_trimsAndCaps() {
        assertEquals("", AiPromptPolicy.clampImageTagPolicy(null))
        assertEquals("복장 위주", AiPromptPolicy.clampImageTagPolicy("  복장 위주  "))
        val long = "가".repeat(AiPromptPolicy.IMAGE_TAG_POLICY_MAX_CHARS + 50)
        assertEquals(AiPromptPolicy.IMAGE_TAG_POLICY_MAX_CHARS, AiPromptPolicy.clampImageTagPolicy(long).length)
    }

    // ── 드롭 집계 ──

    @Test fun dropTally_addsPerAxis() {
        val a = ImageFolderTagSuggester.DropTally(unknownFolder = 1, blankOrTooLong = 2)
        val b = ImageFolderTagSuggester.DropTally(blankOrTooLong = 3, overPerFolderCap = 4)
        val sum = a + b
        assertEquals(1, sum.unknownFolder)
        assertEquals(5, sum.blankOrTooLong)
        assertEquals(4, sum.overPerFolderCap)
        assertFalse(sum.isEmpty)
    }
}
