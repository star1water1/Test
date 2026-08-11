package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.NarrativeFieldAiWriter.BulkDraftExcludeCause
import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서술형 일괄 초안의 대상 규칙과 비용 계산 (B-45).
 *
 * **이 판이 지키는 방어선은 셋이다** — 건수가 아니라 이 셋이 요점이다.
 *
 * ① *"짧은 값 경로와 대상이 서로 배타적이다"* — 두 `bulkTargetsOf`가 같은 필드를 함께
 *   가져가면 사용자는 **한 필드에 두 번 결제**하고, 산문 자리에 한 줄짜리 값이 덮인다.
 *   두 함수를 같은 입력에 세워 교집합이 비는 것을 실제로 잰다.
 *
 * ② *"이미 쓴 필드는 초안 대상이 아니다"* — 초안은 빈 칸을 채우는 모드라 원문을 보지 않는다.
 *   대상에 들어가면 검토 창이 **원문을 지우는 후보**를 기본 선택으로 들이민다.
 *
 * ③ *"요청 수는 필드 수다"* — 짧은 값 일괄과 자릿수가 다른 것이 B-45가 착수 조건으로 건
 *   바로 그 사실이다. 고지와 실행이 각자 세면 고지된 요청 수와 실제 과금이 어긋난다(R-4).
 */
class NarrativeBulkDraftTest {

    private var nextId = 1L

    private fun field(
        key: String,
        type: String = "TEXT",
        config: String = """{"narrativeMode":"narrative"}"""
    ) = FieldDefinition(
        id = nextId++, universeId = 1L, key = key, name = "이름_$key",
        type = type, config = config, entityType = FieldDefinition.ENTITY_CHARACTER
    )

    // ===== ① 두 경로의 대상은 겹치지 않는다 =====

    @Test
    fun 두_일괄_경로의_대상이_서로_배타적이다() {
        val fields = listOf(
            field("성격"),                                             // 서술형 → 이쪽
            field("배경", config = """{"displayFormat":"multiline"}"""), // AUTO 서술형 → 이쪽
            field("머리색", config = "{}"),                             // 짧은 값 → 저쪽
            field("나이", type = "NUMBER", config = "{}")               // 짧은 값 → 저쪽
        )
        val narrative = NarrativeFieldAiWriter.bulkDraftTargetsOf(fields, emptyMap())
        val short = CharacterFieldAiSuggester.bulkTargetsOf(fields, emptyMap())

        val narrativeIds = narrative.targets.map { it.fieldId }.toSet()
        val shortIds = short.targets.map { it.fieldId }.toSet()

        assertEquals(setOf("이름_성격", "이름_배경"), narrative.targets.map { it.spec.name }.toSet())
        // 교집합이 비어야 한다 — 겹치면 한 필드를 두 번 결제한다.
        assertTrue(
            "두 경로가 같은 필드를 가져갔다: ${narrativeIds intersect shortIds}",
            (narrativeIds intersect shortIds).isEmpty()
        )
        // 그리고 합이 입력 전체다 — 어느 쪽도 안 가는 필드가 조용히 생기지 않는다.
        assertEquals(fields.size, narrativeIds.size + shortIds.size)
    }

    @Test
    fun 서술형이_아닌_필드는_제외에도_담기지_않는다() {
        // 담으면 "제외 N개" 고지가 짧은 값 필드까지 세어 거짓이 된다.
        val fields = listOf(field("성격"), field("머리색", config = "{}"))
        val bulk = NarrativeFieldAiWriter.bulkDraftTargetsOf(fields, emptyMap())

        assertEquals(1, bulk.targets.size)
        assertEquals(0, bulk.excludedCount)
    }

    // ===== ② 이미 쓴 필드 =====

    @Test
    fun 이미_쓴_필드는_대상에서_빠지고_사유가_남는다() {
        val written = field("성격")
        val empty = field("배경")
        val bulk = NarrativeFieldAiWriter.bulkDraftTargetsOf(
            listOf(written, empty),
            mapOf(written.id to "그는 말이 없다.")
        )

        assertEquals(listOf("이름_배경"), bulk.targets.map { it.spec.name })
        assertEquals(listOf("이름_성격"), bulk.excluded[BulkDraftExcludeCause.ALREADY_WRITTEN])
    }

    @Test
    fun 공백만_있는_값은_쓴_것으로_보지_않는다() {
        // 빈칸·줄바꿈만 남은 필드를 '작성됨'으로 세면 정작 비어 있는 필드가 초안을 못 받는다.
        val f = field("성격")
        val bulk = NarrativeFieldAiWriter.bulkDraftTargetsOf(listOf(f), mapOf(f.id to "   \n "))

        assertEquals(1, bulk.targets.size)
        assertEquals(0, bulk.excludedCount)
    }

    @Test
    fun 꺼짐이_이미_작성됨보다_앞선_사유다() {
        // 사용자가 끈 결정이 폼 상태보다 앞선 원인이다 — 짧은 값 경로의 같은 규약과 맞춘다.
        val f = field("성격", config = """{"aiSuggest":false,"narrativeMode":"narrative"}""")
        val bulk = NarrativeFieldAiWriter.bulkDraftTargetsOf(listOf(f), mapOf(f.id to "이미 쓴 글"))

        assertEquals(listOf("이름_성격"), bulk.excluded[BulkDraftExcludeCause.AI_DISABLED])
        assertFalse(bulk.excluded.containsKey(BulkDraftExcludeCause.ALREADY_WRITTEN))
    }

    @Test
    fun 개별만_받기로_둔_필드는_일괄에서만_빠진다() {
        val f = field("성격", config = """{"aiSuggest":"manual","narrativeMode":"narrative"}""")
        val bulk = NarrativeFieldAiWriter.bulkDraftTargetsOf(listOf(f), emptyMap())

        assertEquals(listOf("이름_성격"), bulk.excluded[BulkDraftExcludeCause.MANUAL_ONLY])
        // 고지가 교정 경로를 함께 말한다 — 제외가 '기능 부재'로 읽히면 사용자는 그 필드를
        // 영영 손대지 못한다고 여긴다.
        val line = NarrativeFieldAiWriter.bulkDraftExcludedDetailLines(bulk.excluded).single()
        assertTrue(line, line.contains("✨"))
    }

    @Test
    fun 제외_상세가_사유마다_교정_경로를_단다() {
        // 사유가 셋인데 하나라도 경로가 없으면 그 사유를 만난 사용자만 길을 잃는다.
        for (cause in BulkDraftExcludeCause.entries) {
            val line = NarrativeFieldAiWriter
                .bulkDraftExcludedDetailLines(mapOf(cause to listOf("필드")))
                .single()
            assertTrue("$cause 에 교정 경로가 없다: $line", line.contains(" — "))
        }
    }

    // ===== ③ 비용 =====

    @Test
    fun 요청_수는_필드_수와_같다() {
        // 짧은 값 일괄은 여러 필드를 한 요청에 묶지만(필드 15개 = 요청 1건) 서술형은 못 묶는다.
        assertEquals(0, NarrativeFieldAiWriter.draftRequestCount(0))
        assertEquals(1, NarrativeFieldAiWriter.draftRequestCount(1))
        assertEquals(5, NarrativeFieldAiWriter.draftRequestCount(5))
    }

    @Test
    fun 서술형과_짧은_값의_요청_수가_실제로_자릿수가_다르다() {
        // B-45가 착수 조건으로 건 사실 자체를 못 박는다 — 같은 필드 수에서 두 경로의
        // 요청 수가 같아지면 그 순간 비용 고지 문구를 하나로 합쳐도 되는 것처럼 보인다.
        val n = 15
        val short = CharacterFieldAiSuggester.requestCountFor(n, AiTokenPolicy.DEFAULT_REQUEST)
        val narrative = NarrativeFieldAiWriter.draftRequestCount(n)

        assertEquals(1, short)
        assertEquals(15, narrative)
        assertTrue(narrative > short)
    }

    @Test
    fun 일괄_후보는_하나이고_필드별보다_적다() {
        // 이중 경로의 축이다 — 일괄은 러프하게 깔고(후보 1개) 필드별 ✨이 정밀 조정한다(2개).
        // 같아지면 일괄 검토가 필드 수 × 2가 되어 '한 번에 깔기'이기를 그친다.
        assertEquals(1, NarrativeFieldAiWriter.BULK_DRAFT_VARIANTS)
        assertTrue(
            NarrativeFieldAiWriter.BULK_DRAFT_VARIANTS < NarrativeFieldAiWriter.DEFAULT_VARIANTS
        )
    }

    @Test
    fun 분량이_길수록_추정_토큰이_커진다() {
        val short = NarrativeFieldAiWriter.tokensPerDraft(NarrativeFieldAiWriter.Length.SHORT)
        val medium = NarrativeFieldAiWriter.tokensPerDraft(NarrativeFieldAiWriter.Length.MEDIUM)
        val long = NarrativeFieldAiWriter.tokensPerDraft(NarrativeFieldAiWriter.Length.LONG)

        assertTrue(short < medium)
        assertTrue(medium < long)
    }

    @Test
    fun 출력_상한이_낮으면_절단_위험을_미리_말한다() {
        // 잘린 뒤에 알리면 이미 요청 수만큼 결제가 끝난 뒤다 — 판정이 **실행 전에** 서야 한다.
        val long = NarrativeFieldAiWriter.Length.LONG

        assertFalse(NarrativeFieldAiWriter.draftFitsBudget(AiTokenPolicy.FLOOR, long))
        assertTrue(
            NarrativeFieldAiWriter.draftFitsBudget(AiTokenPolicy.DEFAULT_REQUEST, long)
        )
        // 기본 상한이 가장 긴 분량을 감당한다는 사실 자체가 계약이다 — 감당 못 하면
        // 기본 설정으로 돌린 사용자가 매번 경고를 보게 되고, 그러면 경고가 무시된다.
        for (length in NarrativeFieldAiWriter.Length.entries) {
            assertTrue(
                "기본 상한이 $length 를 감당하지 못한다",
                NarrativeFieldAiWriter.draftFitsBudget(AiTokenPolicy.DEFAULT_REQUEST, length)
            )
        }
    }

    @Test
    fun 후보가_늘면_같은_상한에서_절단_위험이_커진다() {
        val long = NarrativeFieldAiWriter.Length.LONG
        val budget = NarrativeFieldAiWriter.tokensPerDraft(long) * 2

        assertTrue(NarrativeFieldAiWriter.draftFitsBudget(budget, long, variants = 2))
        assertFalse(NarrativeFieldAiWriter.draftFitsBudget(budget, long, variants = 3))
    }
}
