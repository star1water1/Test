package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.DefaultFieldRef
import com.novelcharacter.app.data.model.DuelGradeRef
import com.novelcharacter.app.data.model.FieldConfigTransfer
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 필드 config가 **종류 경계**를 넘을 때의 강등 (B-63 · 확정 14번).
 *
 * 세계관 경계(`demoteAcrossUniverse`)와 **다른 축이다.** 그쪽은 *"남의 세계관을 가리키는
 * 참조"*를 걷고, 이쪽은 *"이 종류에서는 뜻이 없는 설정"*을 걷는다. 둘을 합치지 않는 것이
 * 설계이며, 경계 둘을 함께 넘는 복사는 두 함수를 차례로 부른다.
 *
 * **이 계층의 방어선은 셋이다:**
 * ① *"세는 쪽과 걷는 쪽이 한 벌이다"* — 갈리면 미리보기가 *"설정 A가 빠집니다"*라 해 놓고
 *    실제로는 B를 걷거나, 예고 없이 걷는다. 사용자는 심고 난 뒤에야 알게 되고 그때는
 *    되돌리는 것 말고 손쓸 방법이 없다(개발 의도 2번).
 * ② *"역할은 이름을 박지 않고 [SemanticRole.entityTypes]에 묻는다"* — 나중에 사건 역할이
 *    실리면 그 역할은 **저절로** 살아남아야 한다. 여기에 `"event"`를 적어 두면 그날
 *    멀쩡한 설정이 계속 걷힌다(`SemanticRoleEntityScopeTest`가 지키는 계약의 이쪽 끝).
 * ③ *"손상 JSON은 손대지 않는다"* — 다시 써 내려가면 파싱되지 않던 원문까지 잃는다.
 *    읽지 못하는 것과 지우는 것은 다른 사실이다.
 */
class FieldConfigEntityTypeTransferTest {

    private val character = FieldDefinition.ENTITY_CHARACTER
    private val event = FieldDefinition.ENTITY_EVENT

    // ── ① 세는 쪽과 걷는 쪽이 한 벌이다 ──

    @Test
    fun `센 키가 실제로 걷힌다`() {
        val config = """{"semanticRole":"age","bodyAnalysis":{"ribOffset":2},"label":"나이"}"""
        val dropped = FieldConfigTransfer.droppedKeys(config, character, event)
        val demoted = FieldConfigTransfer.demoteAcrossEntityType(config, character, event)
        assertTrue(dropped.containsAll(listOf("semanticRole", "bodyAnalysis")))
        for (key in dropped) assertFalse("'$key'가 남아 있다", demoted.contains("\"$key\""))
        // 목록에 없는 것은 그대로 남는다 — 걷는 것은 종류를 넘지 못하는 키뿐이다.
        assertTrue(demoted.contains("label"))
    }

    @Test
    fun `config에 없는 키는 예고하지 않는다`() {
        // 빠지지도 않을 것을 예고하면 사용자가 잃는 것이 있다고 오해한다.
        val dropped = FieldConfigTransfer.droppedKeys("""{"label":"장소"}""", character, event)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `걷을 것이 없으면 원문 그대로다`() {
        // 재직렬화하면 키 순서가 뒤바뀌어 `configEquals`가 아닌 문자열 비교를 하는 자리에서
        // 같은 정의가 '달라졌다'로 뜬다(PresetMerge.configEquals의 KDoc이 같은 함정을 든다).
        val config = """{"label":"장소","options":["집","길"]}"""
        assertEquals(config, FieldConfigTransfer.demoteAcrossEntityType(config, character, event))
    }

    @Test
    fun `종류가 같으면 아무것도 걷지 않는다`() {
        val config = """{"semanticRole":"age","bodyAnalysis":{"ribOffset":2}}"""
        assertEquals(config, FieldConfigTransfer.demoteAcrossEntityType(config, character, character))
        assertTrue(FieldConfigTransfer.droppedKeys(config, character, character).isEmpty())
    }

    // ── ② 역할은 성립 종류에 묻는다 ──

    @Test
    fun `사건에서 성립하지 않는 역할은 걷힌다`() {
        // 지금은 역할이 전부 캐릭터 축이다(SemanticRoleEntityScopeTest가 그 사실을 든다).
        for (role in SemanticRole.entries) {
            val config = SemanticRole.applyToConfig("{}", role)
            val dropped = FieldConfigTransfer.droppedKeys(config, character, event)
            assertTrue("역할 '${role.key}'", "semanticRole" in dropped)
        }
    }

    @Test
    fun `성립하는 역할은 살아남는다 — 종류 이름을 박아 두지 않는다`() {
        // 이 계약이 확장 경로 그 자체다: 나중에 사건 역할이 entityTypes에 실리면
        // 그 역할은 **이 코드를 고치지 않아도** 걷히지 않아야 한다.
        for (role in SemanticRole.entries) {
            val target = role.entityTypes.first()
            val config = SemanticRole.applyToConfig("{}", role)
            val dropped = FieldConfigTransfer.droppedKeys(config, "faction", target)
            assertFalse("역할 '${role.key}'가 성립 종류 '$target'에서 걷혔다", "semanticRole" in dropped)
        }
    }

    // ── 종류를 넘지 못하는 나머지 셋 ──

    @Test
    fun `대결 등급 산정은 캐릭터 밖으로 넘어가지 않는다`() {
        // 대결 참가자는 캐릭터다 — 사건에는 그 순위 자체가 존재하지 않는다.
        val config = """{"${DuelGradeRef.CONFIG_KEY}":{"axisCode":"AX1"}}"""
        assertTrue(DuelGradeRef.CONFIG_KEY in FieldConfigTransfer.droppedKeys(config, character, event))
        assertFalse(
            FieldConfigTransfer.demoteAcrossEntityType(config, character, event)
                .contains(DuelGradeRef.CONFIG_KEY)
        )
    }

    @Test
    fun `전역 기본 필드 표식은 종류가 바뀌면 걷힌다`() {
        // 템플릿의 자리는 **(종류, 키)** 쌍이다(`repo.getBySlot(entityType, key)`) —
        // 종류만 바꿔 심으면 그 표식이 **남의 자리**를 가리킨다.
        val config = """{"${DefaultFieldRef.CONFIG_KEY}":"DF-1"}"""
        assertTrue(DefaultFieldRef.CONFIG_KEY in FieldConfigTransfer.droppedKeys(config, character, event))
        assertEquals(
            null,
            DefaultFieldRef.codeFromConfig(
                FieldConfigTransfer.demoteAcrossEntityType(config, character, event)
            )
        )
    }

    @Test
    fun `등급 체계 참조는 종류 경계에서 걷지 않는다`() {
        // 등급 체계는 **세계관 단위**이지 종류 단위가 아니다 — 같은 세계관 안에서 종류만
        // 바뀌는 이 경로에서는 참조가 그대로 성립한다. 걷으면 사용자가 고른 체계가 사라진다.
        val config = """{"gradeSystem":"GS-1"}"""
        assertTrue(FieldConfigTransfer.droppedKeys(config, character, event).isEmpty())
        assertEquals(config, FieldConfigTransfer.demoteAcrossEntityType(config, character, event))
    }

    @Test
    fun `캐릭터로 들일 때는 캐릭터 축 설정이 살아남는다`() {
        // 반대 방향 — 사건 필드를 캐릭터로 들이는 경우. 걷을 이유가 없는 것을 걷지 않는지
        // 반대편에서 잠근다(한쪽만 재면 '전부 걷는' 구현도 초록이다).
        val config = """{"bodyAnalysis":{"ribOffset":2},"${DuelGradeRef.CONFIG_KEY}":{"axisCode":"AX1"}}"""
        assertTrue(FieldConfigTransfer.droppedKeys(config, event, character).isEmpty())
    }

    // ── ③ 손상 JSON ──

    @Test
    fun `손상 JSON은 손대지 않는다`() {
        val broken = "{not json"
        assertTrue(FieldConfigTransfer.droppedKeys(broken, character, event).isEmpty())
        assertEquals(broken, FieldConfigTransfer.demoteAcrossEntityType(broken, character, event))
    }
}
