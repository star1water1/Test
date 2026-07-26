package com.novelcharacter.app.data

import com.novelcharacter.app.data.repository.RestoreLossCounts
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
}
