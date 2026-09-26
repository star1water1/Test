package com.novelcharacter.app.ai

import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NaturalBatchPlanTest {
    private val input = NaturalBatchInput("session-1",
        NaturalBatchInput.Scope(NaturalBatchInput.ScopeKind.UNIVERSE, 7), 0, 0,
        "민아의 직업은 탐정이다. 아니, 기자다.\n\n준호는 민아보다 빠르다.")
    private val segments = input.segments()
    private val refs = NaturalBatchRefs(setOf("c1", "c2"), setOf("f1", "f2"), emptySet(), setOf("fa1"))

    private fun response(): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("sessionId", input.sessionId)
        .put("scopeRevision", input.scopeRevision).put("inputRevision", input.inputRevision)
        .put("operations", JSONArray()).put("constraints", JSONArray())
        .put("unresolved", JSONArray()).put("suggestionNotes", JSONArray())
        .put("segmentStatus", JSONArray())

    private fun operation(id: String = "op1", value: String = "기자", segment: String = segments[0].id) =
        JSONObject().put("id", id).put("kind", "SET_FIELD_VALUE")
            .put("targetRef", "c1").put("fieldRef", "f1").put("value", value)
            .put("origin", "EXTRACTED").put("segmentIds", JSONArray().put(segment))
            .put("quote", "아니, 기자다")

    private fun status(id: String, state: String, reason: String? = null) =
        JSONObject().put("segmentId", id).put("status", state).apply {
            if (reason != null) put("reason", reason)
        }

    private fun parse(root: JSONObject, requested: Set<String> = segments.map { it.id }.toSet(),
        sequence: Long = 0) =
        NaturalBatchPlanParser.parse(root.toString(), input, requested, refs, sequence)

    private fun invalid(root: JSONObject) {
        try {
            parse(root)
            fail("Expected format rejection")
        } catch (_: NaturalBatchFormatException) { }
    }

    @Test fun inputRevisionAndSegmentsPreserveCorrectionContext() {
        assertEquals(2, segments.size)
        assertTrue(segments[0].text.contains("탐정이다. 아니, 기자다."))
        assertEquals(input.text.substring(segments[1].start, segments[1].end), segments[1].text)
        assertEquals(input, input.edit(input.text))
        assertEquals(1L, input.edit("수정").inputRevision)
        assertEquals(1L, input.changeScope(NaturalBatchInput.Scope(
            NaturalBatchInput.ScopeKind.WORK, 5)).scopeRevision)
        assertFalse(NaturalBatchPlans.merge(input.copy(text = ""), emptyList()).complete)
    }

    @Test fun bulkReviewSelectionKeepsCreativeDestructiveAndConflictedItemsOff() {
        val extracted = parse(response().put("operations", JSONArray().put(operation()))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))))
            .operations.single()
        assertTrue(NaturalBatchReviewSelection.canBulkSelect(extracted, emptySet()))
        assertFalse(NaturalBatchReviewSelection.canBulkSelect(extracted.copy(
            origin = NaturalBatchPlan.Origin.CREATIVE), emptySet()))
        assertFalse(NaturalBatchReviewSelection.canBulkSelect(extracted.copy(
            kind = NaturalBatchPlan.Kind.REMOVE_FIELD_VALUE), emptySet()))
        assertFalse(NaturalBatchReviewSelection.canBulkSelect(extracted.copy(
            evidence = extracted.evidence.copy(matched = false)), emptySet()))
        assertFalse(NaturalBatchReviewSelection.canBulkSelect(extracted, setOf(extracted.id)))
        assertFalse(NaturalBatchReviewSelection.canSelect(extracted.copy(
            kind = NaturalBatchPlan.Kind.JOIN_FACTION), emptySet()))
        for (kind in NaturalBatchReviewSelection.relationshipKinds) {
            assertTrue(NaturalBatchReviewSelection.canSelect(extracted.copy(kind = kind), emptySet()))
            assertFalse(NaturalBatchReviewSelection.canBulkSelect(extracted.copy(kind = kind), emptySet()))
        }
    }

    @Test fun scopedContextJournalRestoresPairKeyedValues() {
        val context = NaturalBatchContext(
            mapOf("c1" to NaturalBatchContext.Character(10, 20, 7, "민아", listOf("미나"),
                "C-1", "첫 작품", "N-1", "U-1")),
            mapOf("f1" to NaturalBatchContext.Field(30, 7, "직업", "job", "text", "{}")),
            emptyMap(), emptyMap(), mapOf(("c1" to "f1") to "기자"), listOf("생략 1건"))
        val gson = Gson()
        val encoded = gson.toJson(NaturalBatchContextSnapshot.from(context))
        val restored = gson.fromJson(encoded, NaturalBatchContextSnapshot::class.java).restore()
        assertEquals(context, restored)
        assertEquals("기자", restored.values["c1" to "f1"])
    }

    @Test fun strictSchemaRejectsUnknownKindsTypesRefsAndStaleResponses() {
        try {
            NaturalBatchPlanParser.parse(response().toString() + response().toString(), input,
                segments.map { it.id }.toSet(), refs, 0)
            fail("Expected a single JSON object")
        } catch (_: NaturalBatchFormatException) { }
        invalid(response().put("extra", 1))
        invalid(response().put("schemaVersion", 2))
        invalid(response().put("inputRevision", 1))
        invalid(response().put("operations", JSONArray().put(operation().put("kind", "DELETE_CHARACTER"))))
        invalid(response().put("operations", JSONArray().put(operation().put("value", JSONObject.NULL))))
        invalid(response().put("operations", JSONArray().put(operation().put("value", ""))))
        invalid(response().put("operations", JSONArray().put(operation().put("origin", "MADE_UP"))))
        invalid(response().put("operations", JSONArray().put(operation().put("targetRef", "outside")
            .put("origin", "MADE_UP"))))
        invalid(response().put("operations", JSONArray().put(operation().put("segmentIds", JSONArray().put("unknown")))))
        val ambiguousRelationship = operation().put("kind", "UPDATE_RELATIONSHIP")
            .put("relatedRef", "c2").put("relationshipType", "동료")
        ambiguousRelationship.remove("fieldRef")
        ambiguousRelationship.remove("value")
        invalid(response().put("operations", JSONArray().put(ambiguousRelationship)))
        invalid(response().put("operations", JSONArray().put(operation().put("id", "same"))
            .put(operation("same"))))
    }

    @Test fun unknownReferencesStayVisibleWithoutBecomingOperations() {
        val plan = parse(response().put("operations", JSONArray()
            .put(operation().put("targetRef", "outside"))
            .put(operation("op2").put("fieldRef", "wrong")))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))))
        assertTrue(plan.operations.isEmpty())
        assertEquals(setOf("op1", "op2"), plan.unresolved.map { it.id }.toSet())
        assertTrue(plan.unresolved.all { it.segmentIds == listOf(segments[0].id) })
    }

    @Test fun longUserFieldValueIsNotArbitrarilyCutByTheAiEnvelope() {
        val longValue = "설정".repeat(7_000)
        val plan = parse(response().put("operations", JSONArray().put(operation(value = longValue)))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))))
        assertEquals(longValue, plan.operations.single().value)
    }

    @Test fun omissionsFailuresAndConflictsStayVisible() {
        val complete = parse(response().put("operations", JSONArray().put(operation()))
            .put("constraints", JSONArray().put(JSONObject().put("id", "comparison")
                .put("segmentIds", JSONArray().put(segments[1].id))
                .put("leftTargetRef", "c2").put("rightTargetRef", "c1")
                .put("fieldRef", "f2").put("comparison", "GREATER_THAN")
                .put("description", "준호는 민아보다 빠름")))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))
                .put(status(segments[1].id, "PROCESSED"))))
        assertTrue(NaturalBatchPlans.merge(input, listOf(complete)).complete)
        assertTrue(complete.operations.single().evidence.matched)

        val missing = parse(response().put("operations", JSONArray().put(operation()))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))))
        val merged = NaturalBatchPlans.merge(input, listOf(missing), setOf(segments[1].id))
        assertFalse(merged.complete)
        assertTrue(segments[1].id in merged.incompleteSegments)
        assertTrue(segments[1].id in merged.failedSegments)

        val conflict = parse(response().put("operations", JSONArray().put(operation())
            .put(operation("op2", "의사")))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))
                .put(status(segments[1].id, "IGNORED", "변경 요구 없음"))))
        val conflictMerge = NaturalBatchPlans.merge(input, listOf(conflict))
        assertTrue(conflictMerge.complete)
        assertTrue(conflictMerge.hasConflicts)
        assertEquals(conflictMerge.operations.map { it.id }.toSet(), conflictMerge.conflicts)
        val review = NaturalBatchReviewState(input)
        assertTrue(review.accept(review.beginAnalysis(), conflictMerge))
        try {
            review.confirm(conflictMerge.operations.first().id)
            fail("Conflicting operation must not be selectable")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun processedWithoutEvidenceIsIncompleteAndInventedQuoteNeedsReview() {
        val plan = parse(response().put("operations", JSONArray().put(operation().put("quote", "없는 인용")))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))
                .put(status(segments[1].id, "PROCESSED"))))
        assertFalse(plan.operations.single().evidence.matched)
        assertEquals(NaturalBatchPlan.ReviewStatus.NEEDS_REVIEW,
            NaturalBatchPlans.merge(input, listOf(plan)).let { it.status(it.operations.single()) })
        assertTrue(segments[1].id in NaturalBatchPlans.merge(input, listOf(plan)).incompleteSegments)
    }

    @Test fun retryReplacesOnlyItsSegmentAndPreservesSuccessfulWork() {
        val first = parse(response().put("operations", JSONArray().put(operation()))
            .put("unresolved", JSONArray().put(JSONObject().put("id", "old-unresolved")
                .put("segmentIds", JSONArray().put(segments[1].id))
                .put("text", "미처리").put("reason", "청크 절단")))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))
                .put(status(segments[1].id, "UNPROCESSED"))))
        val later = parse(response().put("operations", JSONArray().put(
            operation("op1", "빠름", segments[1].id).put("targetRef", "c2")
                .put("fieldRef", "f2").put("quote", segments[1].text)))
            .put("segmentStatus", JSONArray().put(status(segments[1].id, "PROCESSED"))),
            setOf(segments[1].id), sequence = 1)
        assertEquals(setOf(segments[1].id), NaturalBatchPlans.retryScope(input, listOf(first),
            setOf(segments[1].id)))
        val merged = NaturalBatchPlans.merge(input, listOf(first, later))
        assertTrue(merged.complete)
        assertFalse(merged.hasConflicts)
        assertEquals(2, merged.operations.size)
        assertEquals(2, merged.operations.map { it.id }.toSet().size)
        assertTrue(merged.unresolved.isEmpty())
        assertEquals("기자", merged.operations.first { it.fieldRef == "f1" }.value)
        assertEquals("빠름", merged.operations.first { it.fieldRef == "f2" }.value)
        assertEquals(merged, NaturalBatchPlans.merge(input, listOf(later, first)))
        val ambiguousOrder = later.copy(requestSequence = first.requestSequence)
        try {
            NaturalBatchPlans.merge(input, listOf(first, ambiguousOrder))
            fail("Overlapping requests need distinct app-issued sequence numbers")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun independentChunksMayReuseLocalIdsWithoutInventingAConflict() {
        val first = parse(response().put("operations", JSONArray().put(operation()))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))),
            setOf(segments[0].id))
        val second = parse(response().put("operations", JSONArray().put(
            operation("op1", "빠름", segments[1].id).put("targetRef", "c2")
                .put("fieldRef", "f2").put("quote", segments[1].text)))
            .put("segmentStatus", JSONArray().put(status(segments[1].id, "PROCESSED"))),
            setOf(segments[1].id))
        val merged = NaturalBatchPlans.merge(input, listOf(first, second))
        assertTrue(merged.complete)
        assertFalse(merged.hasConflicts)
        assertEquals(2, merged.operations.map { it.id }.toSet().size)
        val review = NaturalBatchReviewState(input)
        assertTrue(review.accept(review.beginAnalysis(), merged))
        merged.operations.forEach { review.confirm(it.id); review.setSelected(it.id, true) }
        assertTrue(merged.operations.all { review.isSelected(it.id) })
    }

    @Test fun retryOfCrossSegmentItemRequiresItsWholeContext() {
        val comparison = JSONObject().put("id", "constraint-1")
            .put("segmentIds", JSONArray().put(segments[0].id).put(segments[1].id))
            .put("leftTargetRef", "c2").put("rightTargetRef", "c1")
            .put("fieldRef", "f2").put("comparison", "GREATER_THAN")
            .put("description", "두 문단의 설정을 함께 해석")
        val first = parse(response().put("constraints", JSONArray().put(comparison))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))
                .put(status(segments[1].id, "UNPROCESSED"))))
        assertEquals(segments.map { it.id }.toSet(), NaturalBatchPlans.retryScope(input,
            listOf(first), setOf(segments[1].id)))
        val incompleteRetry = parse(response().put("segmentStatus", JSONArray()
            .put(status(segments[1].id, "IGNORED", "재검토 후 변경 없음"))),
            setOf(segments[1].id), sequence = 1)
        try {
            NaturalBatchPlans.merge(input, listOf(first, incompleteRetry))
            fail("Retry must not split a previously cited item")
        } catch (_: IllegalArgumentException) { }
        val fullRetry = parse(response().put("segmentStatus", JSONArray()
            .put(status(segments[0].id, "IGNORED", "재검토 후 변경 없음"))
            .put(status(segments[1].id, "IGNORED", "재검토 후 변경 없음"))),
            sequence = 1)
        val merged = NaturalBatchPlans.merge(input, listOf(first, fullRetry))
        assertTrue(merged.complete)
        assertTrue(merged.constraints.isEmpty())
        assertEquals(setOf(segments[1].id), NaturalBatchPlans.retryScope(input,
            listOf(first, fullRetry), setOf(segments[1].id)))
    }

    @Test fun inputEditInvalidatesWholePlanAndDirectEditOnlyItsOwnDecision() {
        val plan = parse(response().put("operations", JSONArray().put(operation())
            .put(operation("op2", "의사").put("fieldRef", "f2")))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))))
        val review = NaturalBatchReviewState(input)
        val request = review.beginAnalysis()
        val merged = NaturalBatchPlans.merge(input, listOf(plan))
        val firstId = merged.operations.first { it.fieldRef == "f1" }.id
        val secondId = merged.operations.first { it.fieldRef == "f2" }.id
        assertTrue(review.accept(request, merged))
        try {
            review.beginAnalysis()
            fail("A new analysis must not overwrite an active paid review")
        } catch (_: IllegalArgumentException) { }
        assertFalse(review.isSelected(firstId))
        review.confirm(firstId)
        review.confirm(secondId)
        review.setSelected(firstId, true)
        review.setSelected(secondId, true)
        assertFalse(review.accept(request, merged))
        assertTrue(review.isSelected(secondId))
        review.editProposal(firstId, "작가")
        assertFalse(review.isSelected(firstId))
        assertTrue(review.isSelected(secondId))
        val snapshot = review.snapshot()
        val restored = NaturalBatchReviewState(input)
        restored.restore(snapshot)
        assertTrue(restored.isSelected(secondId))
        assertFalse(restored.accept(request, merged))
        restored.clearAnalysis()
        assertEquals(null, restored.plan)
        assertEquals(input.text, restored.input.text)
        assertFalse(restored.isSelected(secondId))
        val nextRequest = restored.beginAnalysis()
        assertFalse(restored.accept(request, merged))
        assertTrue(restored.accept(nextRequest, merged))
        restored.editInput(input.text + " 추가")
        assertEquals(null, restored.plan)
        assertFalse(restored.isSelected(secondId))
        assertEquals(1L, restored.input.inputRevision)
        val anotherOwner = NaturalBatchReviewState(input.copy(sessionId = "another-session"))
        try {
            anotherOwner.restore(snapshot)
            fail("Review recovery must keep its owner")
        } catch (_: IllegalArgumentException) { }
    }
}
