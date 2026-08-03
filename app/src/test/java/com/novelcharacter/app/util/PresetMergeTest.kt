package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프리셋 병합 계획 (B-89).
 *
 * 이 계층이 지키는 계약은 넷이다: **무엇이 새 것인가**(종류를 포함한 자리로 판정) ·
 * **덮어쓰면 무엇이 바뀌는가**(바뀌는 것이 없으면 선택지도 없다) · **순서를 어디에 잇는가**
 * (종류별 최댓값 뒤) · **되돌릴 것만 백업하는가**(덮어쓰기를 고른 항목에만).
 */
class PresetMergeTest {

    private fun field(
        key: String,
        name: String = key,
        type: String = "TEXT",
        entityType: String = FieldDefinition.ENTITY_CHARACTER,
        universeId: Long = 0,
        id: Long = 0,
        config: String = "{}",
        groupName: String = "기본 정보",
        displayOrder: Int = 0,
        isRequired: Boolean = false
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type,
        config = config, groupName = groupName, displayOrder = displayOrder,
        isRequired = isRequired, entityType = entityType
    )

    // ── 판정 ──

    @Test
    fun `대상에 없는 필드는 새 것으로 잡힌다`() {
        val plan = PresetMerge.buildPlan(listOf(field("age")), emptyList())
        assertEquals(1, plan.additions.size)
        assertTrue(plan.duplicates.isEmpty())
        assertFalse(plan.items[0].isDuplicate)
    }

    @Test
    fun `같은 key라도 종류가 다르면 중복이 아니다`() {
        // key 유일성 제약이 (universeId, entityType, key)이므로 공존할 수 있다 — 종류를 빼고
        // 판정하면 멀쩡한 사건 필드를 캐릭터 필드가 중복으로 막는다(R-29).
        val existing = listOf(field("place", universeId = 1, id = 7))
        val plan = PresetMerge.buildPlan(
            listOf(field("place", entityType = FieldDefinition.ENTITY_EVENT)), existing
        )
        assertEquals(1, plan.additions.size)
        assertTrue(plan.duplicates.isEmpty())
    }

    @Test
    fun `같은 자리는 중복으로 잡히고 바뀌는 것이 열거된다`() {
        val existing = listOf(field("age", name = "나이", type = "TEXT", universeId = 1, id = 7))
        val plan = PresetMerge.buildPlan(
            listOf(field("age", name = "연령", type = "NUMBER", groupName = "신상")), existing
        )
        val item = plan.items.single()
        assertTrue(item.isDuplicate)
        assertFalse(item.isIdentical)
        assertEquals(
            setOf(PresetMerge.Change.NAME, PresetMerge.Change.TYPE, PresetMerge.Change.GROUP),
            item.changes
        )
    }

    @Test
    fun `내용이 같으면 덮어쓸 것이 없다`() {
        val existing = listOf(field("age", universeId = 1, id = 7, displayOrder = 3))
        val plan = PresetMerge.buildPlan(listOf(field("age")), existing)
        val item = plan.items.single()
        assertTrue(item.isDuplicate)
        assertTrue(item.isIdentical)
        // displayOrder·id가 달라도 '같은 정의'다 — 덮어쓰기가 그 둘을 건드리지 않기 때문이다.
        assertTrue(plan.overwritable.isEmpty())
    }

    @Test
    fun `등급 체계 참조만 다른 것은 바뀐 것으로 보지 않는다`() {
        // 참조는 심을 때 어차피 벗겨진다(U-1) — 벗기기 전으로 비교하면 덮어써 봐야 아무것도
        // 안 바뀌는 항목을 "설정이 다릅니다"로 권하게 된다.
        val grades = """{"grades":{"C":1,"B":2}}"""
        val withRef = """{"grades":{"C":1,"B":2},"gradeSystem":"GS-1"}"""
        val existing = listOf(field("rank", type = "GRADE", config = grades, universeId = 1, id = 7))
        val plan = PresetMerge.buildPlan(
            listOf(field("rank", type = "GRADE", config = withRef, universeId = 2)), existing
        )
        assertTrue(plan.items.single().isIdentical)
    }

    @Test
    fun `키 순서만 다른 설정은 바뀐 것이 아니다`() {
        // JSONObject는 키 순서를 보존하지 않는다 — 문자열로 견주면 재직렬화 한 번에
        // 같은 설정이 "바뀐 것"이 된다.
        val existing = listOf(
            field("age", config = """{"a":1,"b":2}""", universeId = 1, id = 7)
        )
        val plan = PresetMerge.buildPlan(listOf(field("age", config = """{"b":2,"a":1}""")), existing)
        assertTrue(plan.items.single().isIdentical)
    }

    @Test
    fun `선택지 순서가 다르면 바뀐 것이다`() {
        // 배열은 순서가 곧 뜻인 값이 있다(SELECT의 options) — 정렬해 견주면 실제 변경을 놓친다.
        val existing = listOf(
            field("gender", type = "SELECT", config = """{"options":["남","여"]}""",
                universeId = 1, id = 7)
        )
        val plan = PresetMerge.buildPlan(
            listOf(field("gender", type = "SELECT", config = """{"options":["여","남"]}""")), existing
        )
        assertEquals(setOf(PresetMerge.Change.CONFIG), plan.items.single().changes)
    }

    @Test
    fun `깨진 설정 문자열도 비교가 죽지 않는다`() {
        val existing = listOf(field("age", config = "{not json", universeId = 1, id = 7))
        val plan = PresetMerge.buildPlan(listOf(field("age", config = "{not json")), existing)
        // 파싱할 수 없으면 문자열로 내려간다 — 같은 원문이면 같은 것이다.
        assertTrue(plan.items.single().isIdentical)
    }

    @Test
    fun `소스 안의 같은 자리 중복은 하나로 접힌다`() {
        // 손편집 JSON·엑셀로 들어온 프리셋에는 실재한다. 접지 않으면 미리보기는 둘을
        // 보여 주는데 삽입은 하나만 성공한다(유니크 제약).
        val plan = PresetMerge.buildPlan(
            listOf(field("age", name = "나이"), field("age", name = "연령")), emptyList()
        )
        assertEquals(1, plan.items.size)
        assertEquals("나이", plan.items.single().source.name)
    }

    @Test
    fun `기본 선택은 새 필드뿐이다`() {
        val existing = listOf(field("age", name = "나이", universeId = 1, id = 7))
        val plan = PresetMerge.buildPlan(
            listOf(field("age", name = "연령"), field("gender")), existing
        )
        // 값 유실이 0인 쪽이 기본이다 — 이미 있는 필드는 켜지지 않는다.
        assertEquals(setOf(PresetMerge.itemKey(FieldDefinition.ENTITY_CHARACTER, "gender")),
            plan.defaultSelection())
    }

    @Test
    fun `종류별로 묶으면 목록 순서가 보존된다`() {
        val plan = PresetMerge.buildPlan(
            listOf(
                field("age"),
                field("place", entityType = FieldDefinition.ENTITY_EVENT),
                field("gender")
            ),
            emptyList()
        )
        val grouped = plan.byEntityType()
        assertEquals(
            listOf(FieldDefinition.ENTITY_CHARACTER, FieldDefinition.ENTITY_EVENT),
            grouped.keys.toList()
        )
        assertEquals(listOf("age", "gender"), grouped[FieldDefinition.ENTITY_CHARACTER]!!.map { it.key })
    }

    // ── 해소 ──

    @Test
    fun `삽입 순서는 종류별 최댓값 뒤에 이어 붙는다`() {
        val plan = PresetMerge.buildPlan(
            listOf(
                field("age"),
                field("gender"),
                field("place", entityType = FieldDefinition.ENTITY_EVENT)
            ),
            emptyList()
        )
        val resolution = PresetMerge.resolve(
            plan, plan.defaultSelection(), targetUniverseId = 9,
            maxOrderByEntityType = mapOf(
                FieldDefinition.ENTITY_CHARACTER to 4,
                FieldDefinition.ENTITY_EVENT to 1
            )
        )
        assertEquals(listOf(5, 6, 2), resolution.inserts.map { it.displayOrder })
        assertTrue(resolution.inserts.all { it.universeId == 9L && it.id == 0L })
    }

    @Test
    fun `순서를 모르는 종류는 0에서 시작한다`() {
        val plan = PresetMerge.buildPlan(listOf(field("age")), emptyList())
        val resolution = PresetMerge.resolve(plan, plan.defaultSelection(), 9, emptyMap())
        assertEquals(0, resolution.inserts.single().displayOrder)
    }

    @Test
    fun `고르지 않은 중복은 손대지 않는다`() {
        val existing = listOf(field("age", name = "나이", universeId = 9, id = 7))
        val plan = PresetMerge.buildPlan(listOf(field("age", name = "연령")), existing)
        val resolution = PresetMerge.resolve(plan, plan.defaultSelection(), 9, emptyMap())
        assertTrue(resolution.isEmpty)
        assertTrue(resolution.backups.isEmpty())
    }

    @Test
    fun `덮어쓰기는 정의만 바꾸고 자리는 유지한다`() {
        val existing = listOf(
            field("age", name = "나이", type = "TEXT", universeId = 9, id = 7, displayOrder = 3)
        )
        val plan = PresetMerge.buildPlan(
            listOf(field("age", name = "연령", type = "NUMBER", groupName = "신상", isRequired = true)),
            existing
        )
        val resolution = PresetMerge.resolve(plan, setOf(plan.items.single().itemKey), 9, emptyMap())
        val updated = resolution.updates.single()
        assertEquals(7L, updated.id)
        assertEquals(9L, updated.universeId)
        assertEquals("age", updated.key)
        assertEquals(3, updated.displayOrder)
        assertEquals("연령", updated.name)
        assertEquals("NUMBER", updated.type)
        assertEquals("신상", updated.groupName)
        assertTrue(updated.isRequired)
    }

    @Test
    fun `백업은 덮어쓰기를 고른 항목에만 남는다`() {
        val existing = listOf(
            field("age", name = "나이", universeId = 9, id = 7),
            field("gender", name = "성별", universeId = 9, id = 8)
        )
        val plan = PresetMerge.buildPlan(
            listOf(
                field("age", name = "연령"),
                field("gender", name = "성별"),   // 같은 정의 — 고르더라도 백업 대상이 아니다
                field("race")                     // 새 것 — 되돌릴 것이 없다
            ),
            existing
        )
        val resolution = PresetMerge.resolve(plan, plan.items.map { it.itemKey }.toSet(), 9, emptyMap())
        assertEquals(listOf("age"), resolution.backups.map { it.key })
        assertEquals(listOf("나이"), resolution.backups.map { it.name })
        assertEquals(listOf("race"), resolution.inserts.map { it.key })
        assertEquals(1, resolution.updates.size)
    }

    @Test
    fun `백업은 덮이기 직전 값이고 갱신본과 순서가 같다`() {
        val existing = listOf(
            field("age", name = "나이", universeId = 9, id = 7),
            field("height", name = "키", universeId = 9, id = 8)
        )
        val plan = PresetMerge.buildPlan(
            listOf(field("age", name = "연령"), field("height", name = "신장")), existing
        )
        val resolution = PresetMerge.resolve(plan, plan.items.map { it.itemKey }.toSet(), 9, emptyMap())
        assertEquals(resolution.updates.map { it.id }, resolution.backups.map { it.id })
        assertEquals(listOf("나이", "키"), resolution.backups.map { it.name })
        assertEquals(listOf("연령", "신장"), resolution.updates.map { it.name })
    }

    @Test
    fun `다른 세계관에서 온 필드는 등급 체계 참조가 벗겨진 채 심긴다`() {
        val withRef = """{"grades":{"C":1},"gradeSystem":"GS-1"}"""
        val plan = PresetMerge.buildPlan(
            listOf(field("rank", type = "GRADE", config = withRef, universeId = 2)), emptyList()
        )
        val resolution = PresetMerge.resolve(plan, plan.defaultSelection(), 9, emptyMap())
        val config = resolution.inserts.single().config
        assertFalse(config.contains("gradeSystem"))
        // 실효 표는 남는다 — 벗기는 것은 참조뿐이고 등급 표·값·통계는 그대로다.
        assertTrue(config.contains("grades"))
    }

    @Test
    fun `같은 세계관 안의 복사는 config를 건드리지 않는다`() {
        // 자기 세계관 체계 참조는 유령이 아니다 — 벗기면 멀쩡한 연결을 끊는다.
        val withRef = """{"grades":{"C":1},"gradeSystem":"GS-1"}"""
        val plan = PresetMerge.buildPlan(
            listOf(field("rank", type = "GRADE", config = withRef, universeId = 9)), emptyList()
        )
        val resolution = PresetMerge.resolve(plan, plan.defaultSelection(), 9, emptyMap())
        assertEquals(withRef, resolution.inserts.single().config)
    }

    @Test
    fun `종류별 집계는 결과 고지가 쓰는 그대로다`() {
        val plan = PresetMerge.buildPlan(
            listOf(
                field("age"),
                field("gender"),
                field("place", entityType = FieldDefinition.ENTITY_EVENT)
            ),
            emptyList()
        )
        val resolution = PresetMerge.resolve(plan, plan.defaultSelection(), 9, emptyMap())
        assertEquals(
            mapOf(FieldDefinition.ENTITY_CHARACTER to 2, FieldDefinition.ENTITY_EVENT to 1),
            resolution.insertsByEntityType()
        )
        assertTrue(resolution.updatesByEntityType().isEmpty())
    }
}
