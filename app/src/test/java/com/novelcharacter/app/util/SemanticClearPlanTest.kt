package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SemanticRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SemanticClearPlan] — 시맨틱 필드를 비웠을 때의 파생 정리 (R-1: 오배정은 생략보다 나쁘다).
 *
 * **두 방향의 사고를 함께 잰다.** 안 지우면 생일을 비워도 알림이 계속 울리고, 함부로 지우면
 * 연표 사건으로 만든 출생 기록이 사라진다.
 */
class SemanticClearPlanTest {

    private fun field(id: Long, role: SemanticRole?): FieldDefinition = FieldDefinition(
        id = id, universeId = 1L, key = "k$id", name = "f$id", type = FieldType.TEXT.name,
        config = if (role == null) "{}" else """{"semanticRole":"${role.key}"}"""
    )

    private fun value(fieldId: Long, v: String) =
        CharacterFieldValue(characterId = 1L, fieldDefinitionId = fieldId, value = v)

    // ── ① 어느 범위를 비움으로 읽는가 ────────────────────────────────────────

    /** 범위를 모르면 **아무것도 판정하지 않는다** — 이것이 오삭제를 막는 유일한 장치다. */
    @Test
    fun `범위가 없으면 비움으로 읽지 않는다`() {
        val fields = listOf(field(1L, SemanticRole.BIRTH_YEAR))
        assertTrue(SemanticClearPlan.clearedRoles(fields, emptyList(), null).isEmpty())
    }

    @Test
    fun `범위 안에서 값이 없으면 비움이다`() {
        val fields = listOf(field(1L, SemanticRole.BIRTH_YEAR))
        assertEquals(
            setOf(SemanticRole.BIRTH_YEAR),
            SemanticClearPlan.clearedRoles(fields, emptyList(), setOf(1L))
        )
    }

    /** 폼이 그리지 않은 칸은 범위 밖이다 — 그 부재는 비움이 아니다. */
    @Test
    fun `범위 밖 필드의 부재는 비움이 아니다`() {
        val fields = listOf(field(1L, SemanticRole.BIRTH_YEAR), field(2L, SemanticRole.DEATH_YEAR))
        assertEquals(
            setOf(SemanticRole.BIRTH_YEAR),
            SemanticClearPlan.clearedRoles(fields, emptyList(), setOf(1L))
        )
    }

    @Test
    fun `값이 있으면 비움이 아니다`() {
        val fields = listOf(field(1L, SemanticRole.BIRTH_YEAR))
        assertTrue(
            SemanticClearPlan.clearedRoles(fields, listOf(value(1L, "1993")), setOf(1L)).isEmpty()
        )
    }

    /** 공백만 남은 값은 값이 아니다 — 저장 규약이 빈 값을 걸러 넣는 것과 같은 잣대다. */
    @Test
    fun `공백뿐인 값은 비움으로 읽는다`() {
        val fields = listOf(field(1L, SemanticRole.BIRTH_YEAR))
        assertEquals(
            setOf(SemanticRole.BIRTH_YEAR),
            SemanticClearPlan.clearedRoles(fields, listOf(value(1L, "   ")), setOf(1L))
        )
    }

    @Test
    fun `역할이 없는 필드는 비워도 아무것도 안 낸다`() {
        val fields = listOf(field(1L, null))
        assertTrue(SemanticClearPlan.clearedRoles(fields, emptyList(), setOf(1L)).isEmpty())
    }

    // ── ② 출생 이력에서 무엇을 지우는가 ──────────────────────────────────────

    /** 연도만 비웠으면 월·일은 남는다 — 그것은 사용자가 지운 것이 아니다. */
    @Test
    fun `연도만 비우면 월일은 남는다`() {
        val plan = SemanticClearPlan.planBirthClear(1993, 3, 15, clearYear = true, clearDate = false)
        assertFalse(plan.delete)
        assertEquals(0, plan.year)
        assertEquals(3, plan.month)
        assertEquals(15, plan.day)
    }

    /** 생일만 비웠으면 연도는 남는다. */
    @Test
    fun `생일만 비우면 연도는 남는다`() {
        val plan = SemanticClearPlan.planBirthClear(1993, 3, 15, clearYear = false, clearDate = true)
        assertFalse(plan.delete)
        assertEquals(1993, plan.year)
        assertEquals(null, plan.month)
        assertEquals(null, plan.day)
    }

    /** 남길 것이 하나도 없을 때만 행을 지운다. */
    @Test
    fun `연도와 생일을 함께 비우면 행을 지운다`() {
        val plan = SemanticClearPlan.planBirthClear(1993, 3, 15, clearYear = true, clearDate = true)
        assertTrue(plan.delete)
    }

    /** 연도가 이미 미상(0)이었다면 생일만 비워도 남을 것이 없다. */
    @Test
    fun `연도가 미상이면 생일만 비워도 행을 지운다`() {
        val plan = SemanticClearPlan.planBirthClear(0, 3, 15, clearYear = false, clearDate = true)
        assertTrue(plan.delete)
    }

    /** 월·일이 없던 이력은 연도만 비워도 남을 것이 없다. */
    @Test
    fun `월일이 없으면 연도만 비워도 행을 지운다`() {
        val plan = SemanticClearPlan.planBirthClear(1993, null, null, clearYear = true, clearDate = false)
        assertTrue(plan.delete)
    }

    /** 아무것도 안 비우면 아무것도 안 바뀐다. */
    @Test
    fun `비운 것이 없으면 그대로다`() {
        val plan = SemanticClearPlan.planBirthClear(1993, 3, 15, clearYear = false, clearDate = false)
        assertFalse(plan.delete)
        assertEquals(1993, plan.year)
        assertEquals(3, plan.month)
        assertEquals(15, plan.day)
    }
}
