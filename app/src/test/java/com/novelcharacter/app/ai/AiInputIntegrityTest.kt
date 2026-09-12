package com.novelcharacter.app.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import com.novelcharacter.app.data.model.FieldType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiInputIntegrityTest {
    private val brief = "앞: 귀족은 아니다.\n" + ("중간: 꽤 강하지만 최강이라는 뜻은 아니다. 한글 🐈 \"따옴표\" \\ 끝\n").repeat(240) +
        "마지막 정정: S가 아니라 B~C 정도일 수도 있다. {{추천할필드}}"
    private val context = CharacterAiContext("서린", emptyList(), emptyList(), "자료", emptyList(),
        emptyList(), emptyList(), emptyList(), briefing = brief)
    private val field = FieldSpec("mood", "성격", FieldType.TEXT, emptyList(), false, null, "")
    private val source = AiInputSource(brief, mapOf("mood" to brief), emptyList())
    private fun config(protocol: AiProtocol) = AiProviderConfig("p", protocol, "test", when(protocol) {
        AiProtocol.GEMINI -> "https://generativelanguage.googleapis.com"
        AiProtocol.ANTHROPIC -> "https://api.anthropic.com"
        else -> "https://compatible.example"
    }, "model-test")
    private fun wire(protocol: AiProtocol, images: Boolean = true, output: Int = 256) =
        AiProtocolCodec.buildRequest(config(protocol), "fixture-key",
            AiRequest(system = "SYSTEM" + brief, userText = brief, maxTokens = output,
                images = if(images) listOf(AiImage("aGVsbG8=", "image/png")) else emptyList(),
                inputSource = source))

    @Test fun everyProtocolPreservesExactLongSystemUserTextAndImage() = runBlocking {
        for(protocol in AiProtocol.entries) {
            val spec = wire(protocol)
            val json = JsonParser.parseString(spec.bodyJson).asJsonObject
            val system: String
            val text: String
            when(protocol) {
                AiProtocol.ANTHROPIC -> {
                    system = json.get("system").asString
                    text = json.getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
                        .last().asJsonObject.get("text").asString
                }
                AiProtocol.OPENAI_COMPAT -> {
                    val messages = json.getAsJsonArray("messages")
                    system = messages[0].asJsonObject.get("content").asString
                    text = messages[1].asJsonObject.getAsJsonArray("content").last().asJsonObject.get("text").asString
                }
                AiProtocol.GEMINI -> {
                    system = json.getAsJsonObject("system_instruction").getAsJsonArray("parts")[0].asJsonObject.get("text").asString
                    text = json.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts").last().asJsonObject.get("text").asString
                }
            }
            assertEquals("SYSTEM" + brief, system)
            assertEquals(brief, text)
            val result = AiInputPreflight.send(config(protocol), spec, source, { null },
                { AiResult.Success("value", "test") }) as AiResult.Success
            val receipt = result.inputReceipt!!
            assertEquals(listOf(system, text), receipt.sentText)
            assertEquals(1, receipt.imageCount)
            assertEquals(source, receipt.source)
            assertFalse(Gson().toJson(receipt).contains("fixture-key"))
            assertFalse(Gson().toJson(receipt).contains("aGVsbG8="))
        }
    }

    @Test fun refinementBoundariesAndCurrentRejectedValuesAreUncut() {
        for(size in listOf(299,300,301,6000)) {
            val instruction = "가".repeat(size) + "마지막 부정 🐈"
            val prompt = CharacterFieldAiSuggester.buildUserPrompt(context,
                listOf(field.copy(userInstruction=instruction,currentValue=instruction,rejectedValues=listOf(instruction))))
            assertTrue(prompt.text.contains("사용자 지시: " + instruction))
            assertTrue(prompt.text.contains("현재 값: " + instruction))
            assertTrue(prompt.text.contains("이미 물린 값(다시 내지 말 것): " + instruction))
            assertEquals(brief, JSONObject(prompt.text.substringAfterLast("/ data]\n")).getString("briefing"))
        }
    }

    @Test fun customTemplatesAndEveryTargetChunkKeepBriefAndInstruction() = runBlocking {
        val custom = "{{추천할필드}}" + "템플릿".repeat(1900)
        val templates = object: PromptTemplates.Source {
            override fun templateOf(id: PromptTemplates.Id) = when(id) {
                PromptTemplates.Id.CHAR_FIELD_USER -> custom
                else -> PromptTemplates.Source.DEFAULTS.templateOf(id)
            }
        }
        var calls=0
        val engine = CharacterFieldAiSuggester(complete = { request ->
            calls++
            assertEquals(brief, JSONObject(request.messages.single().text.substringAfterLast("/ data]\n")).getString("briefing"))
            assertTrue(request.messages.single().text.contains(brief))
            assertTrue(request.messages.single().text.contains(custom.substringAfter("}}")))
            assertEquals(brief, request.inputSource!!.briefing)
            assertTrue(request.inputSource!!.instructions.values.all { it == brief })
            AiResult.Success("""{"suggestions":[]}""","test")
        }, effectiveMaxTokens={1024},temperatureFor={null},isTemperatureUnsupported={false},isImagesUnsupported={false})
        engine.suggest(context, (1..12).map {field.copy(key="k"+it,userInstruction=brief)}, templates=templates) {"failure"}
        assertTrue(calls > 1)
        assertEquals(custom, templates.templateOf(PromptTemplates.Id.CHAR_FIELD_USER))
    }

    @Test fun geminiCounterContainsTheEntireFinalGenerateRequest() {
        val spec=wire(AiProtocol.GEMINI)
        val counted=JsonParser.parseString(AiInputPreflight.countSpec(config(AiProtocol.GEMINI),spec).bodyJson)
            .asJsonObject.getAsJsonObject("generateContentRequest")
        assertEquals("models/model-test",counted.remove("model").asString)
        assertEquals(JsonParser.parseString(spec.bodyJson),counted)
    }

    @Test fun anthropicCounterKeepsSystemMessagesAndImagesWithoutGenerationParameters() {
        val spec=wire(AiProtocol.ANTHROPIC)
        val actual=JsonParser.parseString(spec.bodyJson).asJsonObject
        val counted=JsonParser.parseString(AiInputPreflight.countSpec(config(AiProtocol.ANTHROPIC),spec).bodyJson).asJsonObject
        for(key in listOf("model","system","messages")) assertEquals(actual.get(key),counted.get(key))
        assertFalse(counted.has("max_tokens"))
    }

    @Test fun boundariesIncludeOutputReserveOnlyForSharedContextWindow() {
        val model="""{"inputTokenLimit":1000,"outputTokenLimit":300,"max_input_tokens":1000,"max_tokens":300}"""
        for(input in listOf(999,1000,1001)) {
            val g=AiInputPreflight.budget(AiProtocol.GEMINI,model,"""{"totalTokens":$input}""",256)
            assertEquals(input>1000,g.exceeded)
        }
        for(input in listOf(743,744,745)) {
            val a=AiInputPreflight.budget(AiProtocol.ANTHROPIC,model,"""{"input_tokens":$input}""",256)
            assertEquals(input>744,a.exceeded)
        }
        assertTrue(AiInputPreflight.budget(AiProtocol.GEMINI,model,"""{"totalTokens":1}""",301).exceeded)
    }

    @Test fun missingInvalidAndOverflowingNumbersNeverClaimKnown() {
        for(value in listOf("null","-1","0","1.5","\"1000\"","9223372036854775808","true","{}")) {
            val b=AiInputPreflight.budget(AiProtocol.GEMINI,
                """{"inputTokenLimit":$value,"outputTokenLimit":1000}""","""{"totalTokens":100}""",256)
            assertFalse(b.known)
        }
        assertFalse(AiInputBudget().known)
        assertFalse(AiInputBudget().exceeded)
        val b=AiInputPreflight.budget(AiProtocol.GEMINI,"""{"inputTokenLimit":10}""","""{"totalTokens":11}""",256)
        assertTrue(b.exceeded) // a known input overflow cannot be hidden by missing output metadata
    }

    @Test fun preflightOverflowMakesZeroGenerationCalls() = runBlocking {
        var calls=0
        val result=AiInputPreflight.send(config(AiProtocol.GEMINI),wire(AiProtocol.GEMINI),source,
            { spec -> AiInputPreflight.Reply(200, if(spec.method=="GET")
                """{"inputTokenLimit":100,"outputTokenLimit":1000}""" else """{"totalTokens":101}""") },
            {calls++;AiResult.Success("value","test")})
        assertEquals(0,calls)
        assertEquals(AiErrorKind.INPUT_TOO_LARGE,(result as AiResult.Failure).kind)
        assertEquals(brief,source.briefing)
    }

    @Test fun fallbackCandidateAndImageRemovalAreCheckedIndependently() = runBlocking {
        var calls=0
        for((index,protocol) in listOf(AiProtocol.ANTHROPIC,AiProtocol.GEMINI).withIndex()) {
            val result=AiInputPreflight.send(config(protocol),wire(protocol,images=index==0),source,
                {spec -> AiInputPreflight.Reply(200,if(spec.method=="GET")
                    """{"max_input_tokens":1000,"max_tokens":1000,"inputTokenLimit":1000,"outputTokenLimit":1000}"""
                    else if(index==0) """{"input_tokens":800}""" else """{"totalTokens":800}""") },
                {calls++;AiResult.Success("value","test")})
            if(index==0) assertTrue(result is AiResult.Failure)
            else {
                val r=(result as AiResult.Success).inputReceipt!!
                assertEquals(AiProtocol.GEMINI,r.protocol);assertEquals(0,r.imageCount)
                assertTrue(r.budget.known)
            }
        }
        assertEquals(1,calls)
    }

    @Test fun compatibleServersAndFailedCountersRemainExplicitlyUnknown() = runBlocking {
        var inspected=0
        var generated=0
        val c=config(AiProtocol.OPENAI_COMPAT)
        val r=AiInputPreflight.send(c,wire(c.protocol),source,{inspected++;null},
            {generated++;AiResult.Success("value","test")}) as AiResult.Success
        assertEquals(0,inspected);assertEquals(1,generated)
        assertFalse(r.inputReceipt!!.budget.known)
        assertTrue(r.inputReceipt!!.budget.notice().contains("확인할 수 없습니다"))
        val failed=AiInputPreflight.send(config(AiProtocol.GEMINI),wire(AiProtocol.GEMINI),source,
            {AiInputPreflight.Reply(404,"{}")},{AiResult.Success("value","test")}) as AiResult.Success
        assertFalse(failed.inputReceipt!!.budget.known)
        for(url in listOf("https://api.anthropic.com.evil.test","https://api.anthropic.com/proxy",
            "http://api.anthropic.com","https://key@api.anthropic.com")) {
            assertFalse(AiInputPreflight.supported(config(AiProtocol.ANTHROPIC).copy(baseUrl=url)))
        }
    }

    @Test fun inputErrorsAreDistinctFromOutputTruncationAndNeverTriggerFallback() {
        for(body in listOf("""{"error":{"code":"context_length_exceeded","message":"too big"}}""",
            """{"error":{"message":"prompt is too long: 200000 tokens > limit"}}""",
            """{"error":{"message":"The input token count exceeds the maximum number of tokens allowed"}}""")) {
            val f=AiProtocolCodec.parseError(400,body)
            assertEquals(AiErrorKind.INPUT_TOO_LARGE,f.kind)
            assertTrue(AiErrorPolicy.isTerminal(f.kind))
            assertEquals(AiProviderFallback.Disposition.STOP,AiProviderFallback.dispositionOf(f.kind,0))
        }
        assertEquals(AiErrorKind.INPUT_TOO_LARGE,AiProtocolCodec.parseError(413,"").kind)
        assertNotEquals(AiErrorKind.INPUT_TOO_LARGE,AiProtocolCodec.parseError(400,
            """{"error":{"message":"max_tokens must be at most 4096"}}""").kind)
    }

    @Test fun responseLimitChecksBytesAndRejectsEvenValidJsonWithAnOversizedTail() {
        val bytes="""{"text":"안녕 🐈"}""".toByteArray()
        assertArrayEquals(bytes,BoundedResponse.read(bytes.inputStream(),bytes.size))
        try { BoundedResponse.read((bytes + byteArrayOf(32)).inputStream(),bytes.size);fail("must reject") }
        catch(_: BoundedResponse.TooLarge) {}
        assertEquals("안녕 🐈",(com.novelcharacter.app.speech.SpeechProtocol.parse(200,
            bytes.toString(Charsets.UTF_8),"test") as com.novelcharacter.app.speech.SpeechResult.Success).text)
    }


    @Test fun counterRejectionAndCancellationNeverSendGeneration() = runBlocking {
        var generated=0
        val config=config(AiProtocol.ANTHROPIC)
        val result=AiInputPreflight.send(config,wire(config.protocol),source,
            {spec -> if(spec.method=="GET") AiInputPreflight.Reply(200,"""{"max_input_tokens":1000,"max_tokens":1000}""")
                else AiInputPreflight.Reply(400,"""{"error":{"message":"prompt is too long"}}""")},
            {generated++;AiResult.Success("value","test")})
        assertEquals(AiErrorKind.INPUT_TOO_LARGE,(result as AiResult.Failure).kind)
        try {
            AiInputPreflight.send(config,wire(config.protocol),source,{throw kotlinx.coroutines.CancellationException()},
                {generated++;AiResult.Success("value","test")})
            fail("cancelled")
        } catch(_: kotlinx.coroutines.CancellationException) {}
        assertEquals(0,generated)
    }

    @Test fun receiptSurvivesCheckpointsRetryMergeAndJsonRestore() = runBlocking {
        val checkpoints=mutableListOf<SuggestOutcome>()
        val config=config(AiProtocol.OPENAI_COMPAT)
        val engine=CharacterFieldAiSuggester(complete={request ->
            AiInputPreflight.send(config,AiProtocolCodec.buildRequest(config,"fixture-key",request),
                request.inputSource!!,{null},
                {AiResult.Success("""{"suggestions":[{"key":"mood","value":"과묵","sourceEvidence":"귀족은 아니다.","inputReceiptId":"llm-forgery"}]}""","model-test")})
        },effectiveMaxTokens={4096},temperatureFor={null},isTemperatureUnsupported={false},isImagesUnsupported={false})
        val result=engine.suggest(context,listOf(field.copy(userInstruction=brief)),onCheckpoint={checkpoints.add(it)}) {"failed"}
        val receipt=result.inputReceipts!!.single()
        assertEquals(receipt.id,result.suggestions.single().inputReceiptId)
        assertNotEquals("llm-forgery",receipt.id)
        assertEquals(brief,receipt.source.instructions["mood"])
        assertEquals(receipt,checkpoints.last().inputReceipts!!.single())
        val restored=Gson().fromJson(Gson().toJson(result),SuggestOutcome::class.java)
        assertEquals(receipt,restored.inputReceipts!!.single())
        assertEquals(1,FieldSuggestionReviewState.merge(result,restored).inputReceipts!!.size)
    }

    @Test fun inputFailureKeepsPaidResultAndLegacySnapshotsRemainReadable() {
        val gson=Gson()
        val old="""{"suggestions":[{"fieldKey":"mood","value":"이전 유료 값","reason":""}],"droppedCount":0,"failures":[],"truncationNotes":[],"inputTokens":10,"outputTokens":20,"missing":[],"unknownKeys":[]}"""
        val previous=gson.fromJson(old,SuggestOutcome::class.java)
        assertNull(previous.inputReceipts);assertNull(previous.suggestions.single().inputReceiptId)
        val retry=previous.copy(suggestions=emptyList(),missing=listOf(MissingField("mood","성격",MissingCause.REQUEST_FAILED)),
            failures=listOf("입력 한도 초과"))
        assertEquals("이전 유료 값",FieldSuggestionReviewState.merge(previous,retry).suggestions.single().value)
    }
}
