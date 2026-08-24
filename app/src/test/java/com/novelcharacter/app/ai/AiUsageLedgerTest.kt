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
    fun 표시명과_모델은_마지막으로_본_값이_남는다() {
        var d = record(AiUsageLedger.Data(), day = 100, name = "옛 이름", model = "old-model")
        d = record(d, day = 100, name = "새 이름", model = "new-model")
        assertEquals("새 이름", d.days[0].displayName)
        assertEquals("new-model", d.days[0].model)
        assertEquals("새 이름", d.totals[0].displayName)
    }

    @Test
    fun 토큰_미보고는_0으로_합치지_않고_갈라_센다() {
        var d = record(AiUsageLedger.Data(), day = 100, input = null, output = null)
        d = record(d, day = 100, input = 100, output = 50)
        assertEquals(2, d.days[0].requests)
        assertEquals(1, d.days[0].unmeteredRequests)
        assertEquals(100L, d.days[0].inputTokens)
        // 한쪽만 온 응답은 미보고가 아니다 — 온 쪽만 더한다(버리는 것보다 작게 잃는다)
        d = record(d, day = 100, input = 30, output = null)
        assertEquals(1, d.days[0].unmeteredRequests)
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
}
