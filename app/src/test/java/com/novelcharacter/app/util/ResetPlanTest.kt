package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱 초기화가 **모든 테이블을 비우는가** (S-13 / 색출 로드맵 7).
 *
 * 이 테스트가 지키는 것은 "지금 코드가 맞다"가 아니라 **"엔티티가 늘면 여기가 깨진다"**이다.
 * 종전 결함이 정확히 그렇게 생겼다 — 테이블이 26개로 늘도록 초기화 목록은 16개에 머물렀고,
 * 아무도 그것을 알려주지 않았다.
 *
 * `AppDatabase.entities`와 이 목록이 실제로 같은지는 `tools/verify_reset_coverage.py`가
 * 소스를 파싱해 대조한다(이 테스트는 Room을 실행할 수 없으므로 목록을 들고 있어야 한다).
 */
class ResetPlanTest {

    /**
     * `AppDatabase.entities`의 tableName 전수. **여기를 손으로 고치는 일이 곧 신호다** —
     * 엔티티를 늘렸다면 [ResetPlan]에도 처분을 적어야 한다.
     */
    private val entityTables = listOf(
        "novels", "characters", "timeline_events", "timeline_character_cross_ref",
        "timeline_event_novel_cross_ref", "universes", "field_definitions",
        "character_field_values", "character_state_changes", "character_tags",
        // 명대사 (사용자 요청 2026.08.20) — characters CASCADE.
        "character_quotes",
        "name_bank", "character_relationships", "character_relationship_changes",
        "recent_activities", "search_presets", "user_preset_templates",
        "factions", "faction_memberships", "faction_relationships",
        "trash_snapshots", "event_field_values", "novel_field_values", "operation_logs",
        "character_list_presets", "image_meta", "image_tags", "field_value_entries",
        "grade_systems",
        // 대결 (B-104) — 축은 세계관 CASCADE, 판·처분은 축 CASCADE.
        "duel_axes", "duel_matches", "duel_counter_verdicts",
        // 전역 기본 필드 템플릿 (B-119) — **전역이라 매달릴 부모가 없다.** 세계관을 지워도
        // 남으므로 유일하게 직접 삭제로만 비워진다.
        "default_field_templates"
    )

    @Test fun 모든_엔티티_테이블이_초기화_계획에_있다() {
        assertEquals(
            "초기화 후에도 살아남을 테이블이 있다 — '모든 데이터 삭제' 약속 위반",
            emptyList<String>(),
            ResetPlan.uncovered(entityTables)
        )
    }

    @Test fun 계획에_없는_테이블을_들고_있지_않다() {
        assertEquals(
            "ResetPlan이 실재하지 않는 테이블을 지운다고 적고 있다 (표가 낡음)",
            emptyList<String>(),
            ResetPlan.unknown(entityTables)
        )
    }

    /**
     * **핵심 불변식.** CASCADE는 부모가 실제로 비워질 때만 성립한다. 부모를 빠뜨린 채
     * "CASCADE로 지워집니다"라고 적어 두면, 비워진다고 믿는 테이블이 영원히 남는다 —
     * `image_tags`(부모 `image_meta` 누락)가 실제로 그 상태였다.
     */
    @Test fun 부모가_비워지지_않는_CASCADE가_없다() {
        val broken = ResetPlan.brokenCascades()
        assertTrue(
            "CASCADE 부모가 초기화되지 않아 실제로는 남는 테이블: " +
                broken.joinToString { "${it.table}(부모 ${it.via})" },
            broken.isEmpty()
        )
    }

    @Test fun 계획에_중복_테이블이_없다() {
        val dupes = ResetPlan.plan.map { it.table }.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertEquals("같은 테이블이 두 번 등재됨", emptyMap<String, Int>(), dupes)
    }

    /** 직접 삭제 순서는 FK 자식이 부모보다 앞서야 한다(트랜잭션 중 FK 위반 방지). */
    @Test fun 직접_삭제_순서가_FK_자식_먼저다() {
        val order = ResetPlan.explicitOrder
        fun before(child: String, parent: String) {
            val c = order.indexOf(child)
            val p = order.indexOf(parent)
            assertTrue("$child(자식)이 $parent(부모)보다 뒤에 지워진다", c in 0..<p)
        }
        before("character_relationship_changes", "character_relationships")
        before("character_relationships", "characters")
        before("faction_memberships", "factions")
        before("factions", "universes")
        before("character_state_changes", "characters")
        before("timeline_character_cross_ref", "timeline_events")
        before("timeline_event_novel_cross_ref", "timeline_events")
        before("characters", "novels")
        before("field_definitions", "universes")
        before("novels", "universes")
    }

    /** S-13이 추가한 넷이 실제로 직접 삭제 목록에 있다(회귀 잠금). */
    @Test fun S13이_추가한_독립_테이블이_직접_삭제된다() {
        for (t in listOf("trash_snapshots", "operation_logs", "character_list_presets", "image_meta")) {
            assertTrue("$t 이(가) 직접 삭제 목록에 없다", t in ResetPlan.explicitOrder)
        }
    }
}
