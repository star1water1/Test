package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.RequiredEnforcement
import com.novelcharacter.app.data.model.RequiredFieldMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * '필수 입력' 강도의 계약 (B-90).
 *
 * **이 테스트가 지키는 것은 "있던 동작을 조용히 바꾸지 않는다"이다.**
 * 설정이 없는 옛 필드의 기본값이 종류마다 **반대**여야 한다 — 캐릭터는 실제로 저장을 막던
 * 자리라 계속 막아야 하고(낮추면 기대하던 방어가 말없이 사라진다), 사건·작품은 한 번도 막은
 * 적이 없어 막으면 안 된다(어제까지 저장되던 사건이 오늘 막히는 것이 기각된 처방 ①이다).
 * 한 상수로 뭉뚱그리면 둘 중 하나가 반드시 깨진다.
 */
class RequiredEnforcementTest {

    private fun field(config: String = "{}", entityType: String = FieldDefinition.ENTITY_CHARACTER) =
        FieldDefinition(
            id = 1L, universeId = 1L, key = "k", name = "이름", type = "TEXT",
            config = config, isRequired = true, entityType = entityType
        )

    @Test
    fun `설정이 없는 캐릭터 필드는 저장을 막는다`() {
        // B-90 이전 동작의 보존. 이것이 깨지면 사용자가 켜 둔 방어가 조용히 사라진다.
        assertEquals(
            RequiredEnforcement.BLOCK,
            RequiredEnforcement.resolve("{}", FieldDefinition.ENTITY_CHARACTER)
        )
    }

    @Test
    fun `설정이 없는 사건과 작품 필드는 막지 않는다`() {
        // 이쪽은 한 번도 막은 적이 없다 — 여기에 BLOCK을 주는 것이 사용자가 기각한 처방 ①이다.
        assertEquals(
            RequiredEnforcement.NOTIFY,
            RequiredEnforcement.resolve("{}", FieldDefinition.ENTITY_EVENT)
        )
        assertEquals(
            RequiredEnforcement.NOTIFY,
            RequiredEnforcement.resolve("{}", FieldDefinition.ENTITY_NOVEL)
        )
    }

    @Test
    fun `적힌 강도가 종류별 기본값을 이긴다`() {
        val blocking = """{"requiredEnforcement":"block"}"""
        val notifying = """{"requiredEnforcement":"notify"}"""
        // 캐릭터를 알림으로 내릴 수 있어야 '빡빡함'이 실제로 풀린다.
        assertEquals(RequiredEnforcement.NOTIFY, RequiredEnforcement.resolve(notifying, FieldDefinition.ENTITY_CHARACTER))
        // 사건을 막게 올릴 수도 있어야 자율성이 성립한다(기본이 아닐 뿐 금지가 아니다).
        assertEquals(RequiredEnforcement.BLOCK, RequiredEnforcement.resolve(blocking, FieldDefinition.ENTITY_EVENT))
    }

    @Test
    fun `새 필드의 기본값은 알림이다`() {
        // 차단이 기본이면 앱이 완성을 강요하게 되고, 그 강도가 기각된 것이다(원칙 04).
        assertEquals(RequiredEnforcement.NOTIFY, RequiredEnforcement.DEFAULT_FOR_NEW)
    }

    @Test
    fun `설정 유무를 가릴 수 있다`() {
        assertNull(RequiredEnforcement.fromConfigOrNull("{}"))
        assertNull(RequiredEnforcement.fromConfigOrNull("깨진 JSON"))
        assertNull("모르는 값은 설정되지 않은 것으로 본다", RequiredEnforcement.fromConfigOrNull("""{"requiredEnforcement":"강제"}"""))
        assertEquals(RequiredEnforcement.BLOCK, RequiredEnforcement.fromConfigOrNull("""{"requiredEnforcement":"block"}"""))
    }

    @Test
    fun `깨진 config는 종류별 기본값으로 떨어진다`() {
        // 값을 읽지 못했다고 필수 자체를 없던 일로 만들지 않는다(변수 제어).
        assertEquals(RequiredEnforcement.BLOCK, RequiredEnforcement.resolve("깨진 JSON", FieldDefinition.ENTITY_CHARACTER))
        assertEquals(RequiredEnforcement.NOTIFY, RequiredEnforcement.resolve("깨진 JSON", FieldDefinition.ENTITY_EVENT))
    }

    @Test
    fun `표식은 필수일 때만 붙고 모양이 한 곳에서 온다`() {
        // 라벨을 만드는 자리가 캐릭터·사건·작품에 흩어져 있어, 모양이 갈리면
        // '종류마다 다르게 동작하는 필수'가 그대로 재발한다.
        assertEquals("이름${RequiredFieldMark.SUFFIX}", RequiredFieldMark.label(field(entityType = FieldDefinition.ENTITY_EVENT)))
        assertEquals("이름", RequiredFieldMark.label("이름", isRequired = false))
        assertEquals("이름${RequiredFieldMark.SUFFIX}", RequiredFieldMark.label("이름", isRequired = true))
    }
}
