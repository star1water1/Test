package com.novelcharacter.app.data

import com.novelcharacter.app.data.repository.TrashPruneSelector
import com.novelcharacter.app.data.repository.TrashPruneSelector.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴지통 보관 한도 정리의 선택 규칙 (N1 / 규약 R-3 → B-1/B-14).
 *
 * 고정하는 계약 두 가지:
 * 1. **정리는 이 작업이 방금 만든 백업을 절대 지우지 않는다.** 종전 구현은 세계관 삭제
 *    (캐릭터 100명)에서 `count - 30`만큼을 오래된 순으로 지웠는데, 그 오래된 쪽이 바로
 *    방금 만든 백업이라 이미지 파일까지 함께 태우면서 UI는 "휴지통에서 복구할 수 있습니다"
 *    라고 약속했다.
 * 2. **선택 단위는 항목이 아니라 삭제 작업이다.** 항목 단위면 캐릭터 100명짜리 세계관을
 *    지운 뒤 아무거나 하나 더 지우는 것만으로 그 묶음이 71건 소각되고, 남은 29건은
 *    "세계관은 되살아나는데 캐릭터는 없는" 반쪽 백업이 된다 — 복원이 아니라 유실이다.
 */
class TrashPruneSelectorTest {

    private fun op(key: String, at: Long, count: Int = 1) = Operation(key, at, count)

    // ── 개수 초과 ──

    @Test
    fun `방금 만든 작업만 있으면 한도를 넘겨도 하나도 지우지 않는다`() {
        // 세계관 삭제 한 번이 캐릭터 100명 + 세계관 1개를 만들었다 — 작업으로는 1건이지만
        // 다른 작업 40건이 이미 있어 한도(30)를 넘긴 상황.
        val ops = (1..40).map { op("old$it", it.toLong()) } + op("mine", 999L, 101)
        val doomed = TrashPruneSelector.selectOverflow(ops, setOf("mine"), maxOperations = 30)
        assertTrue("방금 만든 작업을 태웠다: $doomed", "mine" !in doomed)
    }

    @Test
    fun `한 작업의 항목 수는 한도 계산에 영향을 주지 않는다`() {
        // B-14의 핵심: 캐릭터 100명짜리 삭제도 '작업 1건'이다.
        val ops = listOf(op("a", 1L, count = 100), op("b", 2L, count = 1))
        assertTrue(TrashPruneSelector.selectOverflow(ops, emptySet(), maxOperations = 30).isEmpty())
    }

    @Test
    fun `보호 대상이 아닌 오래된 작업부터 초과분만큼 지운다`() {
        val ops = (1..5).map { op("k$it", it.toLong()) }
        val doomed = TrashPruneSelector.selectOverflow(ops, setOf("k4", "k5"), maxOperations = 3)
        assertEquals(listOf("k1", "k2"), doomed)
    }

    @Test
    fun `보호 대상이 오래된 쪽에 섞여 있어도 건너뛰고 채운다`() {
        val ops = (1..6).map { op("k$it", it.toLong()) }
        val doomed = TrashPruneSelector.selectOverflow(ops, setOf("k1", "k3"), maxOperations = 3)
        assertEquals(listOf("k2", "k4", "k5"), doomed)
    }

    @Test
    fun `비보호 후보가 초과분보다 적으면 있는 만큼만 지운다`() {
        // 한도를 넘겨도 지울 수 있는 것이 없으면 그대로 둔다 — 한도보다 보존이 우선이다.
        // 남은 초과분은 다음 삭제 작업의 정리에서 소진된다(R-3).
        val ops = (1..3).map { op("k$it", it.toLong()) }
        val doomed = TrashPruneSelector.selectOverflow(ops, setOf("k2", "k3"), maxOperations = 0)
        assertEquals(listOf("k1"), doomed)
    }

    @Test
    fun `한도 이내면 아무것도 지우지 않는다`() {
        val ops = (1..3).map { op("k$it", it.toLong()) }
        assertTrue(TrashPruneSelector.selectOverflow(ops, emptySet(), maxOperations = 3).isEmpty())
        assertTrue(TrashPruneSelector.selectOverflow(ops, emptySet(), maxOperations = 10).isEmpty())
    }

    @Test
    fun `보호된 작업도 한도 계산에는 들어간다`() {
        // 보호 때문에 못 지운 몫을 다른 작업에 떠넘기지 않는다 — 그러면 보호 하나가
        // 남의 백업 하나를 대신 태운다.
        val ops = listOf(op("a", 1L), op("b", 2L), op("mine", 3L))
        val doomed = TrashPruneSelector.selectOverflow(ops, setOf("mine"), maxOperations = 2)
        assertEquals("초과분은 1건뿐이다", listOf("a"), doomed)
    }

    // ── 기한 초과 ──

    @Test
    fun `기한을 넘긴 작업만 지우고 방금 만든 작업은 보호한다`() {
        val ops = listOf(op("old", 100L), op("fresh", 5_000L), op("mine", 50L))
        val doomed = TrashPruneSelector.selectExpired(ops, threshold = 1_000L, protectedKeys = setOf("mine"))
        assertEquals(listOf("old"), doomed)
    }

    @Test
    fun `작업의 시각은 가장 최근 스냅샷 기준이다`() {
        // Operation.newestAt이 MAX(deletedAt)이라는 계약. MIN을 쓰면 오래 걸린 대량 삭제가
        // 만들어지자마자 절반이 기한 초과로 판정돼 그 자리에서 소각된다.
        val slowBulkDelete = op("bulk", at = 1_500L, count = 500)
        val doomed = TrashPruneSelector.selectExpired(
            listOf(slowBulkDelete), threshold = 1_000L, protectedKeys = emptySet()
        )
        assertTrue("가장 최근 스냅샷이 기한 안이면 묶음 전체가 살아야 한다", doomed.isEmpty())
    }

    @Test
    fun `경계값은 기한 초과가 아니다`() {
        val ops = listOf(op("exact", 1_000L))
        assertTrue(TrashPruneSelector.selectExpired(ops, 1_000L, emptySet()).isEmpty())
        assertEquals(listOf("exact"), TrashPruneSelector.selectExpired(ops, 1_001L, emptySet()))
    }
}
