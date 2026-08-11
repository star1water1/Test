package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 연표·작품 시트의 필드 열 → 필드 해석 (B-65 · 사용자 확정 15번 ㄱ1).
 *
 * **방어선은 케이스 수가 아니라 그 안의 셋이다:**
 * ① *정확 일치한 열은 구역이 달라도 받는다* — 이 한 줄이 B-65가 고친 것의 전부다. 되돌리면
 *    세계관을 옮겨 생긴 값이 다시 왕복에서 사라지고, **사라진 값은 되돌릴 길이 없다.**
 * ② *한정자가 있으면 그 구역에서만 찾는다* — ①을 넓히다 보면 여기까지 함께 넓히기 쉬운데,
 *    그러면 동명의 남의 필드에 값이 붙는다(R-1: 오배정은 생략보다 나쁘다).
 * ③ *한정자가 없고 이름도 유일하지 않으면 멈춘다* — 고를 근거가 없을 때 아무거나 고르지 않는다.
 */
class EntityFieldColumnResolverTest {

    private fun field(id: Long, name: String, universeId: Long?) = FieldDefinition(
        id = id, universeId = universeId, key = "k$id", name = name, type = "TEXT",
        entityType = FieldDefinition.ENTITY_EVENT
    )

    private val aInU1 = field(1, "규모", 1)
    private val aInU2 = field(2, "규모", 2)
    private val soloInU2 = field(3, "지역", 2)
    private val globalOne = field(4, "공통메모", null)
    private val universeIds = mapOf("아스트라" to 1L, "다른세계" to 2L)

    private fun resolve(
        resolved: FieldDefinition? = null,
        name: String = "",
        qualifier: String? = null,
        entityUniverseId: Long?,
        fields: List<FieldDefinition>
    ) = EntityFieldColumnResolver.resolve(resolved, name, qualifier, entityUniverseId, fields, universeIds)

    // ===== ① 정확 일치는 구역을 넘어 받는다 =====

    @Test
    fun exactMatch_isAcceptedAcrossUniverses() {
        // 세계관 1의 사건인데 열은 세계관 2의 필드로 확정됐다 — 소유자가 세계관을 옮겨 생긴 값이다
        assertEquals(
            aInU2,
            resolve(resolved = aInU2, entityUniverseId = 1L, fields = listOf(aInU1, aInU2))
        )
    }

    @Test
    fun exactMatch_isAcceptedForUnassignedEntity() {
        // 무소속 작품·사건도 마찬가지 — 종전에는 구역이 null이면 열을 통째로 무시했다
        assertEquals(
            globalOne,
            resolve(resolved = globalOne, entityUniverseId = null, fields = listOf(globalOne))
        )
    }

    // ===== ② 한정자는 구역을 좁힌다 =====

    @Test
    fun qualifier_picksThatUniverseOnly() {
        assertEquals(
            aInU2,
            resolve(name = "규모", qualifier = "다른세계", entityUniverseId = 1L, fields = listOf(aInU1, aInU2))
        )
    }

    @Test
    fun qualifier_globalScope_picksGlobalField() {
        val shadow = field(5, "공통메모", 1)
        assertEquals(
            globalOne,
            resolve(
                name = "공통메모", qualifier = EntityFieldHeaders.GLOBAL_QUALIFIER,
                entityUniverseId = 1L, fields = listOf(shadow, globalOne)
            )
        )
    }

    @Test
    fun qualifier_unknownUniverse_stopsInsteadOfGuessing() {
        assertNull(
            resolve(name = "규모", qualifier = "사라진세계", entityUniverseId = 1L, fields = listOf(aInU1, aInU2))
        )
    }

    // ===== ③ 한정자가 없을 때 =====

    @Test
    fun noQualifier_prefersOwnUniverse() {
        assertEquals(
            aInU1,
            resolve(name = "규모", entityUniverseId = 1L, fields = listOf(aInU1, aInU2))
        )
    }

    @Test
    fun noQualifier_acceptsUniqueNameFromAnotherUniverse() {
        // 한정자가 없다 = 내보내기 규칙상 그 이름이 유일했다는 뜻이다(그것이 이 해석의 근거다)
        assertEquals(
            soloInU2,
            resolve(name = "지역", entityUniverseId = 1L, fields = listOf(aInU1, soloInU2))
        )
    }

    @Test
    fun noQualifier_ambiguousName_stopsInsteadOfGuessing() {
        // 이 행의 구역에도 없고 이름도 여럿이면 고를 근거가 없다 — 호출부가 경고한다
        assertNull(
            resolve(name = "규모", entityUniverseId = 3L, fields = listOf(aInU1, aInU2))
        )
    }

    @Test
    fun unknownName_isNotAColumn() {
        assertNull(resolve(name = "없는필드", entityUniverseId = 1L, fields = listOf(aInU1)))
    }
}
