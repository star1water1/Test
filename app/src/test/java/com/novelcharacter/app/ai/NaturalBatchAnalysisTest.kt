package com.novelcharacter.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalBatchAnalysisTest {
    @Test fun relationshipReferenceStillRequiresBothQuotedEndpoints() {
        val first = NaturalBatchContext.Character(1, 10, 7, "Alice", emptyList(), "A", "Book")
        val second = NaturalBatchContext.Character(2, 10, 7, "Bob", emptyList(), "B", "Book")
        val context = NaturalBatchContext(mapOf("c1" to first, "c2" to second), emptyMap(),
            emptyMap(), mapOf("r1" to NaturalBatchContext.Relationship(3, 1, 2, "동료", "", true, 5, "R")),
            emptyMap(), emptyList(), listOf("동료", "친구"))
        val op = NaturalBatchPlan.Operation("edit", NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP,
            "c1", null, null, null, "r1", null, "친구", null, null, null, null, null, null,
            NaturalBatchPlan.Origin.EXTRACTED, NaturalBatchPlan.Evidence(listOf("s0"), "Alice is a friend", true))
        val plan = NaturalBatchPlan("session", 0, 0, listOf(op), emptyList(), emptyList(), emptyList(),
            emptyList(), setOf("s0"), 0)
        assertTrue(NaturalBatchEvidenceGuard.check(plan, context).operations.isEmpty())
        val valid = op.copy(evidence = op.evidence.copy(quote = "Alice and Bob are friends"))
        assertEquals(listOf(valid), NaturalBatchEvidenceGuard.check(plan.copy(operations = listOf(valid)), context).operations)
        val outsider = context.copy(characters = context.characters + ("c3" to first.copy(id = 4, name = "Carol", code = "C")))
        assertTrue(NaturalBatchEvidenceGuard.check(plan.copy(operations = listOf(valid.copy(targetRef = "c3",
            evidence = valid.evidence.copy(quote = "Alice, Bob and Carol")))), outsider).operations.isEmpty())
        val request = NaturalBatchPrompt.request(NaturalBatchInput("session", NaturalBatchInput.Scope(
            NaturalBatchInput.ScopeKind.WORK, 10), 0, 0, "Alice and Bob"), context, 1024)
        assertTrue(request.messages.single().text.contains("relationshipTypes"))
    }
    private val scope = NaturalBatchInput.Scope(NaturalBatchInput.ScopeKind.WORK, 10)
    private val input = NaturalBatchInput("session", scope, 0, 0,
        "민아의 직업은 기자다. 세력 새벽단에 들어갔다.")
    private val characters = listOf(
        NaturalBatchContext.Character(1, 10, 7, "김민아", listOf("민아"), "C1", "첫 작품"),
        NaturalBatchContext.Character(2, 10, 7, "박민아", listOf("민아"), "C2", "첫 작품"),
        NaturalBatchContext.Character(3, 11, 7, "외부 민아", listOf("민아"), "C3", "다른 작품"),
        NaturalBatchContext.Character(4, 10, 8, "민아", emptyList(), "C4", "다른 세계관")
    )
    private val fields = listOf(
        NaturalBatchContext.Field(20, 7, "직업", "job", "TEXT", "{}"),
        NaturalBatchContext.Field(21, 8, "직업", "job", "TEXT", "{}"),
        NaturalBatchContext.Field(22, null, "직업", "job", "TEXT", "{}"),
        NaturalBatchContext.Field(23, 7, "사건 날짜", "event_date", "DATE", "{}")
    )

    @Test fun ambiguousAliasCannotBecomeAnExecutableCandidate() {
        val context = NaturalBatchContextSelector.select(input, 7, characters, fields,
            emptyList(), emptyList(), emptyMap())
        fun operation(id: String, quote: String) = NaturalBatchPlan.Operation(id,
            NaturalBatchPlan.Kind.SET_FIELD_VALUE, "c1", "f1", null, null, null,
            "기자", null, null, null, null, null, null, null,
            NaturalBatchPlan.Origin.EXTRACTED,
            NaturalBatchPlan.Evidence(listOf(input.segments().single().id), quote, true))
        val plan = NaturalBatchPlan(input.sessionId, 0, 0,
            listOf(operation("ambiguous", "민아의 직업"),
                operation("specific", "김민아의 직업")), emptyList(), emptyList(), emptyList(),
            emptyList(), input.segments().map { it.id }.toSet(), 0)
        val checked = NaturalBatchEvidenceGuard.check(plan, context)
        assertEquals(listOf("specific"), checked.operations.map { it.id })
        assertEquals(listOf("ambiguous"), checked.unresolved.map { it.id })

        val calculated = context.copy(fields = context.fields +
            ("f3" to NaturalBatchContext.Field(24, 7, "합계", "total", "CALCULATED", "{}")))
        val computed = operation("computed", "김민아의 합계").copy(fieldRef = "f3")
        val computedResult = NaturalBatchEvidenceGuard.check(plan.copy(operations = listOf(computed)), calculated)
        assertTrue(computedResult.operations.isEmpty())
        assertTrue(computedResult.unresolved.single().reason.contains("계산 필드"))
    }

    @Test fun workScopeKeepsAmbiguousAliasesButExcludesOtherWorksAndFieldZones() {
        val context = NaturalBatchContextSelector.select(input, 7, characters, fields,
            listOf(NaturalBatchContext.Faction(30, 7, "새벽단", "A1"),
                NaturalBatchContext.Faction(31, 8, "새벽단", "A2")),
            listOf(NaturalBatchContext.Relationship(40, 1, 2, "동료", "", true, 5),
                NaturalBatchContext.Relationship(41, 1, 3, "동료", "", true, 5)),
            mapOf((1L to 20L) to "탐정", (1L to 23L) to "비공개 날짜",
                (3L to 20L) to "외부",
                (1L to 21L) to "다른 세계관 값"))
        assertEquals(setOf(1L, 2L), context.characters.values.map { it.id }.toSet())
        assertEquals(setOf(20L, 23L), context.fields.values.map { it.id }.toSet())
        assertEquals(listOf(30L), context.factions.values.map { it.id })
        assertEquals(listOf(40L), context.relationships.values.map { it.id })
        assertEquals(mapOf(("c1" to "f1") to "탐정"), context.values)
        assertTrue(context.omitted.any { it.contains("기존값은 전송하지 않았습니다") })
        val prompt = NaturalBatchPrompt.request(input, context, 2048)
        val body = prompt.messages.single().text
        assertFalse(body.contains("다른 작품"))
        assertFalse(body.contains("다른 세계관 값"))
        assertFalse(body.contains("비공개 날짜"))
        assertFalse(body.contains("\"C3\""))
        assertTrue(body.contains("\"C1\""))
        assertTrue(prompt.system!!.contains("Ambiguous names"))
    }

    @Test fun workWithoutUniverseReceivesOnlyGlobalCharacterFields() {
        val unassigned = characters.filter { it.novelId == 10L }.map { it.copy(universeId = null) }
        val context = NaturalBatchContextSelector.select(input, null, unassigned, fields,
            emptyList(), emptyList(), emptyMap())
        assertEquals(listOf(22L), context.fields.values.map { it.id })
        assertTrue(context.factions.isEmpty())
    }

    @Test fun analyzerKeepsMalformedAndTruncatedRepliesOutOfReview() = runBlocking {
        val context = NaturalBatchContextSelector.select(input, 7, characters, fields,
            emptyList(), emptyList(), emptyMap())
        var calls = 0
        val truncated = NaturalBatchAnalyzer({ context }, {
            calls++
            AiResult.Success("{}", "test", truncated = true)
        }, { 512 }).analyze(input, 0)
        assertTrue(truncated is NaturalBatchAnalyzer.Outcome.Failed)
        assertTrue((truncated as NaturalBatchAnalyzer.Outcome.Failed).billedResponse != null)
        assertEquals(1, calls)

        val malformed = NaturalBatchAnalyzer({ context }, {
            AiResult.Success("{\"schemaVersion\":2}", "test")
        }, { 512 }).analyze(input, 1)
        assertTrue(malformed is NaturalBatchAnalyzer.Outcome.Failed)

        val segment = input.segments().single()
        val response = JSONObject().put("schemaVersion", 1).put("sessionId", input.sessionId)
            .put("scopeRevision", 0).put("inputRevision", 0)
            .put("operations", JSONArray()).put("constraints", JSONArray())
            .put("unresolved", JSONArray().put(JSONObject().put("id", "u1")
                .put("segmentIds", JSONArray().put(segment.id)).put("text", segment.text)
                .put("reason", "동명이인 확인 필요")))
            .put("suggestionNotes", JSONArray())
            .put("segmentStatus", JSONArray().put(JSONObject().put("segmentId", segment.id)
                .put("status", "PROCESSED")))
        val ready = NaturalBatchAnalyzer({ context }, {
            AiResult.Success(response.toString(), "test")
        }, { 512 }).analyze(input, 2)
        assertTrue(ready is NaturalBatchAnalyzer.Outcome.Ready)
        assertEquals(1, (ready as NaturalBatchAnalyzer.Outcome.Ready).plan.unresolved.size)
    }
}
