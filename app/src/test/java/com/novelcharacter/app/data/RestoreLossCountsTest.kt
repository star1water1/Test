package com.novelcharacter.app.data

import com.novelcharacter.app.data.repository.RestoreLossCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 복원 유실 규모의 예측/사실 비교 (N1).
 *
 * 고정하는 계약: **총량이 아니라 항목별로 비교한다.** 총량 하나로 보면 예고분이 사라지고
 * 다른 유실이 새로 생긴 경우(사용자가 동의한 적 없는 유실)를 놓친다 —
 * 자기 재공격의 반증 전담이 남긴 잔여 관찰이 정확히 이것이었다.
 */
class RestoreLossCountsTest {

    @Test
    fun `예고분이 사라지고 다른 유실이 생기면 총량이 줄어도 알린다`() {
        val predicted = RestoreLossCounts(fieldValues = 5)
        // 실제로는 필드값이 다 살아났지만, 중복 관계에 매달린 이력 3건이 합쳐지지 못했다.
        val actual = RestoreLossCounts(duplicateRelationshipChanges = 3)
        assertTrue("총량(3 ≤ 5)만 보면 놓치는 경우다", actual.exceeds(predicted))
    }

    @Test
    fun `예고한 그대로면 다시 알리지 않는다`() {
        val predicted = RestoreLossCounts(fieldValues = 5, relationships = 2, novelCleared = true)
        assertFalse(predicted.exceeds(predicted))
    }

    @Test
    fun `예고보다 줄기만 했으면 알리지 않는다`() {
        val predicted = RestoreLossCounts(fieldValues = 5, relationships = 2)
        val actual = RestoreLossCounts(fieldValues = 3, relationships = 1)
        assertFalse(actual.exceeds(predicted))
    }

    @Test
    fun `어느 한 항목이라도 커지면 알린다`() {
        val predicted = RestoreLossCounts(fieldValues = 5, relationships = 2)
        assertTrue(RestoreLossCounts(fieldValues = 6, relationships = 2).exceeds(predicted))
        assertTrue(RestoreLossCounts(fieldValues = 0, relationships = 3).exceeds(predicted))
        assertTrue(RestoreLossCounts(fieldValues = 5, relationships = 2, events = 1).exceeds(predicted))
    }

    @Test
    fun `예고 없던 작품 해제가 실제로 일어나면 알린다`() {
        assertTrue(RestoreLossCounts(novelCleared = true).exceeds(RestoreLossCounts()))
        assertFalse(RestoreLossCounts().exceeds(RestoreLossCounts(novelCleared = true)))
    }

    @Test
    fun `유실이 없으면 any가 거짓이다`() {
        assertFalse(RestoreLossCounts().any)
        assertTrue(RestoreLossCounts(mergedFieldValues = 1).any)
        assertTrue(RestoreLossCounts(novelCleared = true).any)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 필드가 늘어날 때의 누락 방지 (B-1)
    //
    // 항목은 세 곳에 나타난다: 생성자 · counts · plus.
    // 하나라도 뒤처지면 그 유실은 **조용히 0으로 보고된다** — "유실이 없다"는 거짓말은
    // 무음 유실과 같은 부류다. 아래 두 테스트는 필드 이름을 적지 않고 리플렉션으로 전수
    // 확인하므로, 새 항목을 추가하면서 어느 한 곳을 잊으면 반드시 실패한다.
    // ──────────────────────────────────────────────────────────────────────

    /** 모든 Int 파라미터에 [intValue], 모든 Boolean 파라미터에 true를 넣은 인스턴스. */
    private fun fullyPopulated(intValue: Int): RestoreLossCounts {
        val ctor = RestoreLossCounts::class.java.declaredConstructors
            .filter { c -> c.parameterTypes.all { it == Int::class.java || it == Boolean::class.java } }
            .maxByOrNull { it.parameterCount }
            ?: error("전 파라미터가 Int/Boolean인 생성자를 찾지 못했다")
        val args = ctor.parameterTypes.map { type ->
            if (type == Boolean::class.java) true else intValue
        }.toTypedArray()
        return ctor.newInstance(*args) as RestoreLossCounts
    }

    @Test
    fun `counts는 선언된 모든 항목을 담는다`() {
        val all = fullyPopulated(7)
        val declared = RestoreLossCounts::class.java.declaredFields
            .filter { it.type == Int::class.java || it.type == Boolean::class.java }
            .map { it.name }
            .toSet()
        val counted = all.counts.map { it.first }.toSet()
        assertEquals(
            "counts에 빠진 항목이 있으면 그 유실은 total·any·exceeds 어디에도 잡히지 않는다",
            declared, counted
        )
        assertTrue("모든 항목이 채워졌는데 any가 거짓이다", all.any)
    }

    @Test
    fun `plus는 선언된 모든 항목을 더한다`() {
        val sum = fullyPopulated(1) + fullyPopulated(2)
        val zero = RestoreLossCounts()
        for ((name, value) in sum.counts) {
            val isBoolean = RestoreLossCounts::class.java.getDeclaredField(name).type == Boolean::class.java
            val expected = if (isBoolean) 1 else 3
            assertEquals("plus가 '$name'을 빠뜨렸다 (합계에서 조용히 사라진다)", expected, value)
        }
        assertEquals("빈 값을 더해도 그대로여야 한다", sum, sum + zero)
        assertEquals("교환법칙", sum, zero + sum)
    }

    @Test
    fun `새 항목도 exceeds에 자동으로 편입된다`() {
        // counts가 단일 소스이므로 항목을 추가해도 exceeds를 따로 고칠 필요가 없다.
        assertTrue(fullyPopulated(1).exceeds(RestoreLossCounts()))
        assertFalse(RestoreLossCounts().exceeds(fullyPopulated(1)))
        assertFalse(fullyPopulated(1).exceeds(fullyPopulated(1)))
    }
}
