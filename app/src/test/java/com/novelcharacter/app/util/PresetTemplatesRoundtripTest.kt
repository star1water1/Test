package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.UserPresetTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프리셋 직렬화의 필드 종류 왕복 회귀 테스트 (P5).
 *
 * 종전에는 직렬화 형식에 `entityType`이 없어서 세계관을 프리셋으로 저장하면 사건 필드가
 * 아무 고지 없이 사라졌다. 이 테스트가 지키는 것은 셋이다:
 *
 * 1. 사건 필드가 왕복에서 종류를 잃지 않는다 (유실 방지)
 * 2. `displayOrder`를 **종류별로** 센다 — 한 목록에 섞여 있어도 사건 필드의 순서가
 *    캐릭터 필드 개수만큼 밀려 시작하지 않는다
 * 3. `entityType` 키가 없는 **구버전 프리셋 JSON**을 그대로 읽는다 (R-2) — Gson이 없는 키에
 *    null을 주입하므로 nullable로 받아 캐릭터로 떨어뜨린다
 */
class PresetTemplatesRoundtripTest {

    private fun field(
        key: String,
        name: String,
        order: Int,
        entityType: String = FieldDefinition.ENTITY_CHARACTER
    ) = FieldDefinition(
        universeId = 7, key = key, name = name, type = "TEXT",
        groupName = "기본 정보", displayOrder = order, entityType = entityType
    )

    private val mixed = listOf(
        field("name", "이름", 0),
        field("age", "나이", 1),
        field("chapter", "회차", 0, FieldDefinition.ENTITY_EVENT),
        field("place", "장소", 1, FieldDefinition.ENTITY_EVENT)
    )

    @Test
    fun `사건 필드는 왕복에서 종류를 잃지 않는다`() {
        val restored = PresetTemplates.fieldsFromJson(PresetTemplates.fieldsToJson(mixed))

        assertEquals(4, restored.size)
        val events = restored.filter { it.entityType == FieldDefinition.ENTITY_EVENT }
        assertEquals(listOf("chapter", "place"), events.map { it.key })
        val chars = restored.filter { it.entityType == FieldDefinition.ENTITY_CHARACTER }
        assertEquals(listOf("name", "age"), chars.map { it.key })
    }

    @Test
    fun `displayOrder는 종류별로 센다`() {
        val restored = PresetTemplates.fieldsFromJson(PresetTemplates.fieldsToJson(mixed))

        // 사건 필드가 캐릭터 2개 뒤에 있어도 0부터 시작해야 한다 (전역 색인이면 2,3이 된다)
        assertEquals(
            listOf(0, 1),
            restored.filter { it.entityType == FieldDefinition.ENTITY_EVENT }.map { it.displayOrder }
        )
        assertEquals(
            listOf(0, 1),
            restored.filter { it.entityType == FieldDefinition.ENTITY_CHARACTER }.map { it.displayOrder }
        )
    }

    @Test
    fun `entityType이 없는 구버전 프리셋은 캐릭터 필드로 읽는다`() {
        // v1 형식 — 이 키가 아예 없다. non-null로 받으면 Gson이 null을 넣어 이후 비교가 어긋난다(R-2).
        val legacy = """
            [{"key":"name","name":"이름","type":"TEXT","config":"{}","groupName":"기본 정보","isRequired":false},
             {"key":"age","name":"나이","type":"NUMBER","config":"{}","groupName":"기본 정보","isRequired":false}]
        """.trimIndent()

        val restored = PresetTemplates.fieldsFromJson(legacy)

        assertEquals(2, restored.size)
        assertTrue(restored.all { it.entityType == FieldDefinition.ENTITY_CHARACTER })
        assertEquals(listOf(0, 1), restored.map { it.displayOrder })
    }

    @Test
    fun `사용자 프리셋 복원도 같은 규칙을 쓴다`() {
        // 복원 경로가 둘(fieldsFromJson·fromUserPreset)이라 갈리면 미리보기와 실제 적용이 달라진다.
        val json = PresetTemplates.fieldsToJson(mixed)
        val preset = UserPresetTemplate(id = 3, name = "내 프리셋", fieldsJson = json)

        val viaPreset = PresetTemplates.fromUserPreset(preset).fields
        val viaJson = PresetTemplates.fieldsFromJson(json)

        assertEquals(
            viaJson.map { it.key to (it.entityType to it.displayOrder) },
            viaPreset.map { it.key to (it.entityType to it.displayOrder) }
        )
    }

    @Test
    fun `등급 체계 참조는 프리셋에 실리지 않는다 - 세계관을 넘는 유령 참조 차단`() {
        // 프리셋은 세계관을 넘나드는 템플릿이다 — 특정 세계관의 체계 code를 실으면 적용되는
        // 곳마다 남의 세계관을 가리키는 참조가 된다(U-1). 실효 표는 남아 등급 표는 온전하다.
        val gradeField = FieldDefinition(
            universeId = 5, key = "rank", name = "등급", type = "GRADE",
            config = """{"grades":{"C":0.5,"S":3.0},"gradeSystem":"gs01","gradeOverrides":{"S":5.0},"allowNegative":false}"""
        )
        val restored = PresetTemplates.fieldsFromJson(PresetTemplates.fieldsToJson(listOf(gradeField))).single()
        assertEquals(null, com.novelcharacter.app.data.model.GradeSystemRef.codeFromConfig(restored.config))
        assertEquals(
            "실효 표는 그대로 남는다",
            mapOf("C" to 0.5, "S" to 3.0),
            com.novelcharacter.app.data.model.GradeSystemRef.gradesFromConfig(restored.config)
        )
        // 참조 없는 config는 저장이 원문을 건드리지 않는다 — 키 순서까지 그대로다.
        val plain = FieldDefinition(
            universeId = 5, key = "hair", name = "머리색", type = "TEXT",
            config = """{"displayFormat":"comma","description":"머리 색"}"""
        )
        assertEquals(
            plain.config,
            PresetTemplates.fieldsFromJson(PresetTemplates.fieldsToJson(listOf(plain))).single().config
        )
    }

    @Test
    fun `깨진 JSON은 빈 목록이 되고 예외로 죽지 않는다`() {
        assertEquals(emptyList<FieldDefinition>(), PresetTemplates.fieldsFromJson("{not json"))
        assertEquals(
            emptyList<FieldDefinition>(),
            PresetTemplates.fromUserPreset(UserPresetTemplate(name = "깨진 것", fieldsJson = "{not json")).fields
        )
    }
}
