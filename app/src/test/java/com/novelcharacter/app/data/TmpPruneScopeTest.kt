package com.novelcharacter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 임시 검증용 — TrashRepository.pruneIfNeeded(HEAD 731~741행)의 선택 로직을 그대로 옮겨,
 * "보호 목록이 인스턴스 단위"라는 전제 아래 임포트 경로에서 몇 건이 태워지는지 센다.
 * (TrashRepository 자체는 Room 런타임 의존이라 순수 JVM에서 직접 구동 불가)
 */
class TmpPruneScopeTest {

    private data class Snap(val id: Long, val deletedAt: Long, val origin: String)

    /** 한 TrashRepository 인스턴스 — createdSnapshotIds가 인스턴스 필드인 점을 그대로 모사 */
    private class Repo(val store: MutableList<Snap>) {
        val createdSnapshotIds = mutableSetOf<Long>()
        var nextId = 1L
        fun snapshot(origin: String, at: Long): Long {
            val id = nextId++
            store.add(Snap(id, at, origin))
            createdSnapshotIds.add(id)   // TrashRepository.kt:155
            return id
        }
        /** TrashRepository.kt:734-740 원문 그대로 */
        fun pruneIfNeeded(maxItems: Int): List<Snap> {
            val purged = mutableListOf<Snap>()
            val overflow = store.size - maxItems
            if (overflow > 0) {
                store.sortedBy { it.deletedAt }.take(overflow + createdSnapshotIds.size)
                    .filter { it.id !in createdSnapshotIds }
                    .take(overflow)
                    .forEach { purged.add(it); store.remove(it) }
            }
            return purged
        }
    }

    /** HEAD 배선: 이동 스냅샷은 캐릭터마다 새 인스턴스, prune은 삭제용 인스턴스로만 */
    @Test
    fun headWiring_burnsMoveSnapshots() {
        val store = mutableListOf<Snap>()
        var clock = 1000L
        var idSeq = 1L
        // Phase 2 — 세계관 이동 40건: CharacterRepository.kt:437 `val trash = TrashRepository(db)`
        repeat(40) {
            val per = Repo(store); per.nextId = idSeq
            per.snapshot("move", clock++)
            idSeq = per.nextId
        }
        // Phase 5 — 삭제 1건: ExcelImportService.kt:5175-5177 (이 인스턴스만 trashForPrune에 담긴다)
        val deleteRepo = Repo(store); deleteRepo.nextId = idSeq
        deleteRepo.snapshot("delete", clock++)

        assertEquals(41, store.size)
        val purged = deleteRepo.pruneIfNeeded(30)   // ExcelImportService.kt:480-484
        assertEquals(11, purged.size)
        assertTrue(purged.all { it.origin == "move" })
        assertEquals(30, store.size)
    }

    /** 대조군: 같은 인스턴스를 공유하면 41건 전부 보호되어 한 건도 안 태운다 */
    @Test
    fun sharedInstance_protectsAll() {
        val store = mutableListOf<Snap>()
        val shared = Repo(store)
        var clock = 1000L
        repeat(40) { shared.snapshot("move", clock++) }
        shared.snapshot("delete", clock++)

        assertEquals(41, store.size)
        val purged = shared.pruneIfNeeded(30)
        assertEquals(0, purged.size)
        assertEquals(41, store.size)
    }
}
