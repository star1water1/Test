package com.novelcharacter.app.ai

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ReviewPresentationTest {
    @Test fun singleOrUnknownRequestNeverShowsFakeCompletionFraction() {
        for (total in listOf(-1,0,1)) for (done in listOf(0,1)) {
            val display=ReviewPresentation.progress(done,total)
            assertTrue(display.indeterminate);assertFalse(display.text.contains("/"));assertFalse(display.text.contains("%"))
        }
        assertEquals("2/3 요청 완료",ReviewPresentation.progress(2,3).text)
        assertEquals("3/3 요청 완료",ReviewPresentation.progress(5,3).text)
        assertFalse(ReviewPresentation.progress(0,3).indeterminate)
    }
    @Test fun resetOnlyAppearsWhenTheValueOrDraftDiffersFromLatestAi() {
        assertFalse(ReviewPresentation.canReset("같음","같음",null))
        assertFalse(ReviewPresentation.canReset("같음","같음","같음"))
        assertTrue(ReviewPresentation.canReset("같음","같음",""))
        assertTrue(ReviewPresentation.canReset("수정","최근",null))
    }
    @Test fun viewportRoundtripDoesNotTouchFieldRevisionOrDraft() {
        val state=FieldSuggestionReviewState()
        val value=CharacterFieldAiSuggester.Suggestion("8","제안","이유")
        state.current(value);state.setDraft("8","아직 편집 중");state.setChecked("8",false)
        val request=state.beginRequest(listOf("8"),listOf(value))
        state.viewport=ReviewPresentation.Viewport("8",-17,1532,"edit:8",2,7,setOf("why:8","refine:8"))
        val saved=state.snapshot()
        assertEquals(request.revisions,saved.revisions)
        val restored=FieldSuggestionReviewState().apply {restore(Gson().fromJson(Gson().toJson(saved),FieldSuggestionReviewState.Snapshot::class.java))}
        assertEquals(state.viewport,restored.viewport)
        assertEquals("아직 편집 중",restored.editDrafts["8"]);assertFalse(restored.isChecked("8"))
        restored.clear();assertNull(restored.viewport)
    }
}
