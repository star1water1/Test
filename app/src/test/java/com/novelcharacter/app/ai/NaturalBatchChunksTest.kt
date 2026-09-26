package com.novelcharacter.app.ai

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class NaturalBatchChunksTest {
    private fun input(text: String) = NaturalBatchInput.create(
        NaturalBatchInput.Scope(NaturalBatchInput.ScopeKind.WORK, 10), text)

    private fun plan(input: NaturalBatchInput, ids: Set<String>, sequence: Long, value: String = "기자",
        target: String = "c1", field: String = "f1") = NaturalBatchPlan(
        input.sessionId, input.scopeRevision, input.inputRevision,
        listOf(NaturalBatchPlan.Operation("same-model-id", NaturalBatchPlan.Kind.SET_FIELD_VALUE,
            target, field, null, null, null, value, null, null, null, null, null, null, null,
            NaturalBatchPlan.Origin.EXTRACTED, NaturalBatchPlan.Evidence(ids.toList(), value, true))),
        emptyList(), emptyList(), emptyList(), ids.map {
            NaturalBatchPlan.SegmentStatus(it, NaturalBatchPlan.Coverage.PROCESSED, null)
        }, ids, sequence)

    @Test fun chunkingPreservesParagraphsOffsetsUnicodeAndAllInputWithoutAProductLimit() {
        val original = "민아는 경찰이 아니다. 아니, 기자다!😀".repeat(300) + "\r\n\r\n" +
            (1..35).joinToString("\n\n") { "인물${it}의 직업은 기자다😀" }
        val input = input(original)
        val chunks = NaturalBatchChunks.partition(input)
        assertTrue(chunks.size > 1)
        assertEquals(input.segments().map { it.id }, chunks.flatMap { it })
        assertEquals(input.segments().first().id, chunks.first().single())
        assertTrue(input.segments().first().text.length > NaturalBatchChunks.TARGET_CHARS)
        input.segments().forEach { assertEquals(it.text, original.substring(it.start, it.end)) }
        assertEquals(original, input.text)
    }

    @Test fun perRequestContextKeepsStableRefsAndAmbiguousAliasesButOmitsUnrelatedValues() {
        val input = input("Alice 직업\n\nBob 나이")
        val chars = mapOf("c4" to NaturalBatchContext.Character(4, 10, 7, "Alice", emptyList(), "C4", null),
            "c9" to NaturalBatchContext.Character(9, 10, 7, "Bob", emptyList(), "C9", null),
            "c11" to NaturalBatchContext.Character(11, 10, 7, "다른 Alice", listOf("Alice"), "C11", null))
        val fields = mapOf("f1" to NaturalBatchContext.Field(1, 7, "직업", "job", "TEXT", "{}"),
            "f2" to NaturalBatchContext.Field(2, 7, "나이", "age", "NUMBER", "{}"))
        val context = NaturalBatchContext(chars, fields, emptyMap(), emptyMap(),
            mapOf(("c4" to "f1") to "탐정", ("c4" to "f2") to "40", ("c9" to "f2") to "50"), emptyList())
        val first = NaturalBatchChunks.context(input, setOf(input.segments().first().id), context)
        assertEquals(setOf("c4", "c11"), first.characters.keys)
        assertEquals(mapOf(("c4" to "f1") to "탐정"), first.values)
        val second = NaturalBatchChunks.context(input, setOf(input.segments().last().id), context)
        assertEquals(setOf("c9"), second.characters.keys)
        assertEquals(mapOf(("c9" to "f2") to "50"), second.values)
    }

    @Test fun interruptedAndFailedRequestsRemainIncompleteAfterGsonRestoreAndRetryKeepsSuccess() {
        val input = input("Alice 직업 기자\n\nBob 나이 20")
        val ids = input.segments().map { it.id }
        var state = NaturalBatchChunkState.create(input)
        val first = state.reserve(input, setOf(ids[0])); state = first.first.finish(first.second,
            plan(input, setOf(ids[0]), first.second))
        val interrupted = state.reserve(input, setOf(ids[1])).first
        val restored = Gson().fromJson(Gson().toJson(interrupted), NaturalBatchChunkState::class.java)
        assertEquals(setOf(ids[1]), restored.merge(input).incompleteSegments)
        assertFalse(restored.merge(input).complete)
        val successId = restored.merge(input).operations.single().id
        val retry = restored.reserve(input, setOf(ids[1]))
        val finished = retry.first.finish(retry.second, plan(input, setOf(ids[1]), retry.second, "20", "c2", "f2"))
        assertTrue(finished.merge(input).complete)
        assertTrue(finished.merge(input).operations.any { it.id == successId })
        assertEquals(2, finished.plans.size)
        assertEquals(3, finished.nextSequence)
    }

    @Test fun retryExpandsCrossParagraphEvidenceAndCannotSplitItEvenAtATinyTarget() {
        val input = input("Alice 직업 기자\n\n아니, 의사다\n\nBob 나이 20")
        val ids = input.segments().map { it.id }
        val plans = listOf(plan(input, ids.take(2).toSet(), 0))
        val scope = NaturalBatchPlans.retryScope(input, plans, setOf(ids[1]))
        assertEquals(ids.take(2).toSet(), scope)
        assertEquals(listOf(scope), NaturalBatchChunks.partition(input, scope, 1, 1, plans))
    }

    @Test fun progressKeepsEditsSelectionAndRejectsStaleResponsesAndNewConflicts() {
        val input = input("Alice 직업 기자\n\nAlice 직업 의사")
        val ids = input.segments().map { it.id }
        val first = plan(input, setOf(ids[0]), 0)
        val review = NaturalBatchReviewState(input)
        val request = review.beginAnalysis()
        assertTrue(review.acceptProgress(request, NaturalBatchPlans.merge(input, listOf(first), setOf(ids[1]))))
        val id = review.plan!!.operations.single().id
        review.editProposal(id, "탐정"); review.confirm(id); review.setSelected(id, true)
        val retry = review.beginRetry()
        assertFalse(review.acceptProgress(request, NaturalBatchPlans.merge(input, listOf(first))))
        assertTrue(review.acceptProgress(retry, NaturalBatchPlans.merge(input, listOf(first), setOf(ids[1]))))
        assertEquals(setOf(id), review.snapshot().selected)
        assertEquals("탐정", review.editedValue(id))
        assertTrue(review.acceptProgress(retry, NaturalBatchPlans.merge(input,
            listOf(first, plan(input, setOf(ids[1]), 1, "의사")))))
        assertTrue(review.plan!!.hasConflicts)
        assertTrue(review.snapshot().selected.isEmpty())
        review.editInput("바뀐 원문")
        assertFalse(review.acceptProgress(retry, NaturalBatchPlans.merge(input, listOf(first))))
    }

    @Test fun failureRetainsReasonAndReplacedProposalsRequireFreshConfirmation() {
        val input = input("Alice 직업 기자")
        val ids = input.segments().map { it.id }.toSet()
        val reserved = NaturalBatchChunkState.create(input).reserve(input, ids)
        val success = reserved.first.finish(reserved.second, plan(input, ids, reserved.second))
        val review = NaturalBatchReviewState(input)
        assertTrue(review.accept(review.beginAnalysis(), success.merge(input)))
        val oldId = review.plan!!.operations.single().id
        review.confirm(oldId); review.setSelected(oldId, true)
        val retryRequest = review.beginRetry()
        val retried = success.reserve(input, ids)
        val failure = retried.first.finish(retried.second, null, "응답이 잘렸습니다")
        assertTrue(review.acceptProgress(retryRequest, failure.merge(input)))
        assertEquals("응답이 잘렸습니다", failure.failures.values.single())
        assertTrue(review.snapshot().selected.isEmpty())
        assertTrue(review.plan!!.operations.isEmpty())
    }
}
