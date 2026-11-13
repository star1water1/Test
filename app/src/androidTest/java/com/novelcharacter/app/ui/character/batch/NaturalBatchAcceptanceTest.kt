package com.novelcharacter.app.ui.character.batch

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.ai.AiRequest
import com.novelcharacter.app.ai.AiResult
import com.novelcharacter.app.ai.NaturalBatchAnalyzer
import com.novelcharacter.app.ai.NaturalBatchContextLoader
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor.Status
import com.novelcharacter.app.ai.NaturalBatchPlan
import com.novelcharacter.app.ai.NaturalBatchPlan.Kind
import com.novelcharacter.app.ai.ReviewJournal
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.repository.FactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * M7 통합 회귀 — 필드·관계·세력이 섞인 한 번의 입력이 분석 → 검토 → 적용 전 재검증 → 적용 →
 * 화면 재생성 → 되돌리기를 실제 ViewModel·parser·journal·Room으로 끝까지 지나는지 본다.
 * AI 응답만 대본이다.
 */
@RunWith(AndroidJUnit4::class)
class NaturalBatchAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val db get() = AppDatabase.getDatabase(app)
    private val journal get() = ReviewJournal(File(app.noBackupFilesDir, "creative-reviews"))

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun <T> io(action: suspend () -> T): T = runBlocking(Dispatchers.IO) { action() }
    private fun await(condition: () -> Boolean) {
        repeat(200) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        error("Natural batch flow did not reach the expected state")
    }

    private val text = listOf(
        "Alice의 직업은 기자다.",
        "Alice와 Bob은 친구다.",
        "Carol은 Dawn에 가입했다.",
        "Bob의 직업은 아마 탐정일지도 모른다.",
        "Dave의 직업은 의사다.",
        "Carol의 직업은 화가다."
    ).joinToString("\n\n")

    /** 문단마다 한 항목. 참조는 요청에 실린 것만 쓰고, 요청에 없는 인물은 unresolved로 돌린다. */
    private fun response(request: AiRequest): AiResult.Success {
        val data = JSONObject(request.messages.single().text.substringAfter("Untrusted data block (JSON):\n"))
        fun refs(key: String) = data.getJSONArray(key).let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
                .associate { it.getString("name") to it.getString("ref") }
        }
        val characters = refs("characters")
        val fields = refs("fields")
        val factions = refs("factions")
        val segments = data.getJSONArray("segments")
        val operations = JSONArray()
        val unresolved = JSONArray()
        val status = JSONArray()
        for (index in 0 until segments.length()) {
            val segment = segments.getJSONObject(index)
            val id = segment.getString("id")
            val body = segment.getString("text")
            fun operation(kind: String, target: String, origin: String = "EXTRACTED") = JSONObject()
                .put("id", "op$index").put("kind", kind).put("targetRef", characters.getValue(target))
                .put("origin", origin).put("segmentIds", JSONArray().put(id)).put("quote", body)
            fun job(target: String, value: String, origin: String = "EXTRACTED") =
                operation("SET_FIELD_VALUE", target, origin).put("fieldRef", fields.getValue("직업")).put("value", value)
            when {
                body.startsWith("Alice의") -> operations.put(job("Alice", "기자"))
                body.startsWith("Alice와") -> operations.put(operation("ADD_RELATIONSHIP", "Alice")
                    .put("relatedRef", characters.getValue("Bob")).put("relationshipType", "친구"))
                body.startsWith("Carol은") -> operations.put(operation("JOIN_FACTION", "Carol")
                    .put("factionRef", factions.getValue("Dawn")))
                body.startsWith("Bob의") -> operations.put(job("Bob", "탐정", "CREATIVE"))
                body.startsWith("Dave") -> unresolved.put(JSONObject().put("id", "u$index")
                    .put("segmentIds", JSONArray().put(id)).put("text", body).put("reason", "범위에 없는 인물"))
                else -> operations.put(job("Carol", "화가"))
            }
            status.put(JSONObject().put("segmentId", id).put("status", "PROCESSED"))
        }
        return AiResult.Success(JSONObject().put("schemaVersion", 1).put("sessionId", data.getString("sessionId"))
            .put("scopeRevision", data.getLong("scopeRevision")).put("inputRevision", data.getLong("inputRevision"))
            .put("operations", operations).put("constraints", JSONArray()).put("unresolved", unresolved)
            .put("suggestionNotes", JSONArray()).put("segmentStatus", status).toString(), "scripted")
    }

    @Test fun mixedFieldRelationshipAndFactionInputSurvivesReviewApplyRecreationAndUndo() {
        assumeTrue("Do not replace an existing user review", journal.revision("natural-batch:active") == null)
        val universeId = io { db.universeDao().insert(Universe(name = "M7 통합 검증 세계관")) }
        val workId = io { db.novelDao().insert(Novel(title = "M7 통합 검증 작품", universeId = universeId)) }
        val alice = io { db.characterDao().insert(Character(name = "Alice", novelId = workId)) }
        val bob = io { db.characterDao().insert(Character(name = "Bob", novelId = workId)) }
        val carol = io { db.characterDao().insert(Character(name = "Carol", novelId = workId)) }
        val jobField = io { db.fieldDefinitionDao().insert(FieldDefinition(universeId = universeId,
            key = "m7_job", name = "직업", type = "TEXT")) }
        val dawn = io { db.factionDao().insert(Faction(universeId = universeId, name = "Dawn", autoRelationType = "동료")) }
        io {
            db.characterFieldValueDao().insert(CharacterFieldValue(characterId = carol,
                fieldDefinitionId = jobField, value = "학생"))
            FactionRepository(db).addMember(dawn, bob)
        }
        val calls = AtomicInteger()
        val analyzer = NaturalBatchAnalyzer(NaturalBatchContextLoader(db)::load,
            { calls.incrementAndGet(); response(it) }, { 4096 })
        var model: NaturalBatchViewModel? = null
        var store = ViewModelStore()
        var sessionId: String? = null
        var executionId: String? = null
        fun open() {
            main {
                model = NaturalBatchViewModel(app, analyzer)
                store.put("model", model!!)
                model!!.open(workId)
            }
            await { model!!.snapshot != null && !model!!.busy }
        }
        fun operations() = model!!.snapshot!!.plan!!.operations
        fun the(kind: Kind, origin: NaturalBatchPlan.Origin = NaturalBatchPlan.Origin.EXTRACTED, value: String? = null) =
            operations().single { it.kind == kind && it.origin == origin && (value == null || it.value == value) }
        try {
            open()
            main {
                sessionId = model!!.snapshot!!.input.sessionId
                model!!.editInput(text); model!!.analyze()
            }
            await { !model!!.busy }
            val paid = calls.get()
            assertEquals(model!!.requestCount(), paid)

            // 검토: 일괄 선택은 인용이 맞는 추출 필드만 담고, 창작·관계·세력은 하나씩 고른다.
            lateinit var aliceJob: String
            lateinit var carolJob: String
            lateinit var friend: String
            lateinit var join: String
            lateinit var guess: String
            main {
                val plan = model!!.snapshot!!.plan!!
                assertTrue(plan.complete)
                assertEquals(5, plan.operations.size)
                assertEquals("Dave의 직업은 의사다.", plan.unresolved.single().text)
                assertTrue(model!!.snapshot!!.selected.isEmpty())
                aliceJob = the(Kind.SET_FIELD_VALUE, value = "기자").id
                carolJob = the(Kind.SET_FIELD_VALUE, value = "화가").id
                friend = the(Kind.ADD_RELATIONSHIP).id
                join = the(Kind.JOIN_FACTION).id
                guess = the(Kind.SET_FIELD_VALUE, NaturalBatchPlan.Origin.CREATIVE).id
                model!!.selectExtracted()
                assertEquals(setOf(aliceJob, carolJob), model!!.snapshot!!.selected)
                model!!.select(friend, true); model!!.select(join, true)
                assertFalse(guess in model!!.snapshot!!.selected)
                store.clear()
            }

            // 재생성 뒤에도 선택이 남고 유료 요청은 다시 나가지 않는다.
            store = ViewModelStore(); open()
            assertEquals(paid, calls.get())
            main {
                assertEquals(setOf(aliceJob, carolJob, friend, join), model!!.snapshot!!.selected)
                model!!.preflight()
            }
            await { !model!!.busy && model!!.preview != null }
            main {
                assertEquals(4, model!!.preview!!.size)
                // 화면은 목록의 동일성으로 확인 창을 한 번만 띄운다 — 읽을 때마다 새 목록이면 창이 겹친다.
                assertSame(model!!.preview, model!!.preview)
                assertTrue(model!!.preview!!.all { it.status == Status.READY })
                assertEquals("학생", model!!.preview!!.single { it.operationId == carolJob }.before)
            }

            // 확인과 적용 사이에 다른 창이 Carol의 직업을 바꿨다 — 그 항목만 멈춘다.
            io {
                val row = db.characterFieldValueDao().getValue(carol, jobField)!!
                db.characterFieldValueDao().update(row.copy(value = "교사"))
            }
            main { model!!.applyPrepared() }
            await { !model!!.busy && model!!.results != null }
            main {
                val results = model!!.results!!.associate { it.operationId to it.status }
                assertEquals(Status.STALE, results[carolJob])
                assertEquals(setOf(aliceJob, friend, join), results.filterValues { it == Status.APPLIED }.keys)
                executionId = model!!.undoChoices.single().id
                assertEquals(3, model!!.undoChoices.single().applied)
            }
            io {
                assertEquals("기자", db.characterFieldValueDao().getValue(alice, jobField)!!.value)
                assertEquals("교사", db.characterFieldValueDao().getValue(carol, jobField)!!.value)
                assertNull(db.characterFieldValueDao().getValue(bob, jobField))
                val manual = db.characterRelationshipDao().getRelationshipsForCharacterList(alice).single()
                assertEquals("친구", manual.relationshipType)
                assertNull(manual.factionId)
                assertNotNull(db.factionMembershipDao().getActiveMembership(dawn, carol))
                assertEquals(setOf(bob, carol), db.characterRelationshipDao().getByFactionList(dawn)
                    .single().let { setOf(it.characterId1, it.characterId2) })
                assertEquals(setOf("field", "relationship", "faction"),
                    db.naturalBatchJournalDao().operations(executionId!!).map { it.entityKind }.toSet())
            }

            // 재생성 뒤 실행 기록을 다시 읽어 되돌린다. 건드리지 않은 값과 남의 편집은 그대로다.
            main { store.clear() }
            store = ViewModelStore(); open()
            main {
                assertTrue(model!!.canUndo)
                assertEquals(executionId, model!!.undoChoices.single().id)
                model!!.undo(executionId!!)
            }
            await { !model!!.busy && model!!.results != null }
            main {
                assertEquals(3, model!!.results!!.size)
                assertTrue(model!!.results!!.all { it.status == Status.UNDONE })
                assertFalse(model!!.canUndo)
            }
            io {
                assertNull(db.characterFieldValueDao().getValue(alice, jobField))
                assertEquals("교사", db.characterFieldValueDao().getValue(carol, jobField)!!.value)
                assertTrue(db.characterRelationshipDao().getRelationshipsForCharacterList(alice).isEmpty())
                assertNull(db.factionMembershipDao().getActiveMembership(dawn, carol))
                assertNotNull(db.factionMembershipDao().getActiveMembership(dawn, bob))
                assertTrue(db.characterRelationshipDao().getByFactionList(dawn).isEmpty())
            }
            assertEquals(paid, calls.get())
        } finally {
            main { store.clear() }
            journal.clear("natural-batch:active", journal.revision("natural-batch:active"),
                sessionId?.let { listOf("natural-batch:$it") }.orEmpty())
            io {
                sessionId?.let {
                    db.openHelper.writableDatabase.execSQL(
                        "DELETE FROM natural_batch_operations WHERE sessionId = ?", arrayOf(it))
                }
                db.novelDao().deleteById(workId)
                db.universeDao().getUniverseById(universeId)?.let { db.universeDao().delete(it) }
            }
        }
    }
}
