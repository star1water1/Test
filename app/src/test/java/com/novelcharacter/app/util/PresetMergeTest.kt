package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `itemKey 구분자는 NUL이고 어느 쪽 값에도 섞이지 않는다`() {
        // B-146 — 이 구분자는 **날바이트로 적혀 있었다.** 이스케이프 표기로 바꾸면서
        // 값이 함께 바뀌면 화면이 고른 키와 resolve가 받는 키가 갈리고, 그러면
        // **사용자가 켠 항목이 아닌 것이 처리된다**(조용한 오배정). 값을 코드포인트로 잰다 —
        // 시험이 같은 이스케이프를 다시 적으면 둘이 함께 틀려도 통과하기 때문이다.
        val key = PresetMerge.itemKey("character", "gender")
        assertEquals("character".length, key.indexOf(key[9]))
        assertEquals(0, key[9].code)
        assertEquals("character", key.substring(0, 9))
        assertEquals("gender", key.substring(10))

        // 구분자가 양쪽 어디에도 들어갈 수 없는 문자라야 서로 다른 짝이 한 키로 접히지 않는다.
        // 밑줄·콜론이었다면 아래 둘이 같은 키가 되어 **다른 필드가 같은 항목으로 보인다.**
        assertNotEquals(
            PresetMerge.itemKey("a", "b_c"),
            PresetMerge.itemKey("a_b", "c")
        )
        assertNotEquals(
            PresetMerge.itemKey(FieldDefinition.ENTITY_CHARACTER, "place"),
            PresetMerge.itemKey(FieldDefinition.ENTITY_EVENT, "place")
        )
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

    // ── 종류 바꿔 심기 (B-63 · 확정 14번) ──
    //
    // **이 갈래의 방어선은 시험 수가 아니라 그 안의 넷이다:**
    // ① *"이미 대상 종류인 필드는 허용 타입을 묻지 않는다"* — 무너지면 **오늘 되던
    //    가져오기가 안 된다.** 이 항목은 길을 넓히는 것이지 좁히는 것이 아닌데, 필터를
    //    전부에 걸면 GRADE 사건 필드를 사건에 심던 사람이 갑자기 못 심는다.
    // ② *"막힌 것은 버리지 않는다"* — 조용히 사라지면 사용자는 자기가 고른 소스에 그 필드가
    //    있었다는 것조차 모른다(개발 의도 2번). 화면이 사유와 함께 보이려면 여기서 와야 한다.
    // ③ *"잃는 설정을 심기 전에 센다"* — 걷는 쪽(demoteAcrossEntityType)과 세는 쪽
    //    (droppedKeys)이 갈리면 미리보기가 예고한 것과 실제가 달라진다.
    // ④ *"key는 그대로 둔다"* — 유니크 인덱스가 `(universeId, entityType, key)`라 종류가
    //    달라지면 다른 자리다. 키를 손대면 캐릭터의 '장소'와 사건의 '장소'가 서로를 중복으로
    //    걸어 멀쩡한 필드를 못 넣는다(R-29).

    @Test
    fun `허용 타입이면 종류가 바뀐다`() {
        val conv = PresetMerge.convertEntityType(
            listOf(field("place")), FieldDefinition.ENTITY_EVENT
        )
        assertEquals(1, conv.fields.size)
        assertEquals(FieldDefinition.ENTITY_EVENT, conv.fields[0].entityType)
        // ④ key는 그대로다 — 종류가 다르면 애초에 다른 자리다.
        assertEquals("place", conv.fields[0].key)
        assertTrue(conv.blocked.isEmpty())
    }

    @Test
    fun `허용 타입 밖이면 막히고 버려지지 않는다`() {
        // 기본값은 글자·선택·숫자다(P-6 — 안전 우선). BODY_SIZE는 사건에 뜻이 없다.
        val conv = PresetMerge.convertEntityType(
            listOf(field("body", type = "BODY_SIZE")), FieldDefinition.ENTITY_EVENT
        )
        assertTrue(conv.fields.isEmpty())
        assertEquals(listOf("body"), conv.blocked.map { it.key })
    }

    @Test
    fun `사용자가 허용 타입을 넓히면 그 타입도 넘어간다`() {
        // 확정 14번의 본체 — 허용 타입은 고정이 아니라 사용자가 정한다.
        val conv = PresetMerge.convertEntityType(
            listOf(field("body", type = "BODY_SIZE")),
            FieldDefinition.ENTITY_EVENT,
            allowedTypes = PresetMerge.DEFAULT_CONVERTIBLE_TYPES + "BODY_SIZE"
        )
        assertEquals(listOf("body"), conv.fields.map { it.key })
        assertTrue(conv.blocked.isEmpty())
    }

    @Test
    fun `이미 대상 종류인 필드는 허용 타입을 묻지 않는다`() {
        // ① 오늘의 경로다 — 사건 필드를 사건에 심는 것은 변환이 아니다.
        // 여기에 필터를 걸면 오늘 되던 가져오기가 안 되는 자리가 생긴다.
        val conv = PresetMerge.convertEntityType(
            listOf(field("rank", type = "GRADE", entityType = FieldDefinition.ENTITY_EVENT)),
            FieldDefinition.ENTITY_EVENT
        )
        assertEquals(listOf("rank"), conv.fields.map { it.key })
        assertTrue(conv.blocked.isEmpty())
    }

    @Test
    fun `종류를 넘으면 뜻을 잃는 설정이 빠지고 그것을 먼저 센다`() {
        // ③ 세는 쪽과 걷는 쪽이 한 함수라 미리보기와 실제가 갈리지 않는다.
        val conv = PresetMerge.convertEntityType(
            listOf(field("age", type = "NUMBER", config = """{"semanticRole":"age","label":"나이"}""")),
            FieldDefinition.ENTITY_EVENT
        )
        val key = PresetMerge.itemKey(FieldDefinition.ENTITY_EVENT, "age")
        assertEquals(listOf("semanticRole"), conv.configLoss[key])
        assertFalse(conv.fields[0].config.contains("semanticRole"))
        // 나머지 설정은 그대로다 — 걷는 것은 종류를 넘지 못하는 키뿐이다.
        assertTrue(conv.fields[0].config.contains("label"))
    }

    @Test
    fun `잃을 것이 없으면 고지도 없다`() {
        val conv = PresetMerge.convertEntityType(
            listOf(field("place", config = """{"label":"장소"}""")), FieldDefinition.ENTITY_EVENT
        )
        assertTrue(conv.configLoss.isEmpty())
        assertTrue(conv.isNoop)
    }

    @Test
    fun `변환한 목록이 그대로 계획으로 이어진다`() {
        // 소스·중복·순서·삽입이 같은 종류를 봐야 한다(R-29) — 변환이 계획 앞에서
        // 끝나므로 아래는 손댈 것이 없다. 대상 세계관의 사건 '장소'와 중복이 잡힌다.
        val conv = PresetMerge.convertEntityType(
            listOf(field("place")), FieldDefinition.ENTITY_EVENT
        )
        val existing = listOf(
            field("place", id = 5, universeId = 9, entityType = FieldDefinition.ENTITY_EVENT),
            field("place", id = 6, universeId = 9)   // 캐릭터 '장소' — 다른 자리다
        )
        val plan = PresetMerge.buildPlan(conv.fields, existing)
        assertEquals(1, plan.items.size)
        assertTrue(plan.items[0].isDuplicate)
        assertEquals(5L, plan.items[0].existing?.id)
    }

    @Test
    fun `종류가 그대로면 원문 config를 손대지 않는다`() {
        val config = """{"semanticRole":"age","bodyAnalysis":{"ribOffset":2}}"""
        val conv = PresetMerge.convertEntityType(
            listOf(field("age", config = config)), FieldDefinition.ENTITY_CHARACTER
        )
        assertEquals(config, conv.fields[0].config)
        assertTrue(conv.configLoss.isEmpty())
    }

    @Test
    fun `같은 자리가 겹치면 손실 고지도 먼저 온 것을 따른다`() {
        // buildPlan은 같은 (entityType, key)를 **먼저 온 것으로** 접는다. 손실 고지가
        // 그 규칙과 다르면 **버려진 필드의 손실이 남은 필드의 줄에 붙어**, 잃는 것이 없는
        // 항목에 "설정이 빠집니다"가 뜬다 — 미리보기가 사용자에게 거짓을 말하는 자리다.
        val sources = listOf(
            // 먼저 온 것: 이미 사건 필드라 변환도 손실도 없다.
            field("place", entityType = FieldDefinition.ENTITY_EVENT, config = """{"label":"장소"}"""),
            // 나중 온 것: 캐릭터 필드라 변환되고 semanticRole을 잃는다 — 그런데 접혀서 버려진다.
            field("place", config = """{"semanticRole":"age"}""")
        )
        val conv = PresetMerge.convertEntityType(sources, FieldDefinition.ENTITY_EVENT)
        val plan = PresetMerge.buildPlan(conv.fields, emptyList())

        assertEquals(1, plan.items.size)
        // 남은 것은 손실이 없는 쪽이므로 그 줄에 붙을 고지도 없어야 한다.
        assertTrue(conv.configLoss[plan.items[0].itemKey].isNullOrEmpty())
    }
}
