package com.novelcharacter.app.ui.character.batch

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.ai.*
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Scripted responses exercise the real ViewModel, parser, journal, scope loader and retry path. */
@RunWith(AndroidJUnit4::class)
class NaturalBatchChunkExecutionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val db get() = AppDatabase.getDatabase(app)
    private val journal get() = ReviewJournal(File(app.noBackupFilesDir, "creative-reviews"))

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        repeat(200) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        error("Chunk analysis did not reach the expected state")
    }

    private fun fixture(action: (NaturalBatchInput) -> Unit) {
        assumeTrue("Do not replace an existing user review", journal.revision("natural-batch:active") == null)
        val universeId = runBlocking(Dispatchers.IO) { db.universeDao().insert(Universe(name = "청크 검증 세계관")) }
        val workId = runBlocking(Dispatchers.IO) {
            db.novelDao().insert(Novel(title = "청크 검증 작품", universeId = universeId))
        }
        val text = (1..10).flatMap { person -> (1..5).map { field -> "인물${person}의 속성${field}은 값${person}_${field}이다." } }
            .joinToString("\n\n")
        val input = NaturalBatchInput.create(NaturalBatchInput.Scope(NaturalBatchInput.ScopeKind.WORK, workId), text)
        try {
            runBlocking(Dispatchers.IO) {
                (1..10).forEach { db.characterDao().insert(Character(name = "인물$it", novelId = workId)) }
                (1..5).forEach { db.fieldDefinitionDao().insert(FieldDefinition(universeId = universeId,
                    key = "chunk_test_$it", name = "속성$it", type = "TEXT")) }
            }
            action(input)
        } finally {
            journal.clear("natural-batch:active", journal.revision("natural-batch:active"), listOf("natural-batch:${input.sessionId}"))
            runBlocking(Dispatchers.IO) {
                db.novelDao().deleteById(workId)
                db.universeDao().getUniverseById(universeId)?.let { db.universeDao().delete(it) }
            }
        }
    }

    private fun response(request: AiRequest): AiResult.Success {
        val data = JSONObject(request.messages.single().text.substringAfter("Untrusted data block (JSON):\n"))
        val segments = data.getJSONArray("segments")
        val characters = data.getJSONArray("characters")
        val fields = data.getJSONArray("fields")
        val operations = JSONArray()
        val status = JSONArray()
        for (index in 0 until segments.length()) {
            val segment = segments.getJSONObject(index)
            val text = segment.getString("text")
            val character = (0 until characters.length()).map { characters.getJSONObject(it) }
                .filter { text.contains(it.getString("name")) }.maxBy { it.getString("name").length }
            val field = (0 until fields.length()).map { fields.getJSONObject(it) }
                .single { text.contains(it.getString("name")) }
            operations.put(JSONObject().put("id", "op$index").put("kind", "SET_FIELD_VALUE")
                .put("targetRef", character.getString("ref")).put("fieldRef", field.getString("ref"))
                .put("value", Regex("값[0-9_]+").find(text)!!.value).put("origin", "EXTRACTED")
                .put("segmentIds", JSONArray().put(segment.getString("id"))).put("quote", text))
            status.put(JSONObject().put("segmentId", segment.getString("id")).put("status", "PROCESSED"))
        }
        val result = JSONObject().put("schemaVersion", 1).put("sessionId", data.getString("sessionId"))
            .put("scopeRevision", data.getLong("scopeRevision")).put("inputRevision", data.getLong("inputRevision"))
            .put("operations", operations).put("constraints", JSONArray()).put("unresolved", JSONArray())
            .put("suggestionNotes", JSONArray()).put("segmentStatus", status)
        return AiResult.Success(result.toString(), "scripted")
    }

    @Test fun partialFailureRetryPreservesSuccessDecisionsAndRecoversWithoutAutomaticRequests() = fixture { input ->
        val calls = AtomicInteger()
        var loads = 0
        val analyzer = NaturalBatchAnalyzer({ loads++; NaturalBatchContextLoader(db).load(it) }, { request ->
            if (calls.incrementAndGet() == 2) AiResult.Success("{}", "scripted", truncated = true)
            else response(request)
        }, { 4096 })
        var model: NaturalBatchViewModel? = null
        var store = ViewModelStore()
        fun open() {
            main {
                model = NaturalBatchViewModel(app, analyzer)
                store.put("model", model!!)
                model!!.open(input.scope.id)
            }
            await { model!!.snapshot != null && !model!!.busy }
        }
        try {
            open()
            main { model!!.editInput(input.text); model!!.analyze() }
            await { !model!!.busy }
            assertEquals(5, calls.get())
            assertEquals(1, loads)
            lateinit var keptId: String
            main {
                assertEquals(38, model!!.snapshot!!.plan!!.operations.size)
                assertEquals(12, model!!.retrySegments.size)
                assertEquals(1, model!!.requestCount(retry = true))
                model!!.selectExtracted()
                assertTrue(model!!.snapshot!!.selected.isEmpty())
                keptId = model!!.snapshot!!.plan!!.operations.first().id
                model!!.editProposal(keptId, "직접 고친 값"); model!!.select(keptId, true)
                model!!.toggleExpanded(keptId); model!!.recordScroll(9)
                store.clear()
            }
            store = ViewModelStore(); open()
            assertEquals(5, calls.get())
            main {
                assertEquals(setOf(keptId), model!!.snapshot!!.selected)
                assertEquals("직접 고친 값", model!!.snapshot!!.edits[keptId])
                assertEquals(9, model!!.scrollPosition)
                assertTrue(model!!.isExpanded(keptId))
                model!!.analyze(retry = true)
            }
            await { !model!!.busy }
            main {
                assertTrue(model!!.snapshot!!.plan!!.complete)
                assertEquals(50, model!!.snapshot!!.plan!!.operations.size)
                assertEquals(setOf(keptId), model!!.snapshot!!.selected)
                assertEquals("직접 고친 값", model!!.snapshot!!.edits[keptId])
                assertTrue(model!!.retrySegments.isEmpty())
                assertEquals(9, model!!.scrollPosition)
                assertEquals(10, model!!.analysisContext!!.characters.size)
            }
            assertEquals(6, calls.get())
            assertEquals("Retries use the frozen reference map", 1, loads)
        } finally { main { store.clear() } }
    }

    @Test fun cancelWaitsForPaidResponseAndStopsBeforeAnotherRequest() = fixture { input ->
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val analyzer = NaturalBatchAnalyzer(NaturalBatchContextLoader(db)::load, { request ->
            calls.incrementAndGet(); gate.await(); response(request)
        }, { 4096 })
        lateinit var model: NaturalBatchViewModel
        val store = ViewModelStore()
        try {
            main { model = NaturalBatchViewModel(app, analyzer); store.put("model", model); model.open(input.scope.id) }
            await { model.snapshot != null && !model.busy }
            main { model.editInput(input.text); model.analyze() }
            await { calls.get() == 1 }
            main { model.stopAfterRequest() }
            gate.complete(Unit)
            await { !model.busy }
            main {
                assertEquals(12, model.snapshot!!.plan!!.operations.size)
                assertEquals(38, model.snapshot!!.plan!!.incompleteSegments.size)
                assertEquals(1, model.analysisDone)
                assertFalse(model.snapshot!!.plan!!.complete)
            }
            assertEquals(1, calls.get())
        } finally { gate.complete(Unit); main { store.clear() } }
    }

    @Test fun providerFailureStopsRemainingRequestsAndKeepsTheirOriginalText() = fixture { input ->
        val calls = AtomicInteger()
        val analyzer = NaturalBatchAnalyzer(NaturalBatchContextLoader(db)::load, {
            calls.incrementAndGet(); AiResult.Failure(AiErrorKind.RATE_LIMITED)
        }, { 4096 })
        lateinit var model: NaturalBatchViewModel
        val store = ViewModelStore()
        try {
            main { model = NaturalBatchViewModel(app, analyzer); store.put("model", model); model.open(input.scope.id) }
            await { model.snapshot != null && !model.busy }
            main { model.editInput(input.text); model.analyze() }
            await { !model.busy }
            main {
                assertEquals(input.text, model.snapshot!!.input.text)
                assertEquals(50, model.snapshot!!.plan!!.incompleteSegments.size)
                assertEquals(50, model.analysisFailures.size)
                assertTrue(model.snapshot!!.plan!!.operations.isEmpty())
            }
            assertEquals(1, calls.get())
        } finally { main { store.clear() } }
    }
}
