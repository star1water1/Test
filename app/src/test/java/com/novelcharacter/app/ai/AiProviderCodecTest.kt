package com.novelcharacter.app.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로바이더 설정 직렬화 왕복 잠금 (B-132).
 *
 * **왜 이 시험이 필요했는가:** `imagesUnsupported`는 모델·`hasLearnedFacts()`·설정 화면의
 * R-23 초기화까지 전부 배선됐는데 **저장소의 toJson/fromJson에만 빠져 있었다.** 저장소가
 * Context 의존이라 어떤 로컬 검증도 그 누락을 보지 못했고, 증상은 조용했다 — 비전 미지원
 * 모델에서 청크마다 400→재시도가 되풀이되고(base64를 매번 다시 업로드) 사전 고지 코드
 * 세 자리가 전부 도달하지 않는 죽은 길이 됐다.
 *
 * **이 판의 방어선은 시험 수가 아니라 그 안의 셋이다:**
 * ① *"설정의 모든 필드가 JSON에 나간다"* — 이것이 B-132를 **부류째** 막는 유일한 잠금이다.
 *    개별 필드를 하나씩 세는 시험은 **다음에 학습값이 하나 더 늘 때 그대로 통과한다**(빠뜨린
 *    필드를 시험도 함께 빠뜨리므로). 그래서 필드 목록을 손으로 적지 않고 선언에서 읽는다.
 * ② *"null 학습값은 키 자체를 쓰지 않는다"* — "미설정(자동)"과 "false로 설정"은 다른 상태다.
 *    합치면 아직 아무것도 모르는 모델이 *"이미지를 받는다고 확인됨"*으로 굳는다.
 * ③ *"손상된 항목 하나가 목록 전체를 버리지 않는다"* — 사용자의 나머지 프로바이더가
 *    말없이 사라지는 것은 개발 의도 2번(변수 제어) 정면 위반이다.
 */
class AiProviderCodecTest {

    private fun full() = AiProviderConfig(
        id = "p1",
        protocol = AiProtocol.ANTHROPIC,
        displayName = "앤트로픽",
        baseUrl = "https://api.anthropic.com",
        model = "claude-sonnet-4",
        presetId = "anthropic",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        maxOutputTokens = 4096,
        priority = 3,
        detectedOutputLimit = 8192,
        temperatureUnsupported = true,
        maxTokensParamUnsupported = true,
        imagesUnsupported = true,
        cooldownUntilMillis = 5_000L,
        inputPricePerMillionTokens = 3.0,
        outputPricePerMillionTokens = 15.0
    )

    // ── ① 부류째 막는 잠금 ────────────────────────────────────────────────────

    /**
     * **설정에 선언된 모든 필드가 JSON 키로 나간다.**
     *
     * 필드 목록을 손으로 적지 않고 [AiProviderConfig] 선언에서 읽는 것이 요점이다 —
     * 새 필드를 더하고 코덱을 갱신하지 않으면 **이 시험이 그 자리에서 실패한다.**
     * B-132는 정확히 그 사고였고, 손으로 적는 시험이었다면 함께 낡았을 것이다.
     */
    @Test
    fun 모든_선언_필드가_JSON에_실린다() {
        // static은 뺀다 — companion이 생기면 `Companion`이 필드로 잡혀 **없는 JSON 키를 요구**하는
        // 헛된 실패가 난다(이 시험이 잡아야 할 것은 그것이 아니다).
        val declared = AiProviderConfig::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertTrue("설정 필드를 하나도 못 읽었다 — 이 시험이 무력화된 상태다", declared.isNotEmpty())
        val encoded = JsonParser.parseString(AiProviderCodec.encode(listOf(full())))
            .asJsonArray[0].asJsonObject
        val missing = declared - encoded.keySet()
        assertTrue(
            "설정 필드가 JSON에 빠졌다 — AiProviderCodec.toJson/fromJson에 등재할 것: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun 모든_값이_채워진_설정이_왕복해도_같다() {
        val original = full()
        val decoded = AiProviderCodec.decode(AiProviderCodec.encode(listOf(original)))
        assertEquals(listOf(original), decoded.configs)
        assertEquals(0, decoded.skipped)
    }

    /** B-132의 증상 그 자체 — 이미지 거부 학습이 저장되고 다시 읽힌다. */
    @Test
    fun 이미지_거부_학습이_왕복한다() {
        val learned = full().copy(detectedOutputLimit = null, temperatureUnsupported = null)
        val decoded = AiProviderCodec.decode(AiProviderCodec.encode(listOf(learned)))
        assertEquals(true, decoded.configs.single().imagesUnsupported)
        assertTrue(decoded.configs.single().hasLearnedFacts())
    }

    @Test
    fun 학습값_셋이_함께_왕복한다() {
        val decoded = AiProviderCodec.decode(AiProviderCodec.encode(listOf(full()))).configs.single()
        assertEquals(8192, decoded.detectedOutputLimit)
        assertEquals(true, decoded.temperatureUnsupported)
        assertEquals(true, decoded.imagesUnsupported)
    }

    @Test
    fun 이미지_거부_false도_그대로_왕복한다() {
        val decoded = AiProviderCodec
            .decode(AiProviderCodec.encode(listOf(full().copy(imagesUnsupported = false))))
        assertEquals(false, decoded.configs.single().imagesUnsupported)
    }

    // ── ② 미설정과 설정은 다른 상태다 ─────────────────────────────────────────

    @Test
    fun null_학습값은_키_자체를_쓰지_않는다() {
        val bare = full().copy(
            presetId = null,
            maxOutputTokens = null,
            detectedOutputLimit = null,
            temperatureUnsupported = null,
            imagesUnsupported = null
        )
        val o = JsonParser.parseString(AiProviderCodec.encode(listOf(bare)))
            .asJsonArray[0].asJsonObject
        listOf(
            "presetId", "maxOutputTokens",
            "detectedOutputLimit", "temperatureUnsupported", "imagesUnsupported"
        ).forEach { assertTrue("$it 키가 남아 있다", !o.has(it)) }
    }

    /** 구버전 설정(키 없음)은 null로 읽혀 종전과 똑같이 동작한다 — 마이그레이션이 필요 없다. */
    @Test
    fun 옛_JSON에_없는_키는_null로_읽힌다() {
        val legacy = """
            [{"id":"p1","protocol":"OPENAI_COMPAT","displayName":"옛것",
              "baseUrl":"https://x.test","model":"m","createdAt":1,"updatedAt":1}]
        """.trimIndent()
        val c = AiProviderCodec.decode(legacy).configs.single()
        assertNull(c.imagesUnsupported)
        assertNull(c.temperatureUnsupported)
        assertNull(c.detectedOutputLimit)
        assertNull(c.maxOutputTokens)
        assertNull(c.presetId)
        assertTrue("학습한 것이 없으면 초기화 고지 대상이 아니다", !c.hasLearnedFacts())
    }

    // ── ③ 손상된 항목이 나머지를 데려가지 않는다 ──────────────────────────────

    @Test
    fun 손상된_항목만_건너뛰고_나머지는_남는다() {
        val good = AiProviderCodec.toJson(full())
        val raw = """[$good,{"id":"broken"},$good]"""
        val decoded = AiProviderCodec.decode(raw)
        assertEquals(2, decoded.configs.size)
        assertEquals(1, decoded.skipped)
        assertTrue(!decoded.unreadable)
    }

    @Test
    fun 목록_자체를_못_읽으면_비우고_그_사실을_알린다() {
        val decoded = AiProviderCodec.decode("{이건 배열이 아니다}")
        assertTrue(decoded.configs.isEmpty())
        assertTrue(decoded.unreadable)
    }

    @Test
    fun 저장된_적_없으면_빈_목록이고_오류가_아니다() {
        val decoded = AiProviderCodec.decode(null)
        assertTrue(decoded.configs.isEmpty())
        assertTrue(!decoded.unreadable)
        assertEquals(0, decoded.skipped)
    }

    /**
     * 목록 순서는 **전환 우선순위 → 등록순**이다 (B-108). 우선순위가 같으면 종전과 같은
     * 등록순이라, 한 번도 끌어 놓지 않은 사용자의 화면은 글자 그대로 옛 순서 그대로다.
     */
    @Test
    fun 등록순으로_정렬된다() {
        val late = full().copy(id = "late", createdAt = 9_000L, priority = 0)
        val early = full().copy(id = "early", createdAt = 1L, priority = 0)
        val decoded = AiProviderCodec.decode(AiProviderCodec.encode(listOf(late, early)))
        assertEquals(listOf("early", "late"), decoded.configs.map { it.id })
    }

    @Test
    fun 우선순위가_등록순을_이긴다() {
        val old = full().copy(id = "old", createdAt = 1L, priority = 2)
        val young = full().copy(id = "young", createdAt = 9_000L, priority = 0)
        val decoded = AiProviderCodec.decode(AiProviderCodec.encode(listOf(old, young)))
        assertEquals(listOf("young", "old"), decoded.configs.map { it.id })
    }

    // ── B-108: 우선순위·쿨다운의 왕복 ─────────────────────────────────────────

    /**
     * **우선순위는 0도 뜻이 있는 값이라 null 생략 규칙을 쓰지 않는다.**
     * 생략하면 맨 앞자리(0)가 저장되지 않아, 끌어 놓아 1위로 올린 프로바이더가
     * 앱을 다시 켜는 순간 옛 자리로 돌아간다 — 사용자 조작이 말없이 사라지는 부류다.
     */
    @Test
    fun 우선순위_0도_JSON에_적힌다() {
        val o = JsonParser.parseString(AiProviderCodec.encode(listOf(full().copy(priority = 0))))
            .asJsonArray[0].asJsonObject
        assertTrue("priority 키가 없다", o.has("priority"))
        assertEquals(0, o.get("priority").asInt)
    }

    @Test
    fun 쿨다운이_왕복한다() {
        val decoded = AiProviderCodec
            .decode(AiProviderCodec.encode(listOf(full().copy(cooldownUntilMillis = 1_700_000_000_000L))))
        assertEquals(1_700_000_000_000L, decoded.configs.single().cooldownUntilMillis)
    }

    /** 쿨다운 없음은 키 자체를 쓰지 않는다 — "미설정"과 "0에 만료"는 다른 상태다. */
    @Test
    fun 쿨다운_없음은_키를_쓰지_않는다() {
        val o = JsonParser
            .parseString(AiProviderCodec.encode(listOf(full().copy(cooldownUntilMillis = null))))
            .asJsonArray[0].asJsonObject
        assertTrue("cooldownUntilMillis 키가 남아 있다", !o.has("cooldownUntilMillis"))
    }

    // ── 이식(엑셀·월드패키지) — 기기에 매인 칸은 나가지 않는다 (2026.08.25) ──

    /** 쿨다운은 벽시계 한 점이라 파일을 타면 뜻을 잃는다 — 싣지 않는다. */
    @Test
    fun 이식_인코딩은_쿨다운을_싣지_않는다() {
        val json = AiProviderCodec
            .encodeForTransfer(listOf(full().copy(cooldownUntilMillis = 1_700_000_000_000L)))
        val o = JsonParser.parseString(json).asJsonArray[0].asJsonObject
        assertTrue("이식 목록에 cooldownUntilMillis가 실렸다", !o.has("cooldownUntilMillis"))
    }

    /** 나머지 학습값 넷은 그 모델·그 주소의 사실이라 기기를 옮겨도 참이다 — 함께 나간다. */
    @Test
    fun 이식_인코딩은_모델_학습값은_그대로_싣는다() {
        val json = AiProviderCodec.encodeForTransfer(
            listOf(
                full().copy(
                    cooldownUntilMillis = 1L,
                    detectedOutputLimit = 4096,
                    temperatureUnsupported = true,
                    maxTokensParamUnsupported = true,
                    imagesUnsupported = true
                )
            )
        )
        val c = AiProviderCodec.decode(json).configs.single()
        assertEquals(4096, c.detectedOutputLimit)
        assertEquals(true, c.temperatureUnsupported)
        assertEquals(true, c.maxTokensParamUnsupported)
        assertEquals(true, c.imagesUnsupported)
        assertNull(c.cooldownUntilMillis)
    }

    /** 저장소가 쓰는 [AiProviderCodec.encode]는 그대로다 — 갈라 둔 것이 이 수리의 요점이다. */
    @Test
    fun 저장_인코딩은_여전히_쿨다운을_싣는다() {
        val decoded = AiProviderCodec
            .decode(AiProviderCodec.encode(listOf(full().copy(cooldownUntilMillis = 42L))))
        assertEquals(42L, decoded.configs.single().cooldownUntilMillis)
    }

    /** 옛 파일이 쿨다운을 들고 있어도 이 기기의 값이 이긴다. */
    @Test
    fun 들여오기는_이_기기의_쿨다운을_지킨다() {
        val incoming = full().copy(cooldownUntilMillis = 9_999_999_999_999L)
        val current = full().copy(cooldownUntilMillis = 55L)
        assertEquals(55L, AiProviderCodec.keepDeviceState(incoming, current).cooldownUntilMillis)
    }

    /** 이 기기에 없던 프로바이더는 밀어 둘 근거가 없다 — 쿨다운 없음으로 들어온다. */
    @Test
    fun 처음_보는_프로바이더는_쿨다운_없이_들어온다() {
        val incoming = full().copy(cooldownUntilMillis = 9_999_999_999_999L)
        assertNull(AiProviderCodec.keepDeviceState(incoming, null).cooldownUntilMillis)
    }

    /** 구버전 설정에는 둘 다 없다 — 우선순위는 0, 쿨다운은 없음으로 읽혀 종전과 같다. */
    @Test
    fun 옛_JSON은_우선순위_0에_쿨다운_없음으로_읽힌다() {
        val legacy = """
            [{"id":"p1","protocol":"OPENAI_COMPAT","displayName":"옛것",
              "baseUrl":"https://x.test","model":"m","createdAt":1,"updatedAt":1}]
        """.trimIndent()
        val c = AiProviderCodec.decode(legacy).configs.single()
        assertEquals(0, c.priority)
        assertNull(c.cooldownUntilMillis)
    }
}
