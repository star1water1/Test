package com.novelcharacter.app.ai

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

    private fun parse(root: JSONObject) = NaturalBatchPlanParser.parse(root.toString(), input,
        segments.map { it.id }.toSet(), refs)

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

    @Test fun strictSchemaRejectsUnknownKindsTypesRefsAndStaleResponses() {
        try {
            NaturalBatchPlanParser.parse(response().toString() + response().toString(), input,
                segments.map { it.id }.toSet(), refs)
            fail("Expected a single JSON object")
        } catch (_: NaturalBatchFormatException) { }
        invalid(response().put("extra", 1))
        invalid(response().put("schemaVersion", 2))
        invalid(response().put("inputRevision", 1))
        invalid(response().put("operations", JSONArray().put(operation().put("kind", "DELETE_CHARACTER"))))
        invalid(response().put("operations", JSONArray().put(operation().put("value", JSONObject.NULL))))
        invalid(response().put("operations", JSONArray().put(operation().put("origin", "MADE_UP"))))
        invalid(response().put("operations", JSONArray().put(operation().put("segmentIds", JSONArray().put("unknown")))))
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
        assertEquals(setOf("op1", "op2"), conflictMerge.conflicts)
        val review = NaturalBatchReviewState(input)
        assertTrue(review.accept(review.beginAnalysis(), conflictMerge))
        try {
            review.confirm("op1")
            fail("Conflicting operation must not be selectable")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun processedWithoutEvidenceIsIncompleteAndInventedQuoteNeedsReview() {
        val plan = parse(response().put("operations", JSONArray().put(operation().put("quote", "없는 인용")))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))
                .put(status(segments[1].id, "PROCESSED"))))
        assertFalse(plan.operations.single().evidence.matched)
        assertEquals(NaturalBatchPlan.ReviewStatus.NEEDS_REVIEW,
            NaturalBatchPlans.merge(input, listOf(plan)).status(plan.operations.single()))
        assertTrue(segments[1].id in NaturalBatchPlans.merge(input, listOf(plan)).incompleteSegments)
    }

    @Test fun inputEditInvalidatesWholePlanAndDirectEditOnlyItsOwnDecision() {
        val plan = parse(response().put("operations", JSONArray().put(operation())
            .put(operation("op2", "의사").put("fieldRef", "f2")))
            .put("segmentStatus", JSONArray().put(status(segments[0].id, "PROCESSED"))))
        val review = NaturalBatchReviewState(input)
        val request = review.beginAnalysis()
        assertTrue(review.accept(request, NaturalBatchPlans.merge(input, listOf(plan))))
        assertFalse(review.isSelected("op1"))
        review.confirm("op1")
        review.confirm("op2")
        review.setSelected("op1", true)
        review.setSelected("op2", true)
        assertFalse(review.accept(request, NaturalBatchPlans.merge(input, listOf(plan))))
        assertTrue(review.isSelected("op2"))
        review.editProposal("op1", "작가")
        assertFalse(review.isSelected("op1"))
        assertTrue(review.isSelected("op2"))
        val snapshot = review.snapshot()
        val restored = NaturalBatchReviewState(input)
        restored.restore(snapshot)
        assertTrue(restored.isSelected("op2"))
        assertFalse(restored.accept(request, NaturalBatchPlans.merge(input, listOf(plan))))
        restored.editInput(input.text + " 추가")
        assertEquals(null, restored.plan)
        assertFalse(restored.isSelected("op2"))
        assertEquals(1L, restored.input.inputRevision)
    }
}
