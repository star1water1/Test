package com.novelcharacter.app.ai

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NaturalBatchFieldExecutorTest {
    private lateinit var db: AppDatabase
    private lateinit var executor: NaturalBatchFieldExecutor

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).build()
        executor = NaturalBatchFieldExecutor(db)
        db.universeDao().insert(Universe(id = 1, name = "World"))
        db.novelDao().insert(Novel(id = 10, title = "Book", universeId = 1))
        db.characterDao().insert(Character(id = 100, name = "Alice", novelId = 10))
        db.characterDao().insert(Character(id = 101, name = "Bob", novelId = 10))
        db.fieldDefinitionDao().insert(FieldDefinition(id = 20, universeId = 1,
            key = "birth", name = "Birth", type = "NUMBER", config = """{"semanticRole":"birth_year"}"""))
        db.fieldDefinitionDao().insert(FieldDefinition(id = 21, universeId = 1,
            key = "trait", name = "Trait", type = "TEXT"))
        db.characterFieldValueDao().insert(CharacterFieldValue(characterId = 100,
            fieldDefinitionId = 20, value = "2000"))
        db.characterFieldValueDao().insert(CharacterFieldValue(characterId = 101,
            fieldDefinitionId = 21, value = "old"))
        Unit
    }

    @After fun close() { db.close() }

    private fun operation(id: String, target: String, field: String, value: String,
        kind: NaturalBatchPlan.Kind = NaturalBatchPlan.Kind.SET_FIELD_VALUE) =
        NaturalBatchPlan.Operation(id = id, kind = kind,
            targetRef = target, fieldRef = field, relatedRef = null, factionRef = null,
            relationshipRef = null, value = value, relationshipType = null,
            relationshipDescription = null, intensity = null, bidirectional = null,
            joinYear = null, leaveYear = null, leaveMode = null,
            origin = NaturalBatchPlan.Origin.EXTRACTED,
            evidence = NaturalBatchPlan.Evidence(listOf("s0"), "Alice Birth", true))

    private fun review(vararg ops: NaturalBatchPlan.Operation): Pair<NaturalBatchReviewState.Snapshot, NaturalBatchContext> {
        val input = NaturalBatchInput("session-1", NaturalBatchInput.Scope(
            NaturalBatchInput.ScopeKind.UNIVERSE, 1), 0, 0, "Alice Birth; Bob Trait")
        val state = NaturalBatchReviewState(input)
        val request = state.beginAnalysis()
        val merge = NaturalBatchMerge(input.sessionId, 0, 0, ops.toList(), emptyList(),
            emptyList(), emptyList(), emptySet(), emptySet(), emptySet(), true)
        check(state.accept(request, merge))
        ops.forEach { state.confirm(it.id); state.setSelected(it.id, true) }
        val context = NaturalBatchContext(
            characters = mapOf(
                "c1" to NaturalBatchContext.Character(100, 10, 1, "Alice", emptyList(), "A", "Book"),
                "c2" to NaturalBatchContext.Character(101, 10, 1, "Bob", emptyList(), "B", "Book")),
            fields = mapOf(
                "f1" to NaturalBatchContext.Field(20, 1, "Birth", "birth", "NUMBER",
                    """{"semanticRole":"birth_year"}"""),
                "f2" to NaturalBatchContext.Field(21, 1, "Trait", "trait", "TEXT", "{}")),
            factions = emptyMap(), relationships = emptyMap(),
            values = mapOf(("c1" to "f1") to "2000", ("c2" to "f2") to "old"),
            omitted = emptyList())
        return state.snapshot() to context
    }

    @Test fun independentRowsCommitAndAStaleRowDoesNotBlockThem() = runBlocking {
        val (review, context) = review(operation("one", "c1", "f1", "2001"),
            operation("two", "c2", "f2", "new"))
        val prepared = executor.preflight(review, context)
        assertEquals(listOf(NaturalBatchFieldExecutor.Status.READY,
            NaturalBatchFieldExecutor.Status.READY), prepared.items.map { it.decision.status })
        val bob = db.characterFieldValueDao().getValue(101, 21)!!
        db.characterFieldValueDao().update(bob.copy(value = "someone else's edit"))
        assertEquals(listOf(NaturalBatchFieldExecutor.Status.APPLIED,
            NaturalBatchFieldExecutor.Status.STALE),
            executor.apply(prepared, "run-1").map { it.status })
        assertEquals("2001", db.characterFieldValueDao().getValue(100, 20)?.value)
        assertEquals("someone else's edit", db.characterFieldValueDao().getValue(101, 21)?.value)
        assertEquals(NaturalBatchFieldExecutor.Status.ALREADY_APPLIED,
            executor.apply(prepared, "run-1").first().status)
        assertEquals(1, db.naturalBatchJournalDao().operations("run-1").size)
    }

    @Test fun undoRunsInTheReverseOfTheActualInsertOrder() = runBlocking {
        val (review, context) = review(operation("first", "c1", "f1", "2001"),
            operation("second", "c2", "f2", "new"))
        assertEquals(listOf(NaturalBatchFieldExecutor.Status.APPLIED,
            NaturalBatchFieldExecutor.Status.APPLIED),
            executor.apply(executor.preflight(review, context), "run-order").map { it.status })
        assertEquals(listOf("first", "second"),
            db.naturalBatchJournalDao().operations("run-order").map { it.operationId })
        assertEquals(listOf("second", "first"), executor.undo("run-order").map { it.operationId })
    }

    @Test fun semanticSideEffectAndUndoUseRealRowsAndRejectLaterChanges() = runBlocking {
        val (review, context) = review(operation("birth", "c1", "f1", "2001"))
        val prepared = executor.preflight(review, context)
        assertEquals(NaturalBatchFieldExecutor.Status.APPLIED, executor.apply(prepared, "run-2").single().status)
        assertNotNull(db.characterStateChangeDao().getChangesByField(100, CharacterStateChange.KEY_BIRTH).singleOrNull())
        val value = db.characterFieldValueDao().getValue(100, 20)!!
        db.characterFieldValueDao().update(value.copy(value = "2002"))
        assertEquals(NaturalBatchFieldExecutor.Status.UNDO_CONFLICT, executor.undo("run-2").single().status)
        assertEquals("2002", db.characterFieldValueDao().getValue(100, 20)?.value)
        db.characterFieldValueDao().update(value)
        assertEquals(NaturalBatchFieldExecutor.Status.UNDONE, executor.undo("run-2").single().status)
        assertEquals("2000", db.characterFieldValueDao().getValue(100, 20)?.value)
        assertNull(db.characterStateChangeDao().getChangesByField(100, CharacterStateChange.KEY_BIRTH).singleOrNull())
        assertEquals(NaturalBatchFieldExecutor.Status.ALREADY_UNDONE, executor.undo("run-2").single().status)
    }

    @Test fun changedFieldDefinitionIsRejectedBeforeWrite() = runBlocking {
        val (review, context) = review(operation("trait", "c2", "f2", "new"))
        val prepared = executor.preflight(review, context)
        val field = db.fieldDefinitionDao().getFieldById(21)!!
        db.fieldDefinitionDao().update(field.copy(config = """{"inputMode":"restricted"}"""))
        assertEquals(NaturalBatchFieldExecutor.Status.FIELD_CHANGED,
            executor.apply(prepared, "run-3").single().status)
        assertEquals("old", db.characterFieldValueDao().getValue(101, 21)?.value)
    }

    @Test fun undoRefusesAFieldWhoseRulesChangedAfterApply() = runBlocking {
        val (review, context) = review(operation("trait", "c2", "f2", "new"))
        assertEquals(NaturalBatchFieldExecutor.Status.APPLIED,
            executor.apply(executor.preflight(review, context), "run-rules").single().status)
        val field = db.fieldDefinitionDao().getFieldById(21)!!
        db.fieldDefinitionDao().update(field.copy(config = """{"inputMode":"restricted"}"""))
        assertEquals(NaturalBatchFieldExecutor.Status.UNDO_CONFLICT,
            executor.undo("run-rules").single().status)
        assertEquals("new", db.characterFieldValueDao().getValue(101, 21)?.value)
    }

    @Test fun requiredClearIsBlockedByTheSameFieldPolicyAsTheEditor() = runBlocking {
        val field = db.fieldDefinitionDao().getFieldById(21)!!
        db.fieldDefinitionDao().update(field.copy(isRequired = true,
            config = """{"requiredEnforcement":"block"}"""))
        val (review, oldContext) = review(operation("clear", "c2", "f2", "",
            NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE))
        val context = oldContext.copy(fields = oldContext.fields +
            ("f2" to oldContext.fields.getValue("f2").copy(config = """{"requiredEnforcement":"block"}""")))
        assertEquals(NaturalBatchFieldExecutor.Status.INVALID_VALUE,
            executor.preflight(review, context).items.single().decision.status)
        assertEquals("old", db.characterFieldValueDao().getValue(101, 21)?.value)
    }

    @Test fun multiValueAdditionAndUndoPreserveOtherTokens() = runBlocking {
        val field = db.fieldDefinitionDao().getFieldById(21)!!
        db.fieldDefinitionDao().update(field.copy(type = "MULTI_TEXT"))
        val old = db.characterFieldValueDao().getValue(101, 21)!!
        db.characterFieldValueDao().update(old.copy(value = "red, blue"))
        val (review, oldContext) = review(operation("add", "c2", "f2", "green",
            NaturalBatchPlan.Kind.ADD_FIELD_VALUE))
        val context = oldContext.copy(fields = oldContext.fields +
            ("f2" to oldContext.fields.getValue("f2").copy(type = "MULTI_TEXT")),
            values = oldContext.values + (("c2" to "f2") to "red, blue"))
        val prepared = executor.preflight(review, context)
        assertEquals(NaturalBatchFieldExecutor.Status.APPLIED,
            executor.apply(prepared, "run-4").single().status)
        assertEquals("red, blue, green", db.characterFieldValueDao().getValue(101, 21)?.value)
        assertEquals(NaturalBatchFieldExecutor.Status.UNDONE, executor.undo("run-4").single().status)
        assertEquals("red, blue", db.characterFieldValueDao().getValue(101, 21)?.value)
        val (duplicateReview, duplicateContext) = review(operation("duplicate", "c2", "f2", "red",
            NaturalBatchPlan.Kind.ADD_FIELD_VALUE))
        val currentContext = duplicateContext.copy(fields = context.fields,
            values = duplicateContext.values + (("c2" to "f2") to "red, blue"))
        assertEquals(NaturalBatchFieldExecutor.Status.ALREADY_SATISFIED,
            executor.preflight(duplicateReview, currentContext).items.single().decision.status)
    }
}
