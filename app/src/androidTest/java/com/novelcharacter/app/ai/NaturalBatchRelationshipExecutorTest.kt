package com.novelcharacter.app.ai

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor.Status
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NaturalBatchRelationshipExecutorTest {
    private lateinit var db: AppDatabase
    private lateinit var executor: NaturalBatchFieldExecutor
    private val input = NaturalBatchInput("relationship-session", NaturalBatchInput.Scope(
        NaturalBatchInput.ScopeKind.WORK, 10), 0, 0, "Alice와 Bob은 친구다")

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).build()
        executor = NaturalBatchFieldExecutor(db)
        db.universeDao().insert(Universe(id = 1, name = "World", code = "U"))
        db.novelDao().insert(Novel(id = 10, title = "Book", universeId = 1, code = "N"))
        db.characterDao().insert(Character(id = 100, name = "Alice", novelId = 10, code = "A"))
        db.characterDao().insert(Character(id = 101, name = "Bob", novelId = 10, code = "B"))
        Unit
    }
    @After fun close() { db.close() }

    private fun operation(kind: NaturalBatchPlan.Kind = NaturalBatchPlan.Kind.ADD_RELATIONSHIP,
        id: String = "proposal") = NaturalBatchPlan.Operation(id, kind, "c1", null,
        if (kind == NaturalBatchPlan.Kind.ADD_RELATIONSHIP) "c2" else null, null,
        if (kind == NaturalBatchPlan.Kind.ADD_RELATIONSHIP) null else "r1", null,
        if (kind == NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP) null else "친구", null, null, null,
        null, null, null, NaturalBatchPlan.Origin.EXTRACTED,
        NaturalBatchPlan.Evidence(listOf(input.segments().single().id), input.text, true))

    private suspend fun context(): NaturalBatchContext = NaturalBatchContextLoader(db).load(input)
    private fun review(vararg ops: NaturalBatchPlan.Operation): NaturalBatchReviewState {
        val state = NaturalBatchReviewState(input)
        val request = state.beginAnalysis()
        assertTrue(state.accept(request, NaturalBatchMerge(input.sessionId, 0, 0, ops.toList(),
            emptyList(), emptyList(), emptyList(), emptySet(), emptySet(), emptySet(), true)))
        ops.forEach { state.confirm(it.id); state.setSelected(it.id, true) }
        return state
    }
    private suspend fun prepare(op: NaturalBatchPlan.Operation, ctx: NaturalBatchContext? = null) =
        executor.preflight(review(op).snapshot(), ctx ?: context())
    private suspend fun relation(type: String = "동료", both: Boolean = true): CharacterRelationship {
        val row = CharacterRelationship(characterId1 = 100, characterId2 = 101,
            relationshipType = type, isBidirectional = both, description = "original")
        return row.copy(id = db.characterRelationshipDao().insert(row))
    }

    @Test fun addRetryAndUndoKeepOneDurableRelationship() = runBlocking {
        val prepared = prepare(operation().copy(bidirectional = false, intensity = 8))
        assertEquals(Status.READY, prepared.items.single().decision.status)
        assertTrue(prepared.items.single().decision.after!!.contains("Alice → Bob"))
        assertEquals(Status.APPLIED, executor.apply(prepared, "add").single().status)
        val row = db.characterRelationshipDao().getAllRelationships().single()
        assertFalse(row.isBidirectional); assertEquals(8, row.intensity)
        assertEquals(Status.ALREADY_APPLIED, executor.apply(prepared, "add").single().status)
        assertEquals(Status.UNDONE, executor.undo("add").single().status)
        assertTrue(db.characterRelationshipDao().getAllRelationships().isEmpty())
        assertEquals(Status.ALREADY_UNDONE, executor.undo("add").single().status)
        assertEquals(Status.ALREADY_APPLIED, executor.apply(prepared, "again").single().status)
    }

    @Test fun updatePreservesUnspecifiedPropertiesAndHistory() = runBlocking {
        val old = relation(both = false)
        val change = CharacterRelationshipChange(relationshipId = old.id, year = 2010,
            relationshipType = "라이벌")
        db.characterRelationshipChangeDao().insert(change)
        val prepared = prepare(operation(NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP).copy(intensity = 9))
        assertEquals(Status.APPLIED, executor.apply(prepared, "update").single().status)
        val row = db.characterRelationshipDao().getById(old.id)!!
        assertEquals("친구", row.relationshipType); assertEquals(9, row.intensity)
        assertEquals(old.description, row.description); assertFalse(row.isBidirectional)
        assertEquals(1, db.characterRelationshipChangeDao().getChangesForRelationshipList(old.id).size)
        assertEquals(Status.UNDONE, executor.undo("update").single().status)
        assertEquals(old, db.characterRelationshipDao().getById(old.id))
    }

    @Test fun removalReportsAndRestoresHistoryAndEventLinkByActualId() = runBlocking {
        val old = relation()
        val eventId = db.timelineDao().insert(TimelineEvent(universeId = 1, year = 2010, description = "Meeting", code = "E"))
        val id = db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(
            relationshipId = old.id, year = 2010, relationshipType = "친구", eventId = eventId))
        val change = db.characterRelationshipChangeDao().getById(id)
        val prepared = prepare(operation(NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP))
        assertEquals(1, prepared.items.single().decision.sideEffects)
        assertTrue(prepared.items.single().decision.reason.contains("1건"))
        assertEquals(Status.APPLIED, executor.apply(prepared, "remove").single().status)
        assertNull(db.characterRelationshipChangeDao().getById(id))
        assertEquals(Status.UNDONE, executor.undo("remove").single().status)
        assertEquals(old, db.characterRelationshipDao().getById(old.id))
        assertEquals(change, db.characterRelationshipChangeDao().getById(id))
    }

    @Test fun removalUndoRejectsReusedEventIdentity() = runBlocking {
        val old = relation()
        val event = TimelineEvent(universeId = 1, year = 2010, description = "Meeting", code = "E")
        val eventId = db.timelineDao().insert(event)
        db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(
            relationshipId = old.id, year = 2010, relationshipType = "친구", eventId = eventId))
        executor.apply(prepare(operation(NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP)), "remove")
        db.timelineDao().update(event.copy(id = eventId, code = "another-event"))
        assertEquals(Status.UNDO_CONFLICT, executor.undo("remove").single().status)
        assertNull(db.characterRelationshipDao().getById(old.id))
    }

    @Test fun staleEndpointAndRelationshipIdentitiesBlockWrites() = runBlocking {
        val ctx = context()
        val bob = db.characterDao().getCharacterById(101)!!
        db.characterDao().update(bob.copy(code = "another-Bob"))
        assertEquals(Status.STALE, prepare(operation(), ctx).items.single().decision.status)
        db.characterDao().update(bob)
        val old = relation()
        val before = context()
        db.characterRelationshipDao().update(old.copy(code = "another-link"))
        assertEquals(Status.STALE, prepare(operation(NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP), before)
            .items.single().decision.status)
    }

    @Test fun concurrentEditsAfterPreviewBlockApplyAndUndo() = runBlocking {
        val old = relation()
        val prepared = prepare(operation(NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP))
        db.characterRelationshipDao().update(old.copy(description = "user edit"))
        assertEquals(Status.STALE, executor.apply(prepared, "stale").single().status)
        db.characterRelationshipDao().update(old)
        executor.apply(prepare(operation(NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP)), "good")
        val current = db.characterRelationshipDao().getById(old.id)!!
        db.characterRelationshipDao().update(current.copy(description = "later edit"))
        assertEquals(Status.UNDO_CONFLICT, executor.undo("good").single().status)
        assertEquals("later edit", db.characterRelationshipDao().getById(old.id)!!.description)
    }

    @Test fun newHistoryAfterAddMakesUndoConflictInsteadOfCascadeLoss() = runBlocking {
        executor.apply(prepare(operation()), "add")
        val row = db.characterRelationshipDao().getAllRelationships().single()
        db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(relationshipId = row.id,
            year = 2030, relationshipType = "친구"))
        assertEquals(Status.UNDO_CONFLICT, executor.undo("add").single().status)
        assertEquals(1, db.characterRelationshipChangeDao().getChangesForRelationshipList(row.id).size)
    }

    @Test fun duplicateDirectionAndAutomaticRelationshipAreExplicitConflicts() = runBlocking {
        val reverse = CharacterRelationship(characterId1 = 101, characterId2 = 100, relationshipType = "친구")
        db.characterRelationshipDao().insert(reverse)
        assertEquals(Status.ALREADY_SATISFIED, prepare(operation()).items.single().decision.status)
        db.characterRelationshipDao().deleteAll()
        val factionId = db.factionDao().insert(Faction(universeId = 1, name = "Guild", autoRelationType = "친구"))
        db.characterRelationshipDao().insert(reverse.copy(factionId = factionId))
        assertEquals(Status.STALE, prepare(operation()).items.single().decision.status)
        assertTrue(context().relationships.isEmpty())
    }

    @Test fun opposingDirectedRelationshipsCanCoexist() = runBlocking {
        db.characterRelationshipDao().insert(CharacterRelationship(characterId1 = 101, characterId2 = 100,
            relationshipType = "친구", isBidirectional = false))
        assertEquals(Status.READY, prepare(operation().copy(bidirectional = false)).items.single().decision.status)
        assertEquals(Status.STALE, prepare(operation().copy(bidirectional = true)).items.single().decision.status)
    }

    @Test fun selectedAddAndReferencedUpdateCannotCollideSilently() = runBlocking {
        relation("친구")
        val ops = arrayOf(operation(id = "add"), operation(NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP, "update"))
        val prepared = executor.preflight(review(*ops).snapshot(), context())
        assertTrue(prepared.items.all { it.decision.status == Status.STALE })
        assertTrue(executor.apply(prepared, "conflict").all { it.status == Status.STALE })
    }

    @Test fun directEditsRequireNewConfirmationAndPreserveStableRelationship() = runBlocking {
        val old = relation()
        val op = operation(NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP)
        val state = review(op)
        state.editProposal(op.id, NaturalBatchRelationshipEdits.Draft("적", "", 2, false).encode())
        assertTrue(state.snapshot().selected.isEmpty())
        state.confirm(op.id); state.setSelected(op.id, true)
        val saved = com.google.gson.Gson().toJson(NaturalBatchContextSnapshot.from(context()))
        val restored = com.google.gson.Gson().fromJson(saved, NaturalBatchContextSnapshot::class.java).restore()
        executor.apply(executor.preflight(state.snapshot(), restored), "edit")
        val row = db.characterRelationshipDao().getById(old.id)!!
        assertEquals(old.code, row.code); assertEquals("적", row.relationshipType)
        assertEquals("", row.description); assertFalse(row.isBidirectional); assertEquals(2, row.intensity)
    }

    @Test fun changedTypesAndMissingLegacyContextRequireReanalysis() = runBlocking {
        val ctx = context()
        assertEquals(Status.STALE, prepare(operation(), ctx.copy(relationshipTypes = null)).items.single().decision.status)
        val world = db.universeDao().getUniverseById(1)!!
        db.universeDao().update(world.copy(customRelationshipTypes = "[\"우정\"]"))
        assertEquals(Status.STALE, prepare(operation(), ctx).items.single().decision.status)
        assertEquals(Status.INVALID_VALUE, prepare(operation()).items.single().decision.status)
    }

    @Test fun historyAddedAfterDeletePreviewBlocksTheEntireDeletion() = runBlocking {
        val old = relation()
        val prepared = prepare(operation(NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP))
        db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(relationshipId = old.id,
            year = 2030, relationshipType = "라이벌"))
        assertEquals(Status.STALE, executor.apply(prepared, "changed-history").single().status)
        assertEquals(old, db.characterRelationshipDao().getById(old.id))
        assertEquals(1, db.characterRelationshipChangeDao().getChangesForRelationshipList(old.id).size)
        assertTrue(db.naturalBatchJournalDao().operations("changed-history").isEmpty())
    }

    @Test fun mixedFieldAndRelationshipExecutionCanUndoBothKinds() = runBlocking {
        val fieldId = db.fieldDefinitionDao().insert(FieldDefinition(universeId = 1,
            key = "trait", name = "Trait", type = "TEXT"))
        val field = operation(id = "field").copy(kind = NaturalBatchPlan.Kind.SET_FIELD_VALUE,
            fieldRef = "f1", relatedRef = null, relationshipType = null, value = "kind")
        val ctx = context().copy(fields = mapOf("f1" to NaturalBatchContext.Field(fieldId, 1,
            "Trait", "trait", "TEXT", "{}")))
        val prepared = executor.preflight(review(field, operation()).snapshot(), ctx)
        assertTrue(prepared.items.all { it.decision.status == Status.READY })
        assertTrue(executor.apply(prepared, "mixed").all { it.status == Status.APPLIED })
        assertEquals("kind", db.characterFieldValueDao().getValue(100, fieldId)!!.value)
        assertEquals(setOf("field", "relationship"), db.naturalBatchJournalDao().operations("mixed").map { it.entityKind }.toSet())
        assertTrue(executor.undo("mixed").all { it.status == Status.UNDONE })
        assertNull(db.characterFieldValueDao().getValue(100, fieldId))
        assertTrue(db.characterRelationshipDao().getAllRelationships().isEmpty())
    }

    @Test fun workWithoutUniverseUsesGlobalRelationshipTypesAndCanUndo() = runBlocking {
        val book = db.novelDao().getNovelById(10)!!
        db.novelDao().update(book.copy(universeId = null))
        val prepared = prepare(operation())
        assertEquals(Status.READY, prepared.items.single().decision.status)
        assertEquals(Status.APPLIED, executor.apply(prepared, "global").single().status)
        assertEquals(Status.UNDONE, executor.undo("global").single().status)
    }
}
