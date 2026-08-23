package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SemanticRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SemanticAlivePrecedence] — 사용자가 고른 생존여부가 사망연도 파생을 이긴다.
 *
 * **잠그는 것은 차례 독립성이다.** 종전 결함의 뿌리는 두 갈래(`DEATH_YEAR`·`ALIVE`)가 한
 * 저장에서 같은 칸을 쓰면서 **값 목록의 차례**에 따라 답이 갈린 것이었다. 이 판정이 루프
 * 밖에서 한 번 서면 차례가 답을 바꾸지 못한다.
 */
class SemanticAlivePrecedenceTest {

    private val ALIVE_CONFIG =
        """{"semanticRole":"${SemanticRole.ALIVE.key}","aliveValue":"생존","deadValue":"사망"}"""

    private fun aliveField(id: Long = 10L, config: String = ALIVE_CONFIG) = FieldDefinition(
        id = id, universeId = 1L, key = "alive", name = "생존여부",
        type = FieldType.SELECT.name, config = config
    )

    private fun deathField(id: Long = 20L) = FieldDefinition(
        id = id, universeId = 1L, key = "death", name = "사망연도", type = FieldType.NUMBER.name,
        config = """{"semanticRole":"${SemanticRole.DEATH_YEAR.key}"}"""
    )

    private fun value(fieldId: Long, v: String) =
        CharacterFieldValue(characterId = 1L, fieldDefinitionId = fieldId, value = v)

    /** 생존여부 필드가 없으면 파생이 그대로 돈다 — 이 규칙이 켜지는 자리를 좁힌다. */
    @Test
    fun `생존여부 필드가 없으면 파생이 그대로 돈다`() {
        assertEquals(
            SemanticAlivePrecedence.DeathDerivation.APPLY,
            SemanticAlivePrecedence.deathDerivation(listOf(deathField()), listOf(value(20L, "1000")))
        )
    }

    /** 값이 안 실려 온 저장 — 사망연도만 적은 사용자는 종전대로 생존여부를 받아야 한다. */
    @Test
    fun `생존여부 값이 안 실려 오면 파생이 그대로 돈다`() {
        assertEquals(
            SemanticAlivePrecedence.DeathDerivation.APPLY,
            SemanticAlivePrecedence.deathDerivation(
                listOf(aliveField(), deathField()), listOf(value(20L, "1000"))
            )
        )
    }

    /** 빈 값은 고르지 않은 것이다 — 저장 규약이 빈 값을 값 없음으로 읽는 것과 같게 읽는다. */
    @Test
    fun `빈 생존여부는 고르지 않은 것으로 본다`() {
        assertEquals(
            SemanticAlivePrecedence.DeathDerivation.APPLY,
            SemanticAlivePrecedence.deathDerivation(
                listOf(aliveField(), deathField()), listOf(value(10L, "   "), value(20L, "1000"))
            )
        )
    }

    /** **이 판이 고친 자리다** — '불명'을 고르면 파생이 필드값을 되덮지 않는다. */
    @Test
    fun `불명을 고르면 이력만 남기고 필드값은 건드리지 않는다`() {
        assertEquals(
            SemanticAlivePrecedence.DeathDerivation.HISTORY_ONLY,
            SemanticAlivePrecedence.deathDerivation(
                listOf(aliveField(), deathField()), listOf(value(10L, "불명"), value(20L, "1000"))
            )
        )
    }

    /** '사망'도 명시다 — 파생이 같은 값을 다시 쓸 이유가 없고, `__alive`는 ALIVE 갈래의 몫이다. */
    @Test
    fun `사망을 고르면 이력만 남긴다`() {
        assertEquals(
            SemanticAlivePrecedence.DeathDerivation.HISTORY_ONLY,
            SemanticAlivePrecedence.deathDerivation(
                listOf(aliveField(), deathField()), listOf(value(10L, "사망"), value(20L, "1000"))
            )
        )
    }

    /** '생존'은 사망연도를 **지우는** 선택이라 파생이 아무것도 하지 않아야 한다. */
    @Test
    fun `생존을 고르면 사망연도 갈래는 아무것도 하지 않는다`() {
        assertEquals(
            SemanticAlivePrecedence.DeathDerivation.SKIP,
            SemanticAlivePrecedence.deathDerivation(
                listOf(aliveField(), deathField()), listOf(value(10L, "생존"), value(20L, "1000"))
            )
        )
    }

    /** **차례가 답을 바꾸지 못한다** — 이것이 이 판정을 루프 밖으로 뺀 이유다. */
    @Test
    fun `값 목록의 차례가 답을 바꾸지 못한다`() {
        val fields = listOf(aliveField(), deathField())
        for (v in listOf("생존", "사망", "불명")) {
            val forward = SemanticAlivePrecedence.deathDerivation(
                fields, listOf(value(10L, v), value(20L, "1000"))
            )
            val reversed = SemanticAlivePrecedence.deathDerivation(
                fields, listOf(value(20L, "1000"), value(10L, v))
            )
            assertEquals("차례가 답을 바꿨다: $v", forward, reversed)
        }
    }

    /** 설정을 못 읽으면 **종전 동작을 지킨다** — 모른다고 파생을 끄면 더 흔한 경로가 죽는다. */
    @Test
    fun `설정이 깨져 있으면 종전 동작을 지킨다`() {
        assertEquals(
            SemanticAlivePrecedence.DeathDerivation.APPLY,
            SemanticAlivePrecedence.deathDerivation(
                listOf(aliveField(config = """{"semanticRole":"${SemanticRole.ALIVE.key}"""), deathField()),
                listOf(value(10L, "불명"), value(20L, "1000"))
            )
        )
    }

    /** `aliveValue`가 비어 있으면 '생존'을 가려낼 수 없다 — 지우는 쪽으로 넘기지 않는다. */
    @Test
    fun `aliveValue가 비어 있으면 지우는 갈래로 가지 않는다`() {
        assertEquals(
            SemanticAlivePrecedence.DeathDerivation.HISTORY_ONLY,
            SemanticAlivePrecedence.deathDerivation(
                listOf(
                    aliveField(config = """{"semanticRole":"${SemanticRole.ALIVE.key}","deadValue":"사망"}"""),
                    deathField()
                ),
                listOf(value(10L, "무엇이든"), value(20L, "1000"))
            )
        )
    }
}
