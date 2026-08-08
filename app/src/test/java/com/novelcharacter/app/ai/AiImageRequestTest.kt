package com.novelcharacter.app.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **이미지 첨부 요청의 회귀 가드** (A-7 · 설계 `feature_roadmap_2026-08` 2-1).
 *
 * 이 자리가 시험을 받는 이유는 하나다 — **세 프로토콜이 이미지를 서로 다른 모양으로 받고,
 * 틀린 모양은 컴파일도 정적 검사도 통과한 뒤 400으로만 드러난다.** 코덱은 순수 JVM이라
 * 프롬프트 문자열과 달리 이 자리는 로컬 검증이 닿는다(설계가 "반드시 테스트를 함께"라고
 * 적은 근거).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 셋이다:**
 * ① *이미지가 없으면 요청이 종전과 글자 그대로 같다* — content가 배열로 바뀌면 이미지를
 *   안 쓰는 기존 5경로가 전부 새 형식으로 나가고, 그것을 거부하는 서버에서 **멀쩡하던
 *   기능이 함께 죽는다.**
 * ② *거부 판정이 좁다* — 무관한 400을 이미지 문제로 오인하면 `imagesUnsupported`가 남아
 *   **그 모델에는 다시는 이미지가 나가지 않고**, 사용자는 이유를 볼 수 없다.
 * ③ *뺀 사본이 글은 그대로 둔다* — 재시도가 본문까지 잃으면 유료 요청 하나가 통째로 헛된다.
 *
 * **④는 B-139가 더했다 — *그림을 빼면 그림 지시도 빠진다*.** 시스템 프롬프트는
 * *"이미지 N장이 순서대로 함께 실려 있다"*고 말하는데 이미지가 빠지는 경로가 둘이다
 * (④ 거부 재시도 · `strippedUpfront` 사전 제거). 종전에는 `withoutImages()`가 `system`을
 * 손대지 않아 **둘 다** 그 지시를 그대로 내보냈고, 모델은 없는 그림을 근거로 삼아
 * **날조된 출처**를 낼 수 있었다(후자는 학습된 뒤 **매 요청** 어긋난다).
 * 여기서 세 프로토콜 전부를 세우는 이유: 실을지 말지는 요청이 정하지만 **싣는 것은 코덱**이라,
 * 한 프로토콜만 `request.system`으로 되돌아가도 그 경로에서만 조용히 되살아난다.
 */
class AiImageRequestTest {

    private fun config(protocol: AiProtocol) = AiProviderConfig(
        id = "cfg1", protocol = protocol, displayName = "테스트",
        baseUrl = "https://example.test", model = "m1"
    )

    private val image = AiImage(base64 = "QUJD", mediaType = "image/jpeg")
    private val twoImages = listOf(image, AiImage(base64 = "REVG", mediaType = "image/png"))

    private fun requestWith(images: List<AiImage>) =
        AiRequest(system = "sys", userText = "본문", maxTokens = 512, images = images)

    private fun body(protocol: AiProtocol, images: List<AiImage>) =
        JsonParser.parseString(
            AiProtocolCodec.buildRequest(config(protocol), "k", requestWith(images)).bodyJson
        ).asJsonObject

    // ── ① 이미지가 없으면 종전과 동일하다 ────────────────────────────────────

    @Test
    fun `이미지가 없으면 anthropic content는 여전히 문자열이다`() {
        val content = body(AiProtocol.ANTHROPIC, emptyList())
            .getAsJsonArray("messages")[0].asJsonObject.get("content")
        assertTrue("이미지 없는 요청이 배열로 바뀌면 기존 경로 전부가 새 형식으로 나간다", content.isJsonPrimitive)
        assertEquals("본문", content.asString)
    }

    @Test
    fun `이미지가 없으면 openai content는 여전히 문자열이다`() {
        val messages = body(AiProtocol.OPENAI_COMPAT, emptyList()).getAsJsonArray("messages")
        // 0번은 system, 1번이 사용자 메시지다
        val content = messages[1].asJsonObject.get("content")
        assertTrue(content.isJsonPrimitive)
        assertEquals("본문", content.asString)
    }

    @Test
    fun `이미지가 없으면 gemini parts는 텍스트 하나뿐이다`() {
        val parts = body(AiProtocol.GEMINI, emptyList())
            .getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals(1, parts.size())
        assertEquals("본문", parts[0].asJsonObject.get("text").asString)
    }

    // ── 세 프로토콜의 이미지 모양 ────────────────────────────────────────────

    @Test
    fun `anthropic은 base64 source 블록으로 싣고 텍스트를 뒤에 둔다`() {
        val content = body(AiProtocol.ANTHROPIC, twoImages)
            .getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals(3, content.size())

        val first = content[0].asJsonObject
        assertEquals("image", first.get("type").asString)
        val source = first.getAsJsonObject("source")
        assertEquals("base64", source.get("type").asString)
        assertEquals("image/jpeg", source.get("media_type").asString)
        assertEquals("QUJD", source.get("data").asString)

        // 두 번째 장의 mediaType이 그대로 실린다 — 고정값으로 박으면 png가 깨진다
        assertEquals("image/png", content[1].asJsonObject.getAsJsonObject("source").get("media_type").asString)

        val last = content[2].asJsonObject
        assertEquals("text", last.get("type").asString)
        assertEquals("본문", last.get("text").asString)
    }

    @Test
    fun `openai는 data URI로 싣는다`() {
        val content = body(AiProtocol.OPENAI_COMPAT, listOf(image))
            .getAsJsonArray("messages")[1].asJsonObject.getAsJsonArray("content")
        assertEquals(2, content.size())
        val part = content[0].asJsonObject
        assertEquals("image_url", part.get("type").asString)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            part.getAsJsonObject("image_url").get("url").asString
        )
        assertEquals("본문", content[1].asJsonObject.get("text").asString)
    }

    @Test
    fun `gemini는 inline_data 파트로 싣는다`() {
        val parts = body(AiProtocol.GEMINI, listOf(image))
            .getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals(2, parts.size())
        val inline = parts[0].asJsonObject.getAsJsonObject("inline_data")
        assertEquals("image/jpeg", inline.get("mime_type").asString)
        assertEquals("QUJD", inline.get("data").asString)
        assertEquals("본문", parts[1].asJsonObject.get("text").asString)
    }

    @Test
    fun `세 프로토콜 모두 이미지를 텍스트 앞에 둔다`() {
        // 프롬프트가 "순서대로 실려 있다 · 몇 번째 이미지인지 밝혀라"라고 지시하므로,
        // 글보다 그림이 먼저 와야 그 번호가 모델이 본 순서와 같다.
        val anthropic = body(AiProtocol.ANTHROPIC, twoImages)
            .getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals("image", anthropic[0].asJsonObject.get("type").asString)
        assertEquals("image", anthropic[1].asJsonObject.get("type").asString)

        val openai = body(AiProtocol.OPENAI_COMPAT, twoImages)
            .getAsJsonArray("messages")[1].asJsonObject.getAsJsonArray("content")
        assertEquals("image_url", openai[0].asJsonObject.get("type").asString)

        val gemini = body(AiProtocol.GEMINI, twoImages)
            .getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertTrue(gemini[0].asJsonObject.has("inline_data"))
    }

    // ── ③ 이미지를 뺀 사본 ───────────────────────────────────────────────────

    @Test
    fun `withoutImages는 이미지만 지우고 본문과 설정은 그대로 둔다`() {
        val original = AiRequest(
            system = "sys",
            messages = listOf(
                AiMessage(AiRole.USER, "첫 글", listOf(image)),
                AiMessage(AiRole.ASSISTANT, "답")
            ),
            maxTokens = 777,
            temperature = 0.4
        )
        assertTrue(original.hasImages())

        val stripped = original.withoutImages()
        assertFalse(stripped.hasImages())
        assertEquals("첫 글", stripped.messages[0].text)
        assertEquals("답", stripped.messages[1].text)
        assertEquals("sys", stripped.system)
        assertEquals(777, stripped.maxTokens)
        assertEquals(0.4, stripped.temperature!!, 1e-9)
    }

    @Test
    fun `이미지가 없으면 withoutImages는 같은 요청을 그대로 돌려준다`() {
        val plain = AiRequest(userText = "글")
        assertFalse(plain.hasImages())
        assertTrue(plain === plain.withoutImages())
    }

    // ── ④ 그림을 빼면 그림 지시도 빠진다 (B-139) ─────────────────────────────

    /** 실제 지시문과 같은 모양 — 장수를 말하는 한 줄이 이 결함의 본체였다. */
    private fun ruleFor(count: Int) = "\n[이미지] 이 요청에는 캐릭터의 이미지 ${count}장이 실려 있다."

    private fun withRule(images: List<AiImage>) = AiRequest(
        system = "sys", userText = "본문", maxTokens = 512,
        images = images, imageSystemRule = ruleFor(images.size)
    )

    @Test
    fun `이미지가 실리면 시스템 프롬프트에 이미지 절이 붙는다`() {
        val sys = withRule(twoImages).effectiveSystem()!!
        assertTrue("이미지를 실었는데 지시가 빠지면 모델이 그림을 근거로 쓰라는 말을 못 듣는다", "sys" in sys)
        assertTrue(ruleFor(2) in sys)
    }

    @Test
    fun `withoutImages가 만든 사본에는 이미지 절이 없다`() {
        val stripped = withRule(twoImages).withoutImages()
        assertFalse(stripped.hasImages())
        assertEquals(
            "이미지를 뺐는데 '이미지 2장이 실려 있다'가 남으면 모델이 없는 그림을 근거로 답한다",
            "sys", stripped.effectiveSystem()
        )
    }

    @Test
    fun `이미지 절만 있고 이미지가 없으면 싣지 않는다`() {
        // `strippedUpfront`가 도달하는 상태다 — 거부를 학습한 모델에는 애초에 싣지 않는데,
        // 지시를 걷어내는 일을 그 자리에 맡기면 **경로가 셋째로 늘 때 또 샌다.**
        // 판정을 `hasImages()`에 두었으므로 어느 경로로 비워졌든 결과가 같다.
        val request = AiRequest(
            system = "sys", userText = "본문", imageSystemRule = ruleFor(3)
        )
        assertEquals("sys", request.effectiveSystem())
    }

    @Test
    fun `세 프로토콜 모두 이미지를 뺀 요청에 이미지 절을 싣지 않는다`() {
        val stripped = withRule(twoImages).withoutImages()
        val rule = ruleFor(2)

        val anthropic = JsonParser
            .parseString(AiProtocolCodec.buildRequest(config(AiProtocol.ANTHROPIC), "k", stripped).bodyJson)
            .asJsonObject.get("system").asString
        assertEquals("sys", anthropic)

        val openai = JsonParser
            .parseString(AiProtocolCodec.buildRequest(config(AiProtocol.OPENAI_COMPAT), "k", stripped).bodyJson)
            .asJsonObject.getAsJsonArray("messages")[0].asJsonObject.get("content").asString
        assertEquals("sys", openai)

        val gemini = JsonParser
            .parseString(AiProtocolCodec.buildRequest(config(AiProtocol.GEMINI), "k", stripped).bodyJson)
            .asJsonObject.getAsJsonObject("system_instruction")
            .getAsJsonArray("parts")[0].asJsonObject.get("text").asString
        assertEquals("sys", gemini)

        // 같은 요청에 이미지가 붙어 있으면 세 곳 모두 실어야 한다 — 안 싣는 것으로
        // 통과하는 시험은 이 결함을 못 잡는다(빼는 쪽만 재면 늘 초록이다).
        val carried = withRule(twoImages)
        assertTrue(rule in JsonParser
            .parseString(AiProtocolCodec.buildRequest(config(AiProtocol.ANTHROPIC), "k", carried).bodyJson)
            .asJsonObject.get("system").asString)
        assertTrue(rule in JsonParser
            .parseString(AiProtocolCodec.buildRequest(config(AiProtocol.OPENAI_COMPAT), "k", carried).bodyJson)
            .asJsonObject.getAsJsonArray("messages")[0].asJsonObject.get("content").asString)
        assertTrue(rule in JsonParser
            .parseString(AiProtocolCodec.buildRequest(config(AiProtocol.GEMINI), "k", carried).bodyJson)
            .asJsonObject.getAsJsonObject("system_instruction")
            .getAsJsonArray("parts")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `두 조립기의 시스템 프롬프트에는 이미지 절이 들어 있지 않다`() {
        // 이어붙이기의 부활을 여기서도 잡는다 — `check_ai_image_rule.sh`가 모양을 보고
        // 이 시험이 결과를 본다. 둘 중 하나만으로는 이름을 바꾼 부활을 놓친다.
        assertFalse(CharacterFieldAiSuggester.buildSystemPrompt().contains("[이미지]"))
        assertFalse(NarrativeFieldAiWriter.buildSystemPrompt().contains("[이미지]"))
        // 절 자체는 살아 있어야 한다 — 사라지면 이미지를 실어도 모델이 지시를 못 받는다.
        assertTrue(CharacterFieldAiSuggester.imageRule(2).contains("[이미지]"))
        assertTrue(NarrativeFieldAiWriter.imageRule(2).contains("[이미지]"))
        assertEquals("", CharacterFieldAiSuggester.imageRule(0))
        assertEquals("", NarrativeFieldAiWriter.imageRule(0))
    }

    // ── ② 거부 판정은 좁다 ───────────────────────────────────────────────────

    @Test
    fun `이미지 미지원 400을 세 제공사의 표현에서 알아본다`() {
        assertTrue(
            AiProtocolCodec.isImagesUnsupportedError(
                400, """{"error":{"message":"messages.0.content.0.image: this model does not support image input"}}"""
            )
        )
        assertTrue(
            AiProtocolCodec.isImagesUnsupportedError(
                400, """{"error":{"message":"Invalid content type. image_url is only supported by certain models."}}"""
            )
        )
        assertTrue(
            AiProtocolCodec.isImagesUnsupportedError(
                400, """{"error":{"message":"inline_data is not supported for this model"}}"""
            )
        )
    }

    @Test
    fun `무관한 400은 이미지 문제로 오인하지 않는다`() {
        // 이것이 이 판정의 존재 이유다 — 오인하면 학습값이 남아 그 모델에는 다시는
        // 이미지가 나가지 않고, 사용자는 그 사실도 이유도 볼 수 없다.
        assertFalse(AiProtocolCodec.isImagesUnsupportedError(400, """{"error":{"message":"max_tokens is too large"}}"""))
        assertFalse(
            AiProtocolCodec.isImagesUnsupportedError(
                400, """{"error":{"message":"Unsupported value: 'temperature' is not supported"}}"""
            )
        )
        // 낱말만 있고 거부 표현이 없으면 아니다
        assertFalse(AiProtocolCodec.isImagesUnsupportedError(400, """{"error":{"message":"image decoded"}}"""))
        // 거부 표현만 있고 낱말이 없어도 아니다
        assertFalse(AiProtocolCodec.isImagesUnsupportedError(400, """{"error":{"message":"tool use is not supported"}}"""))
    }

    @Test
    fun `400이 아닌 응답과 빈 본문은 이미지 거부가 아니다`() {
        assertFalse(AiProtocolCodec.isImagesUnsupportedError(429, "image is not supported"))
        assertFalse(AiProtocolCodec.isImagesUnsupportedError(500, "image is not supported"))
        assertFalse(AiProtocolCodec.isImagesUnsupportedError(400, null))
    }

    // ── 학습값 등재 (R-23) ───────────────────────────────────────────────────

    @Test
    fun `imagesUnsupported는 학습한 사실로 등재돼 있다`() {
        // 등재를 빠뜨리면 모델·주소를 바꿔도 초기화되지 않아, 안 받던 모델에서 배운 사실이
        // 받는 모델로 넘어가 **첨부가 영영 조용히 빠진다**(R-23이 막으려는 바로 그것).
        val base = AiProviderConfig(
            id = "c", protocol = AiProtocol.ANTHROPIC, displayName = "n",
            baseUrl = "https://x.test", model = "m"
        )
        assertFalse(base.hasLearnedFacts())
        assertTrue(base.copy(imagesUnsupported = true).hasLearnedFacts())
    }
}
