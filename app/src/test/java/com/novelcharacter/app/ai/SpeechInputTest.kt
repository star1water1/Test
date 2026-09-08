package com.novelcharacter.app.ai

import com.novelcharacter.app.speech.*
import org.junit.Assert.*
import org.junit.Test

class SpeechInputTest {
    @Test fun transcribeEndpointHandlesProviderBases() {
        assertEquals("https://api.openai.com/v1/audio/transcriptions",SpeechProtocol.endpoint("https://api.openai.com/"))
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions",SpeechProtocol.endpoint("https://api.groq.com/openai/v1"))
        assertEquals("https://custom.test/audio/transcriptions",SpeechProtocol.endpoint("https://custom.test/audio/transcriptions"))
    }
    @Test fun rejectsInsecureAndCredentialBearingUrls() {
        listOf("http://test/v1","https://key@test/v1","https://test/v1?key=secret","file:///private","https://test/#key")
            .forEach { assertNull(SpeechProtocol.endpoint(it)) }
    }
    @Test fun newestModelGetsKeywordsAndPluralLanguagesOnly() {
        val params=SpeechProtocol.parameters(SpeechConfig(),listOf("셀레네"))
        assertTrue("keywords[]" to "셀레네" in params)
        assertTrue("languages[]" to "ko" in params)
        assertFalse(params.any {it.first=="language" || it.first=="response_format"})
    }
    @Test fun compatibleModelGetsPromptAndSingularLanguage() {
        val params=SpeechProtocol.parameters(SpeechConfig(model="whisper-large-v3"),listOf("셀레네"))
        assertTrue("prompt" to "셀레네" in params)
        assertTrue("language" to "ko" in params)
        assertFalse(params.any {it.first=="keywords[]"})
    }
    @Test fun hintsDisabledMeansNoExternalVocabulary() {
        val params=SpeechProtocol.parameters(SpeechConfig(sendHints=false),listOf("비공개이름"))
        assertFalse(params.any {it.second.contains("비공개이름")})
    }
    @Test fun vocabularyBudgetIsBytesAndReportsOmissions() {
        val selected=SpeechVocabulary.select(listOf(SpeechVocabulary.Term("무관한이름",50),
            SpeechVocabulary.Term("셀레네",0),SpeechVocabulary.Term("긴 메모\n자료",5)),12)
        assertEquals(listOf("셀레네"),selected.terms)
        assertEquals(2,selected.omitted)
    }
    @Test fun vocabularyExcludesApiInvalidKeywordCharacters() {
        assertEquals(emptyList<String>(),SpeechVocabulary.select(listOf(SpeechVocabulary.Term("<명령>",0)),500).terms)
    }
    @Test fun uniqueMisspellingIsOnlyAProposal() {
        val text="셀레나, 귀족은 아니다."
        val proposal=SpeechVocabulary.corrections(text,listOf("셀레네")).single()
        assertEquals("셀레나, 귀족은 아니다.",text)
        assertEquals("셀레네, 귀족은 아니다.",SpeechVocabulary.apply(text,proposal))
    }
    @Test fun ambiguousNamesAreNotCorrected() {
        assertTrue(SpeechVocabulary.corrections("셀레나",listOf("셀레네","셀레노")).isEmpty())
    }
    @Test fun knownNameWithParticleIsNotShortened() {
        assertTrue(SpeechVocabulary.corrections("셀레네는",listOf("셀레네")).isEmpty())
    }
    @Test fun correctionRefusesStaleText() {
        val proposal=SpeechVocabulary.corrections("셀레나",listOf("셀레네")).single()
        assertNull(SpeechVocabulary.apply("다른 말",proposal))
    }
    @Test fun successKeepsNegationRangeAndUncertaintyExactly() {
        val text="귀족처럼 보이지만 사실 귀족은 아니다. 키는 165~170 정도인 것 같아."
        val result=SpeechProtocol.parse(200,org.json.JSONObject().put("text",text).toString(),"test") as SpeechResult.Success
        val session=SpeechSession(); session.audioReady()
        session.complete(session.beginTranscription()!!,result)
        assertEquals(text,session.original)
        assertEquals(text,session.draft)
    }
    @Test fun explicitSelfCorrectionIsKeptForIntentInterpretation() {
        val session=SpeechSession(); session.audioReady()
        session.complete(session.beginTranscription()!!,SpeechResult.Success("키는 170... 아니, 167 정도.","test"))
        assertEquals("키는 170... 아니, 167 정도.",session.original)
        assertTrue(CreativeBriefing.INTENT_RULE.contains("마지막 발언"))
    }
    @Test fun duplicatePaidRequestIsPrevented() {
        val session=SpeechSession();session.audioReady()
        assertNotNull(session.beginTranscription());assertNull(session.beginTranscription())
        assertFalse(session.startRecording(true))
    }
    @Test fun deniedPermissionNeverEntersRecording() {
        val session=SpeechSession()
        assertFalse(session.startRecording(false))
        assertEquals(SpeechError.PERMISSION,session.error)
        assertEquals(SpeechSession.Phase.ERROR,session.phase)
    }
    @Test fun failurePreservesPreviousTranscript() {
        val session=SpeechSession();session.deviceResult("기존 전사")
        session.draft="직접 수정"
        session.audioReady()
        session.complete(session.beginTranscription()!!,SpeechResult.Failure(SpeechError.NETWORK))
        assertEquals("기존 전사",session.original);assertEquals("직접 수정",session.draft)
        assertEquals(SpeechError.NETWORK,session.error)
    }
    @Test fun staleCompletionCannotOverwriteNewSession() {
        val session=SpeechSession();session.audioReady();val id=session.beginTranscription()!!
        session.reset();session.complete(id,SpeechResult.Success("낡은 응답","test"))
        assertEquals("",session.original)
    }
    @Test fun serverErrorsDoNotEchoSecretResponse() {
        val error=SpeechProtocol.parse(401,"secret-key-private", "test") as SpeechResult.Failure
        assertEquals(SpeechError.AUTH,error.kind);assertFalse(error.toString().contains("secret-key-private"))
    }
    @Test fun invalidOrSilentResponsesAreNotSuccess() {
        listOf("{}","{\"text\":null}","{\"text\":{}}","{\"text\":\" \"}","invalid").forEach {
            assertTrue(SpeechProtocol.parse(200,it,"test") is SpeechResult.Failure)
        }
    }
}
