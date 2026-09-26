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
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.repository.FactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NaturalBatchFactionExecutorTest {
    private lateinit var db: AppDatabase
    private lateinit var executor: NaturalBatchFieldExecutor
    private val input = NaturalBatchInput("faction-session", NaturalBatchInput.Scope(
        NaturalBatchInput.ScopeKind.WORK, 10), 0, 0, "Alice, Bob, Carol의 Dawn과 Night 소속을 정리한다")

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        executor = NaturalBatchFieldExecutor(db)
        db.universeDao().insert(Universe(id = 1, name = "World", code = "U"))
        db.novelDao().insert(Novel(id = 10, title = "Book", universeId = 1, code = "N"))
        listOf("Alice", "Bob", "Carol").forEachIndexed { index, name ->
            db.characterDao().insert(Character(id = 100L + index, name = name, novelId = 10, code = name))
        }
        db.factionDao().insert(Faction(id = 20, universeId = 1, name = "Dawn", code = "D", autoRelationType = "동료"))
        db.factionDao().insert(Faction(id = 21, universeId = 1, name = "Night", code = "K", autoRelationType = "친구"))
        Unit
    }
    @After fun close() { db.close() }
    private fun op(id: String = "proposal", target: String = "c1", faction: String = "a1",
        kind: NaturalBatchPlan.Kind = NaturalBatchPlan.Kind.JOIN_FACTION,
        mode: NaturalBatchPlan.LeaveMode? = null) = NaturalBatchPlan.Operation(id, kind, target,
        null, null, faction, null, null, null, null, null, null, null, null, mode,
        NaturalBatchPlan.Origin.EXTRACTED, NaturalBatchPlan.Evidence(listOf(input.segments().single().id), input.text, true))
    private fun state(vararg operations: NaturalBatchPlan.Operation): NaturalBatchReviewState {
        val review = NaturalBatchReviewState(input)
        assertTrue(review.accept(review.beginAnalysis(), NaturalBatchMerge(input.sessionId, 0, 0, operations.toList(),
            emptyList(), emptyList(), emptyList(), emptySet(), emptySet(), emptySet(), true)))
        operations.forEach { review.confirm(it.id); review.setSelected(it.id, true) }
        return review
    }
    private suspend fun context() = NaturalBatchContextLoader(db).load(input)
    private suspend fun prepare(vararg operations: NaturalBatchPlan.Operation) = executor.preflight(state(*operations).snapshot(), context())
    private suspend fun join(characterId: Long, factionId: Long = 20, year: Int? = null) =
        FactionRepository(db).addMember(factionId, characterId, year)
    private fun remove(target: String = "c1") = op(target = target, kind = NaturalBatchPlan.Kind.LEAVE_FACTION, mode = NaturalBatchPlan.LeaveMode.REMOVE)
    private fun depart() = op(kind = NaturalBatchPlan.Kind.LEAVE_FACTION, mode = NaturalBatchPlan.LeaveMode.DEPART)
        .copy(leaveYear = 2010, relationshipType = "라이벌", intensity = 7)

    @Test fun severalJoinsPreviewCombinedEffectsAndUndoInReverseOrder() = runBlocking {
        join(102)
        val prepared = prepare(op(id = "alice", target = "c1"), op(id = "bob", target = "c2"))
        assertTrue(prepared.items.all { it.decision.status == Status.READY })
        assertEquals(listOf(1, 2), prepared.items.map { it.decision.sideEffects })
        val result = executor.apply(prepared, "join-many")
        assertTrue(result.all { it.status == Status.APPLIED })
        assertEquals(listOf(1, 2), result.map { it.sideEffects })
        assertEquals(3, db.characterRelationshipDao().getByFactionList(20).size)
        assertTrue(executor.apply(prepared, "retry").all { it.status == Status.ALREADY_APPLIED })
        assertTrue(NaturalBatchFieldExecutor(db).undo("join-many").all { it.status == Status.UNDONE })
        assertEquals(listOf(102L), db.factionMembershipDao().getActiveMembershipsByFaction(20).map { it.characterId })
        assertTrue(db.characterRelationshipDao().getByFactionList(20).isEmpty())
        assertTrue(executor.undo("join-many").all { it.status == Status.ALREADY_UNDONE })
        assertTrue(executor.apply(prepared, "retry-after-undo").all { it.status == Status.ALREADY_APPLIED })
    }

    @Test fun rejoinPreservesDepartedRowsAndOtherFactionMemberships() = runBlocking {
        val past = FactionMembership(factionId = 20, characterId = 100, joinYear = 1990,
            leaveYear = 2000, leaveType = FactionMembership.LEAVE_DEPARTED)
        val pastId = db.factionMembershipDao().insert(past)
        join(100, 21)
        val prepared = prepare(op().copy(joinYear = 2005))
        assertEquals(Status.APPLIED, executor.apply(prepared, "rejoin").single().status)
        assertEquals(past.copy(id = pastId), db.factionMembershipDao().getById(pastId))
        assertNotNull(db.factionMembershipDao().getActiveMembership(21, 100))
        assertEquals(2, db.factionMembershipDao().getAllMembershipsForPair(20, 100).size)
        assertEquals(Status.UNDONE, executor.undo("rejoin").single().status)
        assertEquals(listOf(past.copy(id = pastId)), db.factionMembershipDao().getAllMembershipsForPair(20, 100))
        assertNotNull(db.factionMembershipDao().getActiveMembership(21, 100))
    }

    @Test fun reverseManualRelationshipIsKeptAndSkipIsReported() = runBlocking {
        join(101)
        val manual = CharacterRelationship(characterId1 = 101, characterId2 = 100, relationshipType = "동료", isBidirectional = false)
        val id = db.characterRelationshipDao().insert(manual)
        val prepared = prepare(op())
        assertEquals(0, prepared.items.single().decision.sideEffects)
        assertTrue(prepared.items.single().decision.reason.contains("기존 관계 1건 유지"))
        assertEquals(Status.APPLIED, executor.apply(prepared, "manual").single().status)
        assertTrue(db.characterRelationshipDao().getByFactionList(20).isEmpty())
        assertEquals(Status.UNDONE, executor.undo("manual").single().status)
        assertEquals(manual.copy(id = id), db.characterRelationshipDao().getById(id))
    }

    @Test fun removeRestoresActualMembershipLinkAndEventHistoryIds() = runBlocking {
        join(100); join(101); join(100, 21)
        val member = db.factionMembershipDao().getActiveMembership(20, 100)!!
        val relation = db.characterRelationshipDao().getByFactionList(20).single()
        val event = db.timelineDao().insert(TimelineEvent(universeId = 1, year = 2000, description = "Meeting", code = "E"))
        val changeId = db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(relationshipId = relation.id,
            year = 2000, relationshipType = "친구", eventId = event))
        val change = db.characterRelationshipChangeDao().getById(changeId)
        val prepared = prepare(remove())
        assertEquals(2, prepared.items.single().decision.sideEffects)
        assertEquals(Status.APPLIED, executor.apply(prepared, "remove").single().status)
        assertNull(db.characterRelationshipDao().getById(relation.id))
        assertNull(db.characterRelationshipChangeDao().getById(changeId))
        assertNotNull(db.factionMembershipDao().getActiveMembership(21, 100))
        assertEquals(Status.UNDONE, executor.undo("remove").single().status)
        assertEquals(member, db.factionMembershipDao().getById(member.id))
        assertEquals(relation, db.characterRelationshipDao().getById(relation.id))
        assertEquals(change, db.characterRelationshipChangeDao().getById(changeId))
    }

    @Test fun departureRequiresChosenRelationAndKeepsHistoryAndBaseLinks() = runBlocking {
        join(100, year = 2000); join(101)
        val relation = db.characterRelationshipDao().getByFactionList(20).single()
        val member = db.factionMembershipDao().getActiveMembership(20, 100)!!
        assertEquals(Status.INVALID_VALUE, prepare(depart().copy(relationshipType = null, intensity = null)).items.single().decision.status)
        assertEquals(Status.INVALID_VALUE, prepare(depart().copy(leaveYear = 1990)).items.single().decision.status)
        assertEquals(Status.APPLIED, executor.apply(prepare(depart()), "depart").single().status)
        assertEquals(relation, db.characterRelationshipDao().getById(relation.id))
        assertEquals(FactionMembership.LEAVE_DEPARTED, db.factionMembershipDao().getById(member.id)!!.leaveType)
        val added = db.characterRelationshipChangeDao().getChangesForRelationshipList(relation.id).single()
        assertEquals(2010, added.year); assertEquals("라이벌", added.relationshipType); assertEquals(7, added.intensity)
        assertEquals(Status.UNDONE, executor.undo("depart").single().status)
        assertEquals(member, db.factionMembershipDao().getById(member.id))
        assertTrue(db.characterRelationshipChangeDao().getChangesForRelationshipList(relation.id).isEmpty())
    }

    @Test fun rosterChangeAfterPreviewBlocksTheWholeFactionGroup() = runBlocking {
        val prepared = prepare(op(id = "alice"), op(id = "bob", target = "c2"))
        join(102)
        assertTrue(executor.apply(prepared, "stale").all { it.status == Status.STALE })
        assertEquals(listOf(102L), db.factionMembershipDao().getActiveMembershipsByFaction(20).map { it.characterId })
        assertTrue(db.naturalBatchJournalDao().operations("stale").isEmpty())
    }

    @Test fun failedSecondJoinRollsBackFirstJoinAndItsJournal() = runBlocking {
        val prepared = prepare(op(id = "alice"), op(id = "bob", target = "c2"))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_bob BEFORE INSERT ON faction_memberships WHEN NEW.characterId = 101 BEGIN SELECT RAISE(ABORT, 'failure'); END")
        assertTrue(executor.apply(prepared, "failed-group").all { it.status == Status.FAILED })
        assertTrue(db.factionMembershipDao().getMembershipsByFactionList(20).isEmpty())
        assertTrue(db.characterRelationshipDao().getByFactionList(20).isEmpty())
        assertTrue(db.naturalBatchJournalDao().operations("failed-group").isEmpty())
    }

    @Test fun laterPeerDepartureAndRelationshipHistoryBlockUndo() = runBlocking {
        join(101)
        executor.apply(prepare(op()), "alice")
        FactionRepository(db).departMember(20, 101, 2020, "라이벌", 8)
        assertEquals(Status.UNDO_CONFLICT, executor.undo("alice").single().status)
        assertNotNull(db.factionMembershipDao().getActiveMembership(20, 100))
        assertEquals(1, db.characterRelationshipDao().getByFactionList(20).size)
    }

    @Test fun reusedEntityCodesAndOldReviewSnapshotsBlockMutation() = runBlocking {
        val context = context()
        db.factionDao().update(db.factionDao().getById(20)!!.copy(code = "different-D"))
        assertEquals(Status.STALE, executor.preflight(state(op()).snapshot(), context).items.single().decision.status)
        assertEquals(Status.STALE, executor.preflight(state(op()).snapshot(), context().copy(memberships = null)).items.single().decision.status)
        val prepared = prepare(op())
        val character = db.characterDao().getCharacterById(100)!!
        db.characterDao().update(character.copy(code = "different-Alice"))
        assertEquals(Status.STALE, executor.apply(prepared, "wrong-owner").single().status)
    }

    @Test fun reusedEventAndMembershipIdsBlockRestoration() = runBlocking {
        join(100); join(101)
        val member = db.factionMembershipDao().getActiveMembership(20, 100)!!
        val relation = db.characterRelationshipDao().getByFactionList(20).single()
        val eventRow = TimelineEvent(universeId = 1, year = 2000, description = "Meeting", code = "E")
        val event = db.timelineDao().insert(eventRow)
        db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(relationshipId = relation.id, year = 2000, relationshipType = "친구", eventId = event))
        executor.apply(prepare(remove()), "remove-event")
        db.timelineDao().update(eventRow.copy(id = event, code = "different-E"))
        assertEquals(Status.UNDO_CONFLICT, executor.undo("remove-event").single().status)
        db.timelineDao().update(eventRow.copy(id = event))
        db.factionMembershipDao().insert(FactionMembership(id = member.id, factionId = 21, characterId = 102))
        assertEquals(Status.UNDO_CONFLICT, executor.undo("remove-event").single().status)
        assertNotNull(db.factionMembershipDao().getActiveMembership(21, 102))
    }

    @Test fun multipleFactionsAndMembershipSwitchPreserveIndependentScopes() = runBlocking {
        join(100); join(101)
        val prepared = prepare(remove(), op(id = "night", faction = "a2"))
        assertTrue(executor.apply(prepared, "switch").all { it.status == Status.APPLIED })
        assertNull(db.factionMembershipDao().getActiveMembership(20, 100))
        assertNotNull(db.factionMembershipDao().getActiveMembership(21, 100))
        assertTrue(executor.undo("switch").all { it.status == Status.UNDONE })
        assertNotNull(db.factionMembershipDao().getActiveMembership(20, 100))
        assertNull(db.factionMembershipDao().getActiveMembership(21, 100))
    }

    @Test fun overlappingManualOrOtherFactionProposalsRequireSeparateSelection() = runBlocking {
        join(101)
        val manual = op(id = "manual").copy(kind = NaturalBatchPlan.Kind.ADD_RELATIONSHIP,
            factionRef = null, relatedRef = "c2", relationshipType = "동료")
        assertTrue(prepare(op(), manual).items.all { it.decision.status == Status.STALE })
        db.factionDao().update(db.factionDao().getById(21)!!.copy(autoRelationType = "동료"))
        join(101, 21)
        assertTrue(prepare(op(), op(id = "night", faction = "a2")).items.all { it.decision.status == Status.STALE })
    }

    @Test fun explicitNoFactionIncludesEachCurrentFactionAndPreservesPastHistory() = runBlocking {
        join(100); join(100, 21)
        val words = input.copy(text = "Alice는 무소속이다")
        val context = NaturalBatchContextLoader(db).load(words)
        assertEquals(setOf(20L, 21L), context.factions.values.map { it.id }.toSet())
        assertEquals(2, context.memberships!!.size)
        val plan = NaturalBatchPlan(words.sessionId, 0, 0, listOf(remove().copy(evidence = NaturalBatchPlan.Evidence(
            listOf(words.segments().single().id), words.text, true))), emptyList(), emptyList(), emptyList(), emptyList(),
            setOf(words.segments().single().id), 0)
        assertEquals(1, NaturalBatchEvidenceGuard.check(plan, context).operations.size)
        assertTrue(NaturalBatchEvidenceGuard.check(plan.copy(operations = listOf(op().copy(evidence = plan.operations.single().evidence))), context).operations.isEmpty())
    }

    @Test fun largeUnicodeUndoGuardIsSplitBelowCursorWindowAndRestoresEveryRow() = runBlocking {
        join(100, year = 2000)
        val description = "설정😀".repeat(400)
        val peerIds = (1000L until 1300L).toList()
        peerIds.forEach { id ->
            db.characterDao().insert(Character(id = id, name = "Peer $id", novelId = 10, code = "P$id"))
            db.factionMembershipDao().insert(FactionMembership(factionId = 20, characterId = id))
        }
        db.characterRelationshipDao().insertAll(peerIds.map { id ->
            CharacterRelationship(characterId1 = 100, characterId2 = id, relationshipType = "동료",
                description = description, factionId = 20)
        })
        val prepared = prepare(depart())
        assertEquals(300, prepared.items.single().decision.sideEffects)
        assertEquals(Status.APPLIED, executor.apply(prepared, "large").single().status)
        val record = db.naturalBatchJournalDao().operations("large").single()
        val rows = db.naturalBatchJournalDao().changes(record.operationKey)
        val parts = rows.filter { it.rowKind == "faction_guard" }
        assertTrue(record.guardJson!!.length < 100)
        assertTrue(parts.size > 1)
        assertTrue(parts.sumOf { it.afterJson!!.toByteArray(Charsets.UTF_8).size } > 2 * 1024 * 1024)
        assertTrue(parts.all { it.afterJson!!.toByteArray(Charsets.UTF_8).size < 200_000 })
        assertEquals(Status.UNDONE, executor.undo("large").single().status)
        assertTrue(db.characterRelationshipDao().getByFactionList(20).all { it.description == description })
        assertTrue(db.characterRelationshipChangeDao().getChangesForRelationships(
            db.characterRelationshipDao().getByFactionList(20).map { it.id }).isEmpty())
        assertNotNull(db.factionMembershipDao().getActiveMembership(20, 100))
    }
}
