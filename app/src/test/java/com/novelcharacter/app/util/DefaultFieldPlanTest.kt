package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DefaultFieldRef
import com.novelcharacter.app.data.model.DefaultFieldTemplate
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 전역 기본 필드의 심기·전파·강등 계획 (B-119) — 설계 정본 `feature_roadmap_2026-08` 1장.
 *
 * **이 시험이 지키는 가장 큰 약속 하나: 이 기능은 캐릭터 데이터를 지우지 않는다.**
 * 심기는 기존 필드를 덮지 않고, 강등은 표식만 걷어내며, 덮어쓰기는 사용자가 고른 전파에서만
 * 일어나고 그때도 백업이 함께 난다.
 */
class DefaultFieldPlanTest {

    private fun template(
        key: String = "gender",
        name: String = "성별",
        type: String = FieldType.SELECT.name,
        config: String = """{"options":["남","여"]}""",
        group: String = "기본 정보",
        required: Boolean = false,
        entityType: String = FieldDefinition.ENTITY_CHARACTER,
        code: String = "DFT-1"
    ) = DefaultFieldTemplate(
        key = key, name = name, type = type, config = config, groupName = group,
        displayOrder = 0, isRequired = required, entityType = entityType,
        createdAt = 100L, code = code
    )

    private fun field(
        id: Long = 1,
        universeId: Long = 1,
        key: String = "gender",
        name: String = "성별",
        type: String = FieldType.SELECT.name,
        config: String = """{"options":["남","여"]}""",
        group: String = "기본 정보",
        order: Int = 0,
        required: Boolean = false,
        entityType: String = FieldDefinition.ENTITY_CHARACTER
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type,
        config = config, groupName = group, displayOrder = order, isRequired = required,
        entityType = entityType
    )

    private fun target(id: Long?, name: String, vararg fields: FieldDefinition) =
        DefaultFieldPlan.Target(id, name, fields.toList())

    // ──────────────────────────────────────────────────────────────────────
    // 구체화
    // ──────────────────────────────────────────────────────────────────────

    @Test fun 구체화는_보통_필드를_만들고_표식을_단다() {
        val f = DefaultFieldPlan.materialize(template(), universeId = 7, displayOrder = 3)
        assertEquals(0L, f.id)
        assertEquals(7L, f.universeId)
        assertEquals("gender", f.key)
        assertEquals("성별", f.name)
        assertEquals(FieldType.SELECT.name, f.type)
        assertEquals(3, f.displayOrder)
        assertEquals(FieldDefinition.ENTITY_CHARACTER, f.entityType)
        assertEquals("DFT-1", DefaultFieldRef.codeFromConfig(f.config))
        assertEquals(2, JSONObject(f.config).getJSONArray("options").length())
    }

    /**
     * 템플릿은 전역이라 세계관 한정 참조를 가질 수 없다(설계 1-2). 손으로 고친 엑셀이 그것을
     * 실어 오면 **모든 세계관에 유령 참조가 하나씩 생긴다** — 심는 자리가 겹방어를 건다.
     */
    @Test fun 구체화는_세계관_한정_참조를_걷어낸다() {
        val t = template(config = """{"options":["남"],"gradeSystem":"GS-1","duelGrade":{"axisCode":"DAX-1"}}""")
        val json = JSONObject(DefaultFieldPlan.materialize(t, 1, 0).config)
        assertFalse("등급 체계 참조는 전역 템플릿에서 심겨선 안 된다", json.has("gradeSystem"))
        assertFalse("대결 축 참조는 남의 세계관 축을 정확히 찾는다 — 더 나쁘다", json.has("duelGrade"))
        assertEquals("DFT-1", json.getString("defaultField"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // 심기
    // ──────────────────────────────────────────────────────────────────────

    @Test fun 빈_자리에는_심고_찬_자리에는_연결만_한다() {
        val occupied = field(id = 5, universeId = 2, name = "성별(고침)")
        val plan = DefaultFieldPlan.planPlant(
            template(),
            listOf(
                target(1, "아르카나"),
                target(2, "제2세계", occupied)
            )
        )
        assertEquals(1, plan.plants.size)
        assertEquals(1L, plan.plants[0].universeId)
        assertEquals(1, plan.links.size)
        assertEquals(2L, plan.links[0].universeId)

        val writes = DefaultFieldPlan.resolvePlant(template(), plan)
        assertEquals(1, writes.inserts.size)
        assertEquals(1L, writes.inserts[0].universeId)
        assertEquals(1, writes.updates.size)
        // **연결은 정의를 건드리지 않는다** — 그 세계관의 설정을 존중한다(자율성 우선).
        assertEquals("성별(고침)", writes.updates[0].name)
        assertEquals(5L, writes.updates[0].id)
        assertEquals("DFT-1", DefaultFieldRef.codeFromConfig(writes.updates[0].config))
    }

    // ── 전역 구역 (무소속 — B-119 확장, 2026.08.07 사용자 확정) ──

    /**
     * **null 대상이 곧 무소속 구역이다.** 이 시험이 무너지면 "전역" 필드가 정작 소속 없는
     * 캐릭터에게만 없는 역설로 되돌아간다 — 그것이 이 확장이 고친 것의 전부다.
     */
    @Test fun 전역_구역에도_심는다() {
        val plan = DefaultFieldPlan.planPlant(
            template(),
            listOf(target(1, "아르카나"), target(null, "무소속"))
        )
        assertEquals(2, plan.plants.size)
        val writes = DefaultFieldPlan.resolvePlant(template(), plan)
        assertEquals(2, writes.inserts.size)
        val globalRow = writes.inserts.single { it.universeId == null }
        assertEquals("gender", globalRow.key)
        assertEquals("DFT-1", DefaultFieldRef.codeFromConfig(globalRow.config))
    }

    /**
     * 전역 구역의 key 유일성은 DB 유니크가 지키지 못한다(SQLite는 NULL끼리를 다른 값으로
     * 본다 — 마이그레이션 54 하네스가 실증한다). **이 계획 로직이 유일한 방어선이다** —
     * 같은 key가 이미 있으면 심지 않고 연결만 하는 판정이 null 구역에서도 서는지 잰다.
     */
    @Test fun 전역_구역도_찬_자리에는_연결만_한다() {
        val occupied = field(id = 9, universeId = 1, name = "성별(전역)").copy(universeId = null)
        val plan = DefaultFieldPlan.planPlant(template(), listOf(target(null, "무소속", occupied)))
        assertEquals(0, plan.plants.size)
        assertEquals(1, plan.links.size)
        val writes = DefaultFieldPlan.resolvePlant(template(), plan)
        assertEquals(0, writes.inserts.size)
        assertEquals(9L, writes.updates.single().id)
    }

    @Test fun 이미_연결된_세계관은_할_일이_없다() {
        val linked = field(
            id = 5, universeId = 2,
            config = DefaultFieldRef.write("""{"options":["남","여"]}""", "DFT-1")
        )
        val plan = DefaultFieldPlan.planPlant(template(), listOf(target(2, "제2세계", linked)))
        assertEquals(1, plan.already.size)
        assertTrue(plan.isNoop)
        assertTrue(DefaultFieldPlan.resolvePlant(template(), plan).isEmpty)
    }

    /**
     * 자리 판정은 `(entityType, key)`다 — key만 보면 캐릭터의 `place`와 사건의 `place`가
     * 서로를 중복으로 걸어 멀쩡한 필드를 못 넣는다(R-29).
     */
    @Test fun 자리_판정은_종류까지_본다() {
        val eventField = field(id = 9, universeId = 1, entityType = FieldDefinition.ENTITY_EVENT)
        val plan = DefaultFieldPlan.planPlant(template(), listOf(target(1, "아르카나", eventField)))
        assertEquals("사건 필드가 캐릭터 자리를 막아선 안 된다", 1, plan.plants.size)
    }

    /** 순서는 **같은 종류의** 최댓값 뒤다 — 두 종류의 순서는 서로 독립이다. */
    @Test fun 순서는_같은_종류의_최댓값_뒤에_붙는다() {
        val plan = DefaultFieldPlan.planPlant(
            template(),
            listOf(
                target(
                    1, "아르카나",
                    field(id = 1, key = "a", order = 4),
                    field(id = 2, key = "b", order = 7),
                    // 사건 필드의 큰 순서가 캐릭터 순서를 밀어선 안 된다.
                    field(id = 3, key = "c", order = 99, entityType = FieldDefinition.ENTITY_EVENT)
                )
            )
        )
        assertEquals(8, plan.plants[0].nextOrder)
        assertEquals(8, DefaultFieldPlan.resolvePlant(template(), plan).inserts[0].displayOrder)
    }

    @Test fun 첫_필드는_순서_0에서_시작한다() {
        val plan = DefaultFieldPlan.planPlant(template(), listOf(target(1, "아르카나")))
        assertEquals(0, plan.plants[0].nextOrder)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 전파
    // ──────────────────────────────────────────────────────────────────────

    private fun linkedField(universeId: Long, t: DefaultFieldTemplate, id: Long = universeId) =
        field(id = id, universeId = universeId, name = t.name, type = t.type,
              config = DefaultFieldPlan.linkedConfig(t), group = t.groupName, required = t.isRequired)

    @Test fun 템플릿과_같은_필드는_같음이다() {
        val t = template()
        val plan = DefaultFieldPlan.planPropagate(
            t, previous = null,
            linked = listOf(DefaultFieldPlan.LinkedField(1, "아르카나", linkedField(1, t)))
        )
        assertEquals(1, plan.same.size)
        assertTrue(plan.isNoop)
    }

    /**
     * **이 판의 요점.** 템플릿을 고친 직후에는 모든 세계관이 *"템플릿과 다르다"* — 아직 못
     * 받았으니 당연하다. 직전 템플릿을 견주면 *못 받은 것*(OUTDATED)과 *손댄 것*(DIVERGED)이
     * 정확히 갈리고, 기본 선택은 앞엣것만 켠다.
     *
     * 가르지 않으면 흔한 조작(*"고쳤으니 전부 밀어라"*)이 세계관 수만큼의 체크를 요구하고,
     * 사용자는 곧 '모두 선택'만 누르게 되어 **손대어 둔 세계관까지 함께 덮는다.**
     */
    @Test fun 못_받은_것과_손댄_것을_가른다() {
        val old = template(name = "성별")
        val new = template(name = "성별/젠더")
        val untouched = linkedField(1, old)                       // 옛 정의 그대로
        val edited = linkedField(2, old).copy(name = "내 성별")     // 그 세계관에서 고쳤다

        val plan = DefaultFieldPlan.planPropagate(
            new, previous = old,
            linked = listOf(
                DefaultFieldPlan.LinkedField(1, "아르카나", untouched),
                DefaultFieldPlan.LinkedField(2, "제2세계", edited)
            )
        )
        assertEquals(listOf(1L), plan.outdated.map { it.universeId })
        assertEquals(listOf(2L), plan.diverged.map { it.universeId })
        assertEquals(setOf(1L), plan.defaultSelection())
    }

    /**
     * 직전 템플릿을 알 수 없는 자리(관리 화면의 '전파' 단추)에서는 **아무것도 켜지 않는다** —
     * 모르는 것을 안다고 하지 않는 쪽이 안전한 기본이다.
     */
    @Test fun 직전_템플릿을_모르면_기본_선택이_비어_있다() {
        val old = template(name = "성별")
        val new = template(name = "성별/젠더")
        val plan = DefaultFieldPlan.planPropagate(
            new, previous = null,
            linked = listOf(DefaultFieldPlan.LinkedField(1, "아르카나", linkedField(1, old)))
        )
        assertEquals(listOf(1L), plan.diverged.map { it.universeId })
        assertTrue(plan.defaultSelection().isEmpty())
    }

    /**
     * 필드 쪽에는 표식이 있고 템플릿 쪽에는 없다. 날것으로 비교하면 **손대지 않은 필드까지
     * 전부 "설정이 다름"**이 되어 미리보기가 통째로 거짓말을 한다.
     */
    @Test fun 표식_때문에_설정이_다르다고_말하지_않는다() {
        val t = template()
        val plan = DefaultFieldPlan.planPropagate(
            t, previous = null,
            linked = listOf(DefaultFieldPlan.LinkedField(1, "아르카나", linkedField(1, t)))
        )
        assertFalse(PresetMerge.Change.CONFIG in plan.items[0].changes)
    }

    /** 키 순서만 다른 config는 뜻이 같다 — [PresetMerge.configEquals]가 그 판정의 단일 소스다. */
    @Test fun 키_순서만_다른_설정은_같다고_본다() {
        val t = template(config = """{"a":1,"b":2}""")
        val shuffled = linkedField(1, t).copy(config = """{"defaultField":"DFT-1","b":2,"a":1}""")
        val plan = DefaultFieldPlan.planPropagate(
            t, previous = null,
            linked = listOf(DefaultFieldPlan.LinkedField(1, "아르카나", shuffled))
        )
        assertEquals(DefaultFieldPlan.Divergence.SAME, plan.items[0].divergence)
    }

    @Test fun 전파는_고른_곳만_덮고_백업을_남긴다() {
        val old = template(name = "성별")
        val new = template(name = "성별/젠더")
        val plan = DefaultFieldPlan.planPropagate(
            new, previous = old,
            linked = listOf(
                DefaultFieldPlan.LinkedField(1, "아르카나", linkedField(1, old)),
                DefaultFieldPlan.LinkedField(2, "제2세계", linkedField(2, old))
            )
        )
        val writes = DefaultFieldPlan.resolvePropagate(new, plan, selected = setOf(1L))
        assertEquals(1, writes.updates.size)
        assertEquals("성별/젠더", writes.updates[0].name)
        assertEquals(1, writes.backups.size)
        assertEquals("덮이기 직전의 정의가 백업이다", "성별", writes.backups[0].name)
        assertEquals("고르지 않은 세계관은 손대지 않는다", 1L, writes.updates[0].universeId)
    }

    /**
     * 순서와 key는 전파가 건드리지 않는다 — 순서는 그 세계관에서 사용자가 끌어다 정한 것이고,
     * key를 바꾸면 저장된 값이 옛 정의를 가리킨 채 고아가 된다.
     */
    @Test fun 전파는_순서와_key와_id를_건드리지_않는다() {
        val old = template(name = "성별")
        val new = template(name = "성별/젠더")
        val existing = linkedField(1, old, id = 42).copy(displayOrder = 9)
        val plan = DefaultFieldPlan.planPropagate(
            new, previous = old, linked = listOf(DefaultFieldPlan.LinkedField(1, "아르카나", existing))
        )
        val updated = DefaultFieldPlan.resolvePropagate(new, plan, setOf(1L)).updates[0]
        assertEquals(9, updated.displayOrder)
        assertEquals("gender", updated.key)
        assertEquals(42L, updated.id)
        assertEquals(1L, updated.universeId)
    }

    @Test fun 같은_것은_골라도_쓰지_않는다() {
        val t = template()
        val plan = DefaultFieldPlan.planPropagate(
            t, previous = null, linked = listOf(DefaultFieldPlan.LinkedField(1, "아르카나", linkedField(1, t)))
        )
        assertTrue(DefaultFieldPlan.resolvePropagate(t, plan, setOf(1L)).isEmpty)
    }

    /**
     * 타입이 바뀌는 전파는 세계관마다 *"값 몇 개가 못 버티는가"*를 함께 보인다(설계 1-3).
     * 판정은 [FieldTypeCompatibility]가 단일 소스라 저장 경로와 갈리지 않는다.
     */
    @Test fun 타입이_바뀌면_못_버티는_값을_센다() {
        val old = template(type = FieldType.TEXT.name, config = "{}")
        val new = template(type = FieldType.NUMBER.name, config = "{}")
        val plan = DefaultFieldPlan.planPropagate(
            new, previous = old,
            linked = listOf(
                DefaultFieldPlan.LinkedField(
                    1, "아르카나", linkedField(1, old),
                    values = listOf("12", "서른", "", "7", "많음")
                )
            )
        )
        assertTrue(plan.items[0].typeChanges)
        assertEquals("빈 값은 세지 않는다", 2, plan.items[0].incompatibleValues)
    }

    @Test fun 타입이_그대로면_값을_세지_않는다() {
        val old = template(name = "성별")
        val new = template(name = "성별/젠더")
        val plan = DefaultFieldPlan.planPropagate(
            new, previous = old,
            linked = listOf(DefaultFieldPlan.LinkedField(
                1, "아르카나", linkedField(1, old), values = listOf("남", "여")
            ))
        )
        assertFalse(plan.items[0].typeChanges)
        assertEquals(0, plan.items[0].incompatibleValues)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 강등 · 승격
    // ──────────────────────────────────────────────────────────────────────

    @Test fun 강등은_표식만_걷어낸다() {
        val t = template()
        val linked = linkedField(1, t)
        val demoted = DefaultFieldPlan.demote(listOf(linked))
        assertEquals(1, demoted.size)
        assertNull(DefaultFieldRef.codeFromConfig(demoted[0].config))
        assertEquals("정의는 그대로다", "성별", demoted[0].name)
        assertEquals("값이 매달린 id도 그대로다", linked.id, demoted[0].id)
        assertEquals(2, JSONObject(demoted[0].config).getJSONArray("options").length())
    }

    /** 표식이 없던 행은 결과가 입력과 같다 — 아무것도 안 바뀌는 UPDATE를 세면 고지가 거짓이 된다. */
    @Test fun 강등할_것이_없는_행은_쓰기에_들어가지_않는다() {
        assertTrue(DefaultFieldPlan.demote(listOf(field())).isEmpty())
    }

    @Test fun 승격은_세계관_한정_참조와_옛_표식을_함께_걷어낸다() {
        val f = field(
            config = """{"options":["남"],"gradeSystem":"GS-1","duelGrade":{"axisCode":"DAX-1"},"defaultField":"DFT-OLD"}"""
        )
        val t = DefaultFieldPlan.promote(f, displayOrder = 2, code = "DFT-NEW", createdAt = 500L)
        val json = JSONObject(t.config)
        assertFalse(json.has("gradeSystem"))
        assertFalse(json.has("duelGrade"))
        assertFalse("템플릿이 템플릿을 가리킬 수는 없다", json.has("defaultField"))
        assertEquals(1, json.getJSONArray("options").length())
        assertEquals("gender", t.key)
        assertEquals(2, t.displayOrder)
        assertEquals("DFT-NEW", t.code)
        assertEquals(500L, t.createdAt)
    }

    /** 승격 → 구체화 왕복이 정의를 보존한다(세계관 한정 참조를 빼면). */
    @Test fun 승격하고_다시_심으면_같은_정의가_돌아온다() {
        val f = field(name = "출신", key = "origin", group = "배경", required = true)
        val t = DefaultFieldPlan.promote(f, 0, "DFT-2", 1L)
        val planted = DefaultFieldPlan.materialize(t, universeId = 9, displayOrder = 0)
        assertEquals(f.name, planted.name)
        assertEquals(f.key, planted.key)
        assertEquals(f.type, planted.type)
        assertEquals(f.groupName, planted.groupName)
        assertEquals(f.isRequired, planted.isRequired)
        assertEquals("DFT-2", DefaultFieldRef.codeFromConfig(planted.config))
    }
}
