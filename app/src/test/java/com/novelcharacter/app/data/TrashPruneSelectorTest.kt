package com.novelcharacter.app.data

import com.novelcharacter.app.data.repository.TrashPruneSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴지통 보관 한도 정리의 선택 규칙 (N1 / 규약 R-3).
 *
 * 고정하는 계약: **정리는 이 작업이 방금 만든 백업을 절대 지우지 않는다.**
 * 종전 구현은 세계관 삭제(캐릭터 100명)에서 `count - 30`만큼을 오래된 순으로 지웠는데,
 * 그 오래된 쪽이 바로 방금 만든 백업이라 이미지 파일까지 함께 태우면서 UI는
 * "휴지통에서 복구할 수 있습니다"라고 약속했다.
 */
class TrashPruneSelectorTest {

    @Test
    fun `방금 만든 백업만 있으면 한도를 넘겨도 하나도 지우지 않는다`() {
        // 캐릭터 100명 세계관 삭제 — 100건 전부 이 작업이 만들었다.
        val ids = (1L..100L).toList()
        val protectedIds = ids.toSet()
        val doomed = TrashPruneSelector.selectOverflow(ids, protectedIds, overflow = 70)
        assertTrue("방금 만든 백업을 태웠다: $doomed", doomed.isEmpty())
    }

    @Test
    fun `보호 대상이 아닌 오래된 것부터 초과분만큼 지운다`() {
        val oldestFirst = listOf(1L, 2L, 3L, 4L, 5L)
        val doomed = TrashPruneSelector.selectOverflow(oldestFirst, setOf(4L, 5L), overflow = 2)
        assertEquals(listOf(1L, 2L), doomed)
    }

    @Test
    fun `보호 대상이 오래된 쪽에 섞여 있어도 건너뛰고 채운다`() {
        val oldestFirst = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        val doomed = TrashPruneSelector.selectOverflow(oldestFirst, setOf(1L, 3L), overflow = 3)
        assertEquals(listOf(2L, 4L, 5L), doomed)
    }

    @Test
    fun `비보호 후보가 초과분보다 적으면 있는 만큼만 지운다`() {
        // 한도를 넘겨도 지울 수 있는 것이 없으면 그대로 둔다 — 한도보다 보존이 우선이다.
        val oldestFirst = listOf(1L, 2L, 3L)
        val doomed = TrashPruneSelector.selectOverflow(oldestFirst, setOf(2L, 3L), overflow = 3)
        assertEquals(listOf(1L), doomed)
    }

    @Test
    fun `초과분이 0 이하면 아무것도 지우지 않는다`() {
        val oldestFirst = listOf(1L, 2L, 3L)
        assertTrue(TrashPruneSelector.selectOverflow(oldestFirst, emptySet(), 0).isEmpty())
        assertTrue(TrashPruneSelector.selectOverflow(oldestFirst, emptySet(), -5).isEmpty())
    }

    @Test
    fun `조회 한도는 보호 대상이 전부 오래된 쪽에 몰려도 충분하다`() {
        // 최악의 경우: 보호 대상 P개가 전부 가장 오래된 자리에 있다.
        // 그래도 비보호 후보를 overflow개 확보하려면 P + overflow개를 읽어야 한다.
        val overflow = 4
        val protectedIds = setOf(1L, 2L, 3L)
        val all = (1L..20L).toList()
        val limit = TrashPruneSelector.fetchLimit(overflow, protectedIds.size)
        assertEquals(7, limit)
        val doomed = TrashPruneSelector.selectOverflow(all.take(limit), protectedIds, overflow)
        assertEquals("조회 한도가 부족해 초과분을 못 채웠다", overflow, doomed.size)
        assertEquals(listOf(4L, 5L, 6L, 7L), doomed)
    }

    @Test
    fun `조회 한도로 자른 후보 집합에서도 전량 조회와 같은 결과가 나온다`() {
        // 보호 분포를 바꿔 가며 '전량 조회 후 선택'과 '한도만큼만 조회 후 선택'이 일치하는지 확인.
        val all = (1L..40L).toList()
        for (overflow in 1..10) {
            for (shift in 0 until 12) {
                val protectedIds = (0 until shift).map { all[it] }.toSet()
                val limited = TrashPruneSelector.selectOverflow(
                    all.take(TrashPruneSelector.fetchLimit(overflow, protectedIds.size)),
                    protectedIds,
                    overflow
                )
                val full = TrashPruneSelector.selectOverflow(all, protectedIds, overflow)
                assertEquals("overflow=$overflow protected=$shift", full, limited)
            }
        }
    }
}
