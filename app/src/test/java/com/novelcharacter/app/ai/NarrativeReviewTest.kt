package com.novelcharacter.app.ai

import org.junit.Assert.*
import org.junit.Test

class NarrativeReviewTest {
    private fun outcome(vararg text: String) = NarrativeFieldAiWriter.WriteOutcome(
        text.map { NarrativeFieldAiWriter.Draft(it) }, 0, emptyList(), emptyList(), false, 10, 20)

    @Test fun refinementAppendsCandidatesAndKeepsEditedSelection() {
        val state=NarrativeReviewState()
        state.seedDefaults(listOf(1L,2L))
        state.choose(1L,1)
        state.edits[state.key(1L,1)]="직접 수정"
        state.setChecked(2L,false)
        val merged=NarrativeReviewState.merge(outcome("첫 글","둘째 글"),outcome("보완 글"))
        assertEquals(listOf("첫 글","둘째 글","보완 글"),merged.drafts.map {it.text})
        assertEquals("직접 수정",state.current(1L,state.chosen(1L),merged.drafts[1].text))
        state.seedDefaults(listOf(1L,2L))
        assertFalse(state.isChecked(2L))
        assertEquals(20,merged.inputTokens)
    }
    @Test fun failedRefinementNeverRemovesDrafts() {
        val first=outcome("이미 받은 긴 글")
        val result=NarrativeReviewState.merge(first,outcome().copy(failures=listOf("전송 실패")))
        assertEquals(first.drafts,result.drafts)
        assertEquals(listOf("전송 실패"),result.failures)
    }
    @Test fun continuationUsesLiveOriginalAndEditedCandidate() {
        assertEquals("지금 고친 원문\n\n수정한 후보",NarrativeReviewState.appliedText("지금 고친 원문 ","수정한 후보",true))
        assertEquals("수정한 후보",NarrativeReviewState.appliedText("원문","수정한 후보",false))
    }
    @Test fun rawLongDraftSurvivesRecreationWithoutTruncation() {
        val state=NarrativeReviewState()
        val text="긴 글과 미완성 표현...\n".repeat(1000)
        state.edits[state.key(1L,0)]=text
        state.editing.add(state.key(1L,0)); state.expanded.add(1L)
        state.seedDefaults(listOf(1L)); state.seedDefaults(listOf(1L))
        assertEquals(text,state.current(1L,0,"원본"))
        assertTrue(state.key(1L,0) in state.editing)
    }
    @Test fun refinementInstructionIsDataAndKeepsUncertainty() {
        val context=CharacterFieldAiSuggester.CharacterAiContext("이름",emptyList(),emptyList(),"메모",
            emptyList(),emptyList(),emptyList(),emptyList(),briefing="귀족은 아니다")
        val field=NarrativeFieldAiWriter.FieldSpec("story","서술","기본","긴 원문",userInstruction="165~170 정도는 그대로")
        val prompt=NarrativeFieldAiWriter.buildUserPrompt(context,field,NarrativeFieldAiWriter.Mode.POLISH,
            NarrativeFieldAiWriter.Length.LONG,2)
        assertTrue(prompt.text.contains("귀족은 아니다"))
        assertTrue(prompt.text.contains("165~170 정도는 그대로"))
        assertTrue(prompt.text.contains("긴 원문"))
    }
}
