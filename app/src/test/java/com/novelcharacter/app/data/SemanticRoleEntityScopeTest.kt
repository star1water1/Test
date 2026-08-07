package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시맨틱 역할이 성립하는 필드 종류의 계약 (B-81).
 *
 * **이 테스트가 지키는 것은 "감춤이 종류 이름으로 하드코딩되지 않는다"이다.**
 * 사건·작품에서 섹션이 사라지는 근거는 `entityType == "event"` 같은 조건문이 아니라
 * **[SemanticRole.forEntityType]이 빈 목록을 낸다는 사실** 하나뿐이다. 그래야 나중에
 * 사건·작품 역할(시작연도·종료연도 / 출간연도·순서)을 [SemanticRole.entityTypes]에
 * 실었을 때 화면 코드를 고치지 않아도 섹션이 저절로 다시 선다.
 */
class SemanticRoleEntityScopeTest {

    @Test
    fun `캐릭터에서는 모든 역할이 성립한다`() {
        val roles = SemanticRole.forEntityType(FieldDefinition.ENTITY_CHARACTER)
        assertEquals(SemanticRole.entries.size, roles.size)
        assertTrue(roles.containsAll(SemanticRole.entries))
    }

    @Test
    fun `사건과 작품에서는 성립하는 역할이 하나도 없다`() {
        // 0개라는 것이 화면에서 섹션이 사라지는 유일한 근거다.
        // 지금 역할이 전부 캐릭터 축이기 때문이다 — 대부분은 캐릭터 시스템 필드
        // (__birth·__age·__height…)와 잇고, GENDER처럼 **잇는 것 없이 표식만인 역할**도
        // 성별이 캐릭터의 성질이라 캐릭터 축이다.
        // **수를 적지 않는다** — 적으면 역할이 하나 늘 때마다 낡는다(실제로 GENDER에서 낡았다).
        assertTrue(SemanticRole.forEntityType(FieldDefinition.ENTITY_EVENT).isEmpty())
        assertTrue(SemanticRole.forEntityType(FieldDefinition.ENTITY_NOVEL).isEmpty())
    }

    @Test
    fun `모르는 종류는 빈 목록이다`() {
        // 새 종류가 생겼는데 역할을 정하지 않았다면 '보이지 않는다'가 안전한 기본값이다 —
        // 고를 수 있는데 아무 일도 일어나지 않는 자리를 만들지 않는다(R-24).
        assertTrue(SemanticRole.forEntityType("faction").isEmpty())
        assertTrue(SemanticRole.forEntityType("").isEmpty())
    }

    @Test
    fun `목록은 entityTypes에서만 끌어낸다 — 종류 이름을 박아 두지 않는다`() {
        // 이 계약이 확장 경로 그 자체다: 어떤 역할이든 entityTypes에 종류를 실으면
        // forEntityType이 그 종류에서 그 역할을 내야 한다. 여기가 깨지면 '나중에 열면 된다'가
        // 거짓이 되고, 감추기와 다를 바 없어진다.
        val entityTypes = listOf(
            FieldDefinition.ENTITY_CHARACTER,
            FieldDefinition.ENTITY_EVENT,
            FieldDefinition.ENTITY_NOVEL,
            "faction"
        )
        for (entityType in entityTypes) {
            val actual = SemanticRole.forEntityType(entityType).toSet()
            val expected = SemanticRole.entries.filter { entityType in it.entityTypes }.toSet()
            assertEquals("종류 '$entityType'", expected, actual)
        }
    }

    @Test
    fun `역할은 저마다 성립 종류를 최소 하나는 갖는다`() {
        // 빈 entityTypes는 어디에서도 못 고르는 역할이 되어 조용히 죽는다.
        for (role in SemanticRole.entries) {
            assertTrue("역할 '${role.key}'에 성립 종류가 없다", role.entityTypes.isNotEmpty())
        }
    }
}
