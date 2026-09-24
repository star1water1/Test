package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사용량 원장 — 더하기·걷어내기·요약·직렬화 왕복 잠금.
 *
 * 핵심 계약 셋:
 * ① 일 통은 [AiUsageLedger.RETENTION_DAYS]로 걷히지만 **총계는 걷히지 않는다** — "누적"이
 *    조용히 줄어드는 것은 집계가 하는 가장 나쁜 거짓말이다.
 * ② 토큰 미보고 요청은 0토큰으로 합치지 않고 [AiUsageLedger.Summary.unmeteredRequests]로
 *    갈라 센다 — 화면이 "이 수에는 미보고 N건이 있다"를 말할 수 있어야 한다.
 * ③ 직렬화 왕복은 무손실이고, 손상된 항목 하나가 원장 전체를 버리지 않는다.
 */
class AiUsageLedgerTest {

    private fun record(
        data: AiUsageLedger.Data,
        day: Long,
        id: String = "p1",
        name: String = "앤트로픽",
        model: String = "claude-opus-5",
        input: Int? = 100,
        output: Int? = 50
    ) = AiUsageLedger.record(data, day, id, name, model, input, output)

    // ── 더하기 ────────────────────────────────────────────────────────────────

    @Test
    fun 같은_날_같은_프로바이더는_한_통에_쌓인다() {
        var d = record(AiUsageLedger.Data(), day = 100)
        d = record(d, day = 100)
        assertEquals(1, d.days.size)
        assertEquals(2, d.days[0].requests)
        assertEquals(200L, d.days[0].inputTokens)
        assertEquals(100L, d.days[0].outputTokens)
        assertEquals(1, d.totals.size)
        assertEquals(2, d.totals[0].requests)
        assertEquals(100L, d.totals[0].sinceDay)
    }

    @Test
    fun 날짜와_프로바이더가_다르면_통이_갈린다() {
        var d = record(AiUsageLedger.Data(), day = 100, id = "p1")
        d = record(d, day = 100, id = "p2", name = "제미나이")
        d = record(d, day = 101, id = "p1")
        assertEquals(3, d.days.size)
        assertEquals(2, d.totals.size)
    }

    @Test
    fun 같은_프로바이더의_서로_다른_모델은_섞이지_않는다() {
        var d = record(AiUsageLedger.Data(), day = 100, name = "옛 이름", model = "old-model")
        d = record(d, day = 100, name = "새 이름", model = "new-model")
        assertEquals(2, d.days.size)
        assertEquals(2, d.totals.size)
        assertEquals(setOf("old-model", "new-model"), d.totals.map { it.model }.toSet())
        assertEquals(1, d.totals.single { it.model == "old-model" }.requests)
    }

    @Test
    fun 토큰_미보고는_0으로_합치지_않고_갈라_센다() {
        var d = record(AiUsageLedger.Data(), day = 100, input = null, output = null)
        d = record(d, day = 100, input = 100, output = 50)
        assertEquals(2, d.days[0].requests)
        assertEquals(1, d.days[0].unmeteredRequests)
        assertEquals(100L, d.days[0].inputTokens)
        // 한쪽만 온 응답도 누락을 알리되, 보고된 쪽의 토큰 수는 보존한다.
        d = record(d, day = 100, input = 30, output = null)
        assertEquals(2, d.days[0].unmeteredRequests)
        assertEquals(130L, d.days[0].inputTokens)
    }

    // ── 걷어내기 ──────────────────────────────────────────────────────────────

    @Test
    fun 보관_날수를_넘긴_일_통은_걷히지만_총계는_남는다() {
        var d = record(AiUsageLedger.Data(), day = 100)
        val later = 100L + AiUsageLedger.RETENTION_DAYS
        d = record(d, later)
        assertEquals(listOf(later), d.days.map { it.epochDay })
        // 총계는 걷힌 날의 몫까지 그대로 든다 — "누적"이 조용히 줄면 거짓말이다
        assertEquals(2, d.totals[0].requests)
        assertEquals(100L, d.totals[0].sinceDay)
    }

    @Test
    fun 보관_경계_안의_일_통은_남는다() {
        var d = record(AiUsageLedger.Data(), day = 100)
        d = record(d, 100L + AiUsageLedger.RETENTION_DAYS - 1)
        assertEquals(2, d.days.size)
    }

    // ── 요약 ──────────────────────────────────────────────────────────────────

    @Test
    fun 기간_요약은_fromDay_이후만_프로바이더별로_합친다() {
        var d = record(AiUsageLedger.Data(), day = 100)
        d = record(d, day = 105)
        d = record(d, day = 105, id = "p2", name = "제미나이", input = 10, output = 5)
        val since105 = AiUsageLedger.summarize(d.days, fromDay = 105)
        assertEquals(2, since105.size)
        assertEquals(1, since105.first { it.providerId == "p1" }.requests)
        val all = AiUsageLedger.summarize(d.days, fromDay = 100)
        assertEquals(2, all.first { it.providerId == "p1" }.requests)
    }

    @Test
    fun 요약_정렬은_쓴_토큰_큰_순이고_동률은_이름으로_가른다() {
        var d = record(AiUsageLedger.Data(), day = 100, id = "small", name = "ㄴ작음", input = 1, output = 1)
        d = record(d, day = 100, id = "big", name = "ㄱ큼", input = 1000, output = 500)
        d = record(d, day = 100, id = "tie", name = "ㄱ동률", input = 1, output = 1)
        val s = AiUsageLedger.summarize(d.days, fromDay = 100)
        assertEquals(listOf("big", "tie", "small"), s.map { it.providerId })
    }

    @Test
    fun 누적_요약과_첫_기록일() {
        var d = record(AiUsageLedger.Data(), day = 100)
        d = record(d, day = 90, id = "p2", name = "제미나이")
        assertEquals(90L, AiUsageLedger.earliestSinceDay(d.totals))
        assertEquals(2, AiUsageLedger.totalsSummary(d.totals).size)
        assertNull(AiUsageLedger.earliestSinceDay(emptyList()))
    }

    // ── 직렬화 ────────────────────────────────────────────────────────────────

    @Test
    fun 직렬화_왕복은_무손실이다() {
        var d = record(AiUsageLedger.Data(), day = 100, input = null, output = null)
        d = record(d, day = 101, id = "p2", name = "제미나이", model = "gemini-3.6-flash")
        val back = AiUsageCodec.decode(AiUsageCodec.encode(d))
        assertEquals(d, back)
    }

    @Test
    fun 구버전_사용량은_모델과_당시_가격을_추측하지_않고_보존한다() {
        val old = AiUsageCodec.encode(record(AiUsageLedger.Data(), day = 100, model = "last-model"))
            .replace("\"version\":2,", "")
            .replace(Regex(",\"modelKnown\":true,\"pricedCost\":0.0,\"unpricedRequests\":1"), "")
        var restored = AiUsageCodec.decode(old)
        assertEquals(1, restored.totals.size)
        assertEquals(false, restored.totals[0].modelKnown)
        assertEquals(1, restored.totals[0].unpricedRequests)
        restored = record(restored, day = 100, model = "last-model")
        assertEquals(2, restored.totals.size)
        assertEquals(1, restored.totals.single { !it.modelKnown }.requests)
        assertEquals(1, restored.totals.single { it.modelKnown }.requests)
        assertEquals(restored, AiUsageCodec.decode(AiUsageCodec.encode(restored)))
    }

    @Test
    fun 일부만_갱신된_원장과_손상된_배열도_기존_총계를_남긴다() {
        val current = AiUsageCodec.encode(record(AiUsageLedger.Data(), 100))
        val partial = current.replace("\"unpricedRequests\":1", "")
            .replace(",}", "}")
            .replace("\"speechDays\":[]", "\"speechDays\":{}")
        val decoded = AiUsageCodec.decode(partial)
        assertEquals(1, decoded.totals.single().requests)
        assertEquals(1, decoded.totals.single().unpricedRequests)
        assertTrue(decoded.speechDays.isEmpty())
    }

    @Test
    fun 요청_당시_가격으로_더하고_나중_가격_변경은_옛_비용을_바꾸지_않는다() {
        var d = AiUsageLedger.record(AiUsageLedger.Data(), 100, "p1", "제공자", "model-a",
            1_000_000, 1_000_000, 1.0, 2.0)
        d = AiUsageLedger.record(d, 101, "p1", "제공자", "model-a",
            1_000_000, 1_000_000, 3.0, 4.0)
        assertEquals(10.0, d.totals.single().pricedCost, 0.0001)
        assertEquals(0, d.totals.single().unpricedRequests)
        d = record(d, 102, model = "model-b")
        assertEquals(10.0, d.totals.single { it.model == "model-a" }.pricedCost, 0.0001)
        assertEquals(1, d.totals.single { it.model == "model-b" }.unpricedRequests)
    }

    @Test
    fun 음성은_날짜_제공자_모델별로_나뉘고_시간과_단가_미확인은_별도다() {
        var d = AiUsageLedger.recordSpeech(AiUsageLedger.Data(), 100, "p1", "가", "speech-a", 90, 2.0)
        d = AiUsageLedger.recordSpeech(d, 100, "p1", "가", "speech-b", 30, null)
        d = AiUsageLedger.recordSpeech(d, 101, "p2", "나", "speech-a", 0, 2.0)
        assertEquals(3, d.speechDays.size)
        assertEquals(3, d.speechTotals.size)
        assertEquals(3.0, d.speechTotals.single { it.model == "speech-a" && it.providerId == "p1" }.pricedCost, 0.0001)
        assertEquals(1, d.speechTotals.single { it.model == "speech-b" }.unpricedRequests)
        assertEquals(1, d.speechTotals.single { it.providerId == "p2" }.unknownDurationRequests)
        assertEquals(1, AiUsageLedger.summarizeSpeech(d.speechDays, 101).size)
        assertEquals(d, AiUsageCodec.decode(AiUsageCodec.encode(d)))
    }

    @Test
    fun 같은_녹음을_다시_전사하면_새_유료_요청으로_세고_기간은_날짜대로_가른다() {
        var d = AiUsageLedger.recordSpeech(AiUsageLedger.Data(), 100, "p", "제공자", "m", 45)
        d = AiUsageLedger.recordSpeech(d, 100, "p", "제공자", "m", 45)
        d = AiUsageLedger.recordSpeech(d, 107, "p", "제공자", "m", 20)
        d = AiUsageLedger.recordSpeech(d, 130, "p", "제공자", "m", 10)
        assertEquals(1, AiUsageLedger.summarizeSpeech(d.speechDays, 124).single().requests)
        assertEquals(2, AiUsageLedger.summarizeSpeech(d.speechDays, 101).single().requests)
        assertEquals(4, AiUsageLedger.speechTotalsSummary(d.speechTotals).single().requests)
        assertEquals(120L, AiUsageLedger.speechTotalsSummary(d.speechTotals).single().seconds)
    }

    @Test
    fun 빈_원장과_깨진_원문은_빈_데이터로_돌아온다() {
        assertEquals(AiUsageLedger.Data(), AiUsageCodec.decode(null))
        assertEquals(AiUsageLedger.Data(), AiUsageCodec.decode(""))
        assertEquals(AiUsageLedger.Data(), AiUsageCodec.decode("깨진 JSON"))
    }

    @Test
    fun 손상된_항목_하나가_원장_전체를_버리지_않는다() {
        val d = record(AiUsageLedger.Data(), day = 100)
        val json = AiUsageCodec.encode(d)
            .replace("\"days\":[", "\"days\":[{\"providerId\":\"필수칸없음\"},")
        val back = AiUsageCodec.decode(json)
        assertEquals(1, back.days.size)
        assertEquals("p1", back.days[0].providerId)
        assertTrue(back.totals.isNotEmpty())
    }

    @Test
    fun 단가나_토큰이_빠진_요청은_비용_미확인으로_남긴다() {
        var d = AiUsageLedger.record(AiUsageLedger.Data(), 100, "p1", "가", "model-a",
            100, 50, 1.0, null)
        d = AiUsageLedger.record(d, 100, "p1", "가", "model-a",
            null, 50, 1.0, 2.0)
        assertEquals(2, d.totals.single().unpricedRequests)
        assertEquals(0.0, d.totals.single().pricedCost, 0.0001)
    }
}
