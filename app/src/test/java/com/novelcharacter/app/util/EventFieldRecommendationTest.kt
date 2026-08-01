package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사건 필드 추천(설계 `event_field_recommend_2026-08`) — 추천 세트와 값 사전 등록 해석.
 *
 * **config JSON을 파싱까지 해 보는 것이 이 테스트의 값어치다.** 추천 세트의 설정은 손으로 쓴
 * 문자열이라 오타가 나면 선택지·등급표가 **조용히 빈 목록**이 되고, 화면에서는 "고를 것이 없는
 * 필드"로만 보인다. 규칙만 검사하면 그 자리를 못 잡는다.
 */
class EventFieldRecommendationTest {

    private val eventRecommendations = PresetTemplates.recommendedFields(FieldDefinition.ENTITY_EVENT)

    // ── 추천 세트 ──

    @Test
    fun `사건 추천은 설계 8장의 여섯이다`() {
        assertEquals(
            listOf("회차", "장소", "시점 인물", "이야기 단계", "중요도", "공개 상태"),
            eventRecommendations.map { it.name }
        )
    }

    @Test
    fun `추천은 전부 사건 종류이고 키가 겹치지 않는다`() {
        assertTrue(eventRecommendations.all { it.entityType == FieldDefinition.ENTITY_EVENT })
        val keys = eventRecommendations.map { it.key }
        assertEquals("키가 겹치면 두 번째부터 중복 키로 실패한다", keys.size, keys.distinct().size)
    }

    @Test
    fun `추천은 자기들끼리 0부터 순서를 갖는다`() {
        assertEquals(
            eventRecommendations.indices.toList(),
            eventRecommendations.map { it.displayOrder }
        )
    }

    @Test
    fun `사건이 아닌 종류에는 추천이 없다 - 축 중립이되 켜진 것은 사건뿐`() {
        assertTrue(PresetTemplates.recommendedFields(FieldDefinition.ENTITY_CHARACTER).isEmpty())
        assertTrue(PresetTemplates.recommendedFields(FieldDefinition.ENTITY_NOVEL).isEmpty())
    }

    @Test
    fun `추천은 전부 설명을 들고 온다 - 도착한 자리에서 무엇을 적을지 말한다`() {
        for (field in eventRecommendations) {
            val description = com.novelcharacter.app.data.model.FieldDescription.fromConfig(field.config)
            assertTrue("'${field.name}'에 설명이 없다", description.isNotBlank())
        }
    }

    // ── config가 실제로 파싱되는가 (오타 방어) ──

    @Test
    fun `이야기 단계와 공개 상태의 선택지가 실제로 파싱된다`() {
        val stage = eventRecommendations.first { it.key == "story_stage" }
        assertEquals(
            listOf("도입", "전개", "절정", "결말"),
            FieldOptionParser.parseSelectOptions(stage.config)
        )
        val disclosure = eventRecommendations.first { it.key == "disclosure" }
        assertEquals(
            listOf("공개", "복선", "비공개"),
            FieldOptionParser.parseSelectOptions(disclosure.config)
        )
    }

    @Test
    fun `중요도의 등급표가 실제로 파싱되고 독자 표다`() {
        val importance = eventRecommendations.first { it.key == "importance" }
        val rows = GradeTable.fromConfigRows(importance.config)
        assertEquals(listOf("C", "B", "A", "S"), rows.map { it.first })
        // 세계관 등급 체계를 참조하지 않는다 — 프리셋은 체계를 만들지 않으므로 참조를 심으면
        // 유령 참조가 되고 복사 경계 강등(R-30)에 걸린다. 설계 8장 확정.
        assertFalse("추천은 등급 체계를 참조하면 안 된다", importance.config.contains("gradeSystem"))
    }

    @Test
    fun `장소와 시점 인물은 값 라이브러리를 지원한다`() {
        val place = eventRecommendations.first { it.key == "place" }
        val pov = eventRecommendations.first { it.key == "pov" }
        assertTrue(FieldValueTokenizer.supportsLibrary(place))
        assertTrue(FieldValueTokenizer.supportsLibrary(pov))
    }

    @Test
    fun `내장 프리셋 본문에는 추천이 들어 있지 않다 - 자동으로 심지 않는다`() {
        // D1: 추천을 PresetTemplate.fields에 넣으면 프리셋을 고른 모든 새 세계관이 받게 된다.
        for (template in PresetTemplates.getBuiltInTemplates()) {
            assertTrue(
                "'${template.universe.name}' 프리셋이 사건 필드를 자동으로 심는다",
                template.fields.none { it.entityType == FieldDefinition.ENTITY_EVENT }
            )
        }
    }

    // ── 값 사전 등록 해석 ──

    private fun textField(config: String = "") =
        FieldDefinition(universeId = 1, key = "k", name = "n", type = "TEXT", config = config)

    @Test
    fun `콤마로 나누고 공백을 떼고 빈 것과 중복을 버린다`() {
        assertEquals(
            listOf("검", "활", "지팡이"),
            InitialFieldValues.parse(" 검 , 활 ,, 지팡이 , 검 ", textField())
        )
    }

    @Test
    fun `라이브러리를 지원하지 않는 타입은 통째로 비운다`() {
        val number = FieldDefinition(universeId = 1, key = "n", name = "수", type = "NUMBER")
        assertTrue(InitialFieldValues.parse("1,2,3", number).isEmpty())
    }

    @Test
    fun `빈 문자열은 빈 목록이다`() {
        assertTrue(InitialFieldValues.parse("", textField()).isEmpty())
        assertTrue(InitialFieldValues.parse("  ,  , ", textField()).isEmpty())
    }

    @Test
    fun `구조화 입력 필드는 라이브러리 대상이 아니다`() {
        val structured = textField("""{"structuredInput":{"enabled":true,"separator":"-","parts":[]}}""")
        assertNotNull(structured.config)
        assertTrue(InitialFieldValues.parse("가,나", structured).isEmpty())
    }
}
