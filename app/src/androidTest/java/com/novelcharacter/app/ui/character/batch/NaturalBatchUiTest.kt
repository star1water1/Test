package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.MainActivity
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.ReviewJournal
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NaturalBatchUiTest {
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
