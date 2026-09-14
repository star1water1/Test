package com.novelcharacter.app.ui.common

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.ai.*
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewUiContextTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun launch()=ActivityScenario.launch<ReviewUiTestActivity>(Intent(instrumentation.targetContext,ReviewUiTestActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun all(view:View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap {all(view.getChildAt(it))} else emptyList())
    private fun buttons(dialog:AlertDialog,text:String)=all(dialog.window!!.decorView).filterIsInstance<TextView>().filter {it.text.toString()==text}

    @Test fun lateResultReusesDialogAndEditorWithoutLosingDraftSelectionOrFocus() {
        launch().use { scenario ->
            lateinit var before: AlertDialog
            lateinit var editor: EditText
            var screenY = 0
            lateinit var request: FieldSuggestionReviewState.Request
            scenario.onActivity { a ->
                before=a.review
                request=a.model.state.beginRequest(listOf("1","8"),a.model.outcome.suggestions)
                a.model.running.value=true
                buttons(before,"직접 수정")[7].performClick()
                editor=all(before.window!!.decorView).filterIsInstance<EditText>().first {it.isShown}
                editor.setText("내가 쓰던 긴 수정 내용");editor.setSelection(3,7);editor.requestFocus()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { a ->
                screenY=IntArray(2).also {editor.getLocationOnScreen(it)}[1]
                val reply=SuggestOutcome(listOf(Suggestion("1",(1..20).joinToString("\n") {"앞 행의 새 값 $it"},"새 이유"),Suggestion("8","늦은 AI 값","새 이유")),0,emptyList(),emptyList(),0,0)
                a.model.state.receive(request,reply)
                a.model.outcome=FieldSuggestionReviewState.merge(a.model.outcome,reply)
                assertSame(before,a.showReview())
                assertTrue(editor.isAttachedToWindow);assertEquals("내가 쓰던 긴 수정 내용",editor.text.toString())
                assertEquals(3,editor.selectionStart);assertEquals(7,editor.selectionEnd);assertTrue(editor.hasFocus())
                assertEquals(1,a.model.state.candidates("8").size)
                assertEquals(0,a.model.refinements)
                a.model.running.value=false
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val after=IntArray(2).also {editor.getLocationOnScreen(it)}[1]
                assertEquals("Earlier row growth must not move the active editor",screenY,after)
            }
        }
    }
    @Test fun recreatedReviewRestoresEighthRowDisclosureDraftAndSelection() {
        launch().use { scenario ->
            scenario.onActivity { a ->
                buttons(a.review,"근거·메모 펼치기")[7].performClick()
                buttons(a.review,"직접 수정")[7].performClick()
                val editor=all(a.review.window!!.decorView).filterIsInstance<EditText>().first {it.isShown}
                editor.setText("회전 뒤에도 같은 위치");editor.setSelection(2,6);editor.requestFocus()
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { a ->
                val editor=all(a.review.window!!.decorView).filterIsInstance<EditText>().first {it.isShown}
                assertEquals("회전 뒤에도 같은 위치",editor.text.toString())
                assertEquals(2,editor.selectionStart);assertEquals(6,editor.selectionEnd);assertTrue(editor.hasFocus())
                assertTrue("why:8" in a.model.state.viewport!!.expanded)
                assertEquals(1,buttons(a.review,"근거·메모 접기").size)
                assertEquals(0,a.model.refinements)
            }
        }
    }
    @Test fun longTranscriptComparisonAndExpandedEditorShareOneDraftWithoutSubmitting() {
        launch().use { scenario ->
            val key="m7-voice:${java.util.UUID.randomUUID()}"
            val original=(1..300).joinToString("\n") {"$it 번째 발화와 정정할 고유명사"}
            lateinit var voice:VoiceInputSheet
            lateinit var model:VoiceInputViewModel
            scenario.onActivity { a ->
                a.review.dismiss()
                model=ViewModelProvider(a.host).get("voice:$key",VoiceInputViewModel::class.java)
                model.bind(key);model.session.deviceResult(original);model.editDraft(original)
                VoiceInputSheet.open(a.host,key,emptyList())
                voice=a.host.childFragmentManager.findFragmentByTag("voice:$key") as VoiceInputSheet
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val dialog=voice.requireDialog() as AlertDialog
                val editor=all(dialog.window!!.decorView).filterIsInstance<EditText>().single()
                editor.setSelection(10,25)
                buttons(dialog,"전사 원문 비교 펼치기").single().performClick()
                buttons(dialog,"전사 원문 비교 접기").single().performClick()
                assertEquals(original,model.session.draft);assertEquals(original,model.session.original)
                assertEquals(10,editor.selectionStart);assertEquals(25,editor.selectionEnd)
                buttons(dialog,"긴 글 넓게 편집").single().performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { a ->
                val expanded=a.host.childFragmentManager.findFragmentByTag("transcript-editor:$key") as TranscriptEditorSheet
                val editor=all(expanded.requireDialog().window!!.decorView).filterIsInstance<EditText>().single()
                assertEquals(original,editor.text.toString())
                editor.text.replace(0,1,"고침")
                assertTrue(model.session.draft.startsWith("고침"));assertEquals(original,model.session.original)
                assertTrue(editor.height>0)
                expanded.dismiss()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val editor=all(voice.requireDialog().window!!.decorView).filterIsInstance<EditText>().single()
                assertEquals(model.session.draft,editor.text.toString())
                assertTrue(voice.requireDialog().isShowing)
                model.discard() // Only the test's UUID-keyed transcript, never a user's recording.
            }
        }
    }
}
