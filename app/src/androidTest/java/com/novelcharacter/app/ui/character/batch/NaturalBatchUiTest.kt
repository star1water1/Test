package com.novelcharacter.app.ui.character.batch

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.MainActivity
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.ReviewJournal
import com.novelcharacter.app.ai.NaturalBatchContextLoader
import com.novelcharacter.app.ai.NaturalBatchContextSnapshot
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor
import com.novelcharacter.app.ai.NaturalBatchInput
import com.novelcharacter.app.ai.NaturalBatchPlan
import com.novelcharacter.app.ai.NaturalBatchMerge
import com.novelcharacter.app.ai.NaturalBatchRelationshipEdits
import com.novelcharacter.app.ai.NaturalBatchReviewState
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.Character
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NaturalBatchUiTest {
    private data class SavedReview(
        val review: NaturalBatchReviewState.Snapshot, val context: NaturalBatchContextSnapshot,
        val executions: List<NaturalBatchViewModel.ExecutionRecord> = emptyList(),
        val results: List<NaturalBatchFieldExecutor.Decision>? = null,
        val scrollPosition: Int = 0, val expanded: Set<String> = emptySet()
    )

    private fun textViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(textViews(view.getChildAt(index)))
    }

    private fun awaitIdle(scenario: ActivityScenario<MainActivity>, done: (NaturalBatchViewModel) -> Boolean) {
        repeat(150) {
            instrumentation.waitForIdleSync()
            var complete = false
            scenario.onActivity { activity ->
                screen(activity)?.let { complete = done(ViewModelProvider(it)[NaturalBatchViewModel::class.java]) }
            }
            if (complete) return
            Thread.sleep(100)
        }
        error("Natural batch action did not finish")
    }

    @Test fun relationshipReviewShowsDirectionAndPreservesEditApplyUndoAcrossRecreation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val journal = ReviewJournal(File(app.noBackupFilesDir, "creative-reviews"))
        assumeTrue("Do not replace an existing user review", journal.revision("natural-batch:active") == null)
        val universeId = runBlocking(Dispatchers.IO) { db.universeDao().insert(Universe(name = "관계 UI 검증 세계관")) }
        val novelId = runBlocking(Dispatchers.IO) { db.novelDao().insert(Novel(title = "관계 UI 검증 작품", universeId = universeId)) }
        val input = NaturalBatchInput.create(NaturalBatchInput.Scope(NaturalBatchInput.ScopeKind.WORK, novelId),
            "Alice와 Bob은 친구다")
        try {
            val context = runBlocking(Dispatchers.IO) {
                db.characterDao().insert(Character(name = "Alice", novelId = novelId))
                db.characterDao().insert(Character(name = "Bob", novelId = novelId))
                NaturalBatchContextLoader(db).load(input)
            }
            val operation = NaturalBatchPlan.Operation("ui-relation", NaturalBatchPlan.Kind.ADD_RELATIONSHIP,
                "c1", null, "c2", null, null, null, "친구", "원문 설명", 5, true, null, null, null,
                NaturalBatchPlan.Origin.EXTRACTED, NaturalBatchPlan.Evidence(listOf(input.segments().single().id), input.text, true))
            val state = NaturalBatchReviewState(input)
            val request = state.beginAnalysis()
            assertTrue(state.accept(request, NaturalBatchMerge(input.sessionId, 0, 0, listOf(operation),
                emptyList(), emptyList(), emptyList(), emptySet(), emptySet(), emptySet(), true)))
            journal.write("natural-batch:active", SavedReview(state.snapshot(), NaturalBatchContextSnapshot.from(context)), null)
            launch(novelId).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = screen(activity)!!
                    val model = ViewModelProvider(fragment)[NaturalBatchViewModel::class.java]
                    val texts = textViews(fragment.requireView())
                    assertTrue(texts.any { it.text.contains("Alice ↔ Bob") })
                    assertTrue(texts.filterIsInstance<CheckBox>().any { it.isEnabled && !it.isChecked && it.text.contains("관계 추가") })
                    model.selectExtracted()
                    assertTrue(model.snapshot!!.selected.isEmpty())
                    model.select(operation.id, true)
                    model.editRelationship(operation.id, NaturalBatchRelationshipEdits.Draft("라이벌", "직접 수정", 7, false))
                    assertTrue(model.snapshot!!.selected.isEmpty())
                }
                scenario.recreate(); awaitScreen(scenario)
                scenario.onActivity { activity ->
                    val fragment = screen(activity)!!
                    val model = ViewModelProvider(fragment)[NaturalBatchViewModel::class.java]
                    assertTrue(textViews(fragment.requireView()).any { it.text.contains("Alice → Bob") && it.text.contains("직접 수정") })
                    model.select(operation.id, true); model.preflight()
                }
                awaitIdle(scenario) { !it.busy && it.preview != null }
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(screen(activity)!!)[NaturalBatchViewModel::class.java]
                    assertEquals(NaturalBatchFieldExecutor.Status.READY, model.preview!!.single().status)
                    model.applyPrepared()
                }
                awaitIdle(scenario) { !it.busy && it.results != null }
                scenario.recreate(); awaitScreen(scenario)
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(screen(activity)!!)[NaturalBatchViewModel::class.java]
                    assertTrue(model.canUndo)
                    assertEquals(NaturalBatchFieldExecutor.Status.APPLIED, model.results!!.single().status)
                    model.undo(model.undoChoices.single().id)
                }
                awaitIdle(scenario) { !it.busy && it.results?.singleOrNull()?.status == NaturalBatchFieldExecutor.Status.UNDONE }
            }
            val relationships = runBlocking(Dispatchers.IO) {
                context.characters.values.flatMap { db.characterRelationshipDao().getRelationshipsForCharacterList(it.id) }
            }
            assertTrue(relationships.isEmpty())
        } finally {
            journal.clear("natural-batch:active", journal.revision("natural-batch:active"), listOf("natural-batch:${input.sessionId}"))
            runBlocking(Dispatchers.IO) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM natural_batch_operations WHERE sessionId = ?",
                    arrayOf(input.sessionId))
                db.novelDao().deleteById(novelId)
                db.universeDao().getUniverseById(universeId)?.let { db.universeDao().delete(it) }
            }
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val db get() = AppDatabase.getDatabase(app)

    private fun screen(activity: MainActivity): NaturalBatchFragment? {
        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return host.childFragmentManager.primaryNavigationFragment as? NaturalBatchFragment
    }

    private fun editor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            editor(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun awaitScreen(scenario: ActivityScenario<MainActivity>): NaturalBatchFragment {
        var fragment: NaturalBatchFragment? = null
        repeat(80) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val current = screen(activity)
                if (current != null && ViewModelProvider(current)[NaturalBatchViewModel::class.java]
                        .snapshot != null) fragment = current
            }
            if (fragment != null) return fragment!!
            Thread.sleep(100)
        }
        error("Natural batch screen did not load")
    }

    private fun launch(workId: Long): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            host.navController.navigate(R.id.naturalBatchFragment,
                Bundle().apply { putLong("novelId", workId) })
        }
        awaitScreen(scenario)
        return scenario
    }

    @Test fun sourceAndScopeSurviveRotationAndFreshEditorWithoutPaidRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName,
                Manifest.permission.POST_NOTIFICATIONS)
        }
        val journal = ReviewJournal(File(app.noBackupFilesDir, "creative-reviews"))
        assumeTrue("Do not replace an existing user review",
            journal.revision("natural-batch:active") == null)
        val universe = Universe(name = "M4 UI 검증 세계관")
        val universeId = runBlocking(Dispatchers.IO) { db.universeDao().insert(universe) }
        val novelId = runBlocking(Dispatchers.IO) {
            db.novelDao().insert(Novel(title = "M4 UI 검증 작품", universeId = universeId))
        }
        var sessionId: String? = null
        try {
            launch(novelId).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = screen(activity)!!
                    val model = ViewModelProvider(fragment)[NaturalBatchViewModel::class.java]
                    assertEquals(novelId, model.snapshot!!.input.scope.id)
                    sessionId = model.snapshot!!.input.sessionId
                    val input = editor(fragment.requireView())
                    assertNotNull(input)
                    input!!.setText("민아의 직업은 기자다.")
                    assertFalse(model.busy)
                }
                scenario.recreate()
                awaitScreen(scenario)
                scenario.onActivity { activity ->
                    val fragment = screen(activity)!!
                    assertEquals("민아의 직업은 기자다.", editor(fragment.requireView())!!.text.toString())
                    assertEquals(sessionId, ViewModelProvider(fragment)[NaturalBatchViewModel::class.java]
                        .snapshot!!.input.sessionId)
                }
            }
            launch(novelId).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = screen(activity)!!
                    val restored = ViewModelProvider(fragment)[NaturalBatchViewModel::class.java].snapshot!!
                    assertEquals(sessionId, restored.input.sessionId)
                    assertEquals("민아의 직업은 기자다.", restored.input.text)
                    assertEquals(novelId, restored.input.scope.id)
                }
            }
        } finally {
            journal.clear("natural-batch:active", journal.revision("natural-batch:active"),
                sessionId?.let { listOf("natural-batch:$it") }.orEmpty())
            runBlocking(Dispatchers.IO) {
                db.novelDao().deleteById(novelId)
                db.universeDao().getUniverseById(universeId)?.let { db.universeDao().delete(it) }
            }
        }
    }
}
