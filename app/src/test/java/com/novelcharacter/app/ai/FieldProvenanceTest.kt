package com.novelcharacter.app.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import com.novelcharacter.app.data.model.FieldType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FieldProvenanceTest {
    private val spec = FieldSpec("rank", "등급", FieldType.SELECT, listOf("S", "A"), false, null, "")
    private val brief = "세계관에서 꽤 강한 편이다."
    private fun receipt(source: AiInputSource = AiInputSource(brief, emptyMap(), emptyList()),
        text: List<String> = listOf(brief), images: Int = 0) = AiInputReceipt("r", "test", AiProtocol.OPENAI_COMPAT,
        "hash", text, source, images, AiInputBudget())
    private fun value(origin: List<String>? = listOf("INTERPRETED"), quote: String? = brief,
        source: String? = "BRIEF") = Suggestion("rank", "S", "맥락 해석", Confidence.HIGH,
        inputReceiptId = "r", provenance = FieldProvenance.Claim(origin, source, quote, false))
    private fun assess(value: Suggestion = value(), receipt: AiInputReceipt? = receipt(), target: FieldSpec? = spec) =
        FieldProvenance.assess(value, target, receipt)

    @Test fun backedInterpretationCanBeSelectedWithoutClaimingDirectExtraction() {
        val result = assess()
        assertTrue(result.defaultOn); assertEquals("원문 인용 있음 · AI 해석", result.label)
        assertTrue(result.explanation.contains("의미 일치"))
    }
    @Test fun directSelfReportCannotCertifyMappingEvenWithAnExactQuote() {
        val result = assess(value(listOf("DIRECT")))
        assertFalse(result.defaultOn); assertFalse(result.label.contains("직접"))
        assertTrue(result.explanation.contains("모델 표시만"))
    }
    @Test fun directWithNoQuoteOrInventedQuoteIsNotVerified() {
        listOf(null, "원문에 없는 말").forEach {
            val result = assess(value(listOf("DIRECT"), it))
            assertFalse(result.defaultOn); assertTrue(result.evidence.isEmpty())
        }
    }
    @Test fun partialNegationCorrectionRangeAndUnrelatedQuotesNeverProveMeaning() {
        listOf("고양이를 좋아하지 않는다" to "고양이", "흑발, 아니 은발 후보" to "흑발",
            "키는 165~170 정도" to "165", "아마 강할 수도 있다" to "강할",
            "푸른 눈동자" to "눈동자").forEach { (source, quote) ->
            val result = assess(value(listOf("DIRECT"), quote),
                receipt(AiInputSource(source, emptyMap(), emptyList()), listOf(source)))
            assertFalse(result.defaultOn); assertFalse(result.label.contains("직접"))
            assertEquals(source, result.evidence.single().context)
        }
    }
    @Test fun instructionEvidenceBelongsToThatFieldAndThatDispatch() {
        val source = AiInputSource(brief, mapOf("rank" to "등급은 A 후보"), emptyList())
        val sent = receipt(source, listOf(brief, "등급은 A 후보"))
        val v = value(quote = "등급은 A 후보", source = "INSTRUCTION").copy(value = "A")
        assertTrue(assess(v, sent).defaultOn)
        assertFalse(assess(v.copy(fieldKey = "other"), sent, spec.copy(key = "other")).defaultOn)
        assertFalse(assess(value(quote = "현재 편집기의 새 문장"), sent).defaultOn)
    }
    @Test fun actualWireMustContainTheWholeRecordedMaterial() {
        val forged = receipt(AiInputSource(brief, emptyMap(), emptyList()), listOf("다른 자료"))
        assertFalse(assess(receipt = forged).defaultOn)
        assertFalse(assess(receipt = receipt(text = listOf("세계관에서"))).defaultOn)
        assertFalse(assess(receipt = receipt().copy(id = "another request")).defaultOn)
    }
    @Test fun jsonEscapedBriefIsCheckedAgainstTheActualTextBlock() {
        val source = "첫 줄\n두 번째 \"인용\""
        val wire = JSONObject().put("briefing", source).toString()
        assertTrue(assess(value(quote = source), receipt(AiInputSource(source, emptyMap(), emptyList()), listOf(wire))).defaultOn)
    }
    @Test fun contextMustHaveBeenIncludedRatherThanMerelyExistingInDatabase() {
        val source = AiInputSource("", emptyMap(), listOf("메모 제외"), listOf("등급 후보 S"))
        val v = value(quote = "등급 후보 S", source = "CONTEXT")
        assertTrue(assess(v, receipt(source, listOf("등급 후보 S"))).defaultOn)
        assertFalse(assess(v, receipt(source, listOf("다른 자료"))).defaultOn)
        assertFalse(assess(v, receipt(source.copy(contextText = null), listOf("등급 후보 S"))).defaultOn)
    }
    @Test fun imagesOnlyProveTransmissionNotThatTheValueCameFromThem() {
        val v = value(source = "IMAGE")
        val sent = assess(v, receipt(images = 2))
        val omitted = assess(v, receipt(images = 0))
        assertFalse(sent.defaultOn); assertFalse(omitted.defaultOn)
        assertTrue(sent.label.contains("전송됨")); assertTrue(omitted.label.contains("확인 불가"))
        assertTrue(sent.dispatch.contains("2장")); assertTrue(omitted.dispatch.contains("0장"))
    }
    @Test fun sourceClaimsCannotBorrowEvidenceFromAnotherSource() {
        assertFalse(assess(value(source = "CONTEXT")).defaultOn)
        assertFalse(assess(value(source = "MADE_UP")).defaultOn)
    }
    @Test fun everyCreativeMixtureIsOffButDirectAndInterpretationCanBeOn() {
        listOf(listOf("CREATIVE"), listOf("DIRECT", "CREATIVE"), listOf("INTERPRETED", "CREATIVE"),
            listOf("MIXED"), listOf("CREATIVE", "UNKNOWN")).forEach {
            val result = assess(value(it)); assertFalse(result.defaultOn); assertTrue(result.label.contains("창작"))
        }
        val interpretedMix = assess(value(listOf("DIRECT", "INTERPRETED")))
        assertTrue(interpretedMix.defaultOn); assertTrue(interpretedMix.label.contains("혼합"))
    }
    @Test fun existingValuesInvalidOptionsLowConfidenceAndMissingFieldsAreOff() {
        assertFalse(assess(target = spec.copy(currentValue = "A")).defaultOn)
        assertFalse(assess(target = spec.copy(options = listOf("A"))).defaultOn)
        assertFalse(assess(value().copy(confidence = Confidence.LOW)).defaultOn)
        assertFalse(assess(target = null).defaultOn)
    }
    @Test fun legacyAndMalformedMetadataNeverDiscardAValidValue() {
        listOf("", ",\"origin\":null", ",\"origin\":\"NEW_KIND\"", ",\"origin\":42",
            ",\"origin\":{}", ",\"origin\":[\"INTERPRETED\",5]", ",\"sourceEvidence\":{}",
            ",\"origin\":\"INTERPRETED\",\"sourceType\":false").forEach { metadata ->
            val parsed = CharacterFieldAiSuggester.parseResponse(
                """{"suggestions":[{"key":"rank","value":"S","reason":"이유"$metadata}]}""", listOf(spec))!!
            assertEquals("S", parsed.suggestions.single().value)
            assertFalse(assess(parsed.suggestions.single()).defaultOn)
        }
    }
    @Test fun validMetadataStillUsesAppReceiptIdentity() {
        val parsed = CharacterFieldAiSuggester.parseResponse(
            """{"suggestions":[{"key":"rank","value":"S","origin":"INTERPRETED","sourceType":"BRIEF","sourceEvidence":"$brief","inputReceiptId":"r"}]}""", listOf(spec))!!
        assertNull(parsed.suggestions.single().inputReceiptId)
        assertFalse(assess(parsed.suggestions.single()).defaultOn)
        assertTrue(assess(parsed.suggestions.single().copy(inputReceiptId = "r")).defaultOn)
    }
    @Test fun policyPreservesExplicitChecksAcrossRerenderButNewCreativeValueNeedsReview() {
        val state = FieldSuggestionReviewState(); val a = value()
        state.syncSelection(a, true); assertTrue(state.isChecked("rank"))
        state.setChecked("rank", false); state.syncSelection(a, true); assertFalse(state.isChecked("rank"))
        state.setChecked("rank", true)
        val b = value(listOf("CREATIVE")).copy(inputReceiptId = "new")
        state.syncSelection(b, false); assertFalse(state.isChecked("rank")); assertTrue(state.needsSelectionReview("rank"))
        state.setChecked("rank", true); state.syncSelection(b, false)
        assertTrue(state.isChecked("rank")); assertFalse(state.needsSelectionReview("rank"))
        state.syncSelection(b.copy(inputReceiptId = "another creative"), false)
        assertTrue(state.needsSelectionReview("rank"))
        state.syncSelection(a.copy(inputReceiptId = "another safe"), true)
        assertTrue(state.isChecked("rank")); assertFalse(state.needsSelectionReview("rank"))
    }
    @Test fun explicitOffSurvivesNewSafeValuesAndFreshRestore() {
        val state = FieldSuggestionReviewState(); state.syncSelection(value(), true); state.setChecked("rank", false)
        val restored = FieldSuggestionReviewState().apply {
            restore(Gson().fromJson(Gson().toJson(state.snapshot()), FieldSuggestionReviewState.Snapshot::class.java))
        }
        restored.syncSelection(value().copy(value = "A", inputReceiptId = "new"), true)
        assertFalse(restored.isChecked("rank"))
    }
    @Test fun manuallyEditedValueKeepsItsChoiceAndRawClaimThroughJson() {
        val state = FieldSuggestionReviewState(); state.syncSelection(value(), true)
        val edit = value().copy(value = "A", editedByUser = true)
        state.remember(edit); state.syncSelection(edit, false); assertTrue(state.isChecked("rank"))
        val recovered = Gson().fromJson(Gson().toJson(edit), Suggestion::class.java)
        assertEquals(edit, recovered)
        val legacy = JsonParser.parseString(Gson().toJson(edit)).asJsonObject.apply { remove("provenance") }
        assertNull(Gson().fromJson(legacy, Suggestion::class.java).provenance)
    }
    @Test fun heldCreativeCandidateRechecksOnlyWhenAdopted() {
        val state = FieldSuggestionReviewState(); val a = value()
        state.current(a); state.syncSelection(a, true)
        val request = state.beginRequest(listOf("rank"), listOf(a))
        state.setDraft("rank", "미완성")
        val b = value(listOf("CREATIVE")).copy(value = "A", inputReceiptId = "r2")
        state.receive(request, SuggestOutcome(listOf(b), 0, emptyList(), emptyList(), 0, 0))
        state.syncSelection(state.current(b), true); assertTrue(state.isChecked("rank"))
        state.adopt(state.candidates("rank").single().id)
        state.syncSelection(state.current(b), false); assertFalse(state.isChecked("rank"))
    }
    @Test fun contextManifestTracksActualCustomTemplateMaterialsOnBothAxes() {
        val character = CharacterAiContext("서린", emptyList(), emptyList(), "빠질 메모",
            listOf("특징" to "포함할 자료"), emptyList(), emptyList(), emptyList(), briefing = brief)
        val minimal = "사용자 문체\n{{추천할필드}}"
        val omitted = CharacterFieldAiSuggester.buildUserPrompt(character, listOf(spec), minimal)
        assertTrue(omitted.contextText.orEmpty().isEmpty())
        assertFalse(omitted.text.contains("빠질 메모")); assertTrue(omitted.text.contains(brief))
        val included = CharacterFieldAiSuggester.buildUserPrompt(character, listOf(spec), "{{메모}}\n{{추천할필드}}")
        assertEquals(listOf("빠질 메모"), included.contextText)
        val event = EventFieldAiSuggester.EventAiContext("사건 내용", "오늘", novels = listOf("제외된 작품"))
        val eventPrompt = EventFieldAiSuggester.buildUserPrompt(event, listOf(spec), emptySet(),
            "{{사건설명}}\n{{관련작품}}\n{{추천할필드}}")
        assertTrue("사건 내용" in eventPrompt.contextText.orEmpty())
        assertFalse(eventPrompt.contextText.orEmpty().any { it.contains("제외된 작품") })
    }
    @Test fun userConfirmedDirectIsBoundToExactValueAndReceiptAndCannotBlessCreativeOrMissingEvidence() {
        val state = FieldSuggestionReviewState(); val direct = value(listOf("DIRECT"))
        state.confirmDirect(direct)
        val result = FieldProvenance.assess(direct, spec, receipt(), state.isDirectConfirmed(direct))
        assertEquals("직접 언급 · 사용자 확인", result.label); assertTrue(result.defaultOn)
        val restored = FieldSuggestionReviewState().apply {
            restore(Gson().fromJson(Gson().toJson(state.snapshot()), FieldSuggestionReviewState.Snapshot::class.java))
        }
        assertTrue(restored.isDirectConfirmed(direct))
        assertFalse(restored.isDirectConfirmed(direct.copy(value = "A")))
        assertFalse(restored.isDirectConfirmed(direct.copy(inputReceiptId = "later")))
        assertFalse(FieldProvenance.assess(value(listOf("CREATIVE")), spec, receipt(), true).defaultOn)
        assertFalse(FieldProvenance.assess(direct, spec, null, true).defaultOn)
        restored.remember(direct.copy(value = "A", editedByUser = true))
        assertFalse(restored.isDirectConfirmed(direct))
    }
    @Test fun confirmingDuringARequestKeepsThatValueWhenTheResponseArrives() {
        val state = FieldSuggestionReviewState(); val direct = value(listOf("DIRECT"))
        state.current(direct); state.syncSelection(direct, false)
        val request = state.beginRequest(listOf("rank"), listOf(direct))
        state.confirmDirect(direct); state.setChecked("rank", true)
        val newer = value(listOf("CREATIVE")).copy(value = "A", inputReceiptId = "later")
        state.receive(request, SuggestOutcome(listOf(newer), 0, emptyList(), emptyList(), 0, 0))
        assertEquals(direct, state.current(newer)); assertTrue(state.isDirectConfirmed(direct))
        assertEquals(newer, state.candidates("rank").single().suggestion)
    }
}
