package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 왕복 장부 계약 — 상한이 있고, 오래된 것부터 버리고, 개명 연쇄를 접는다.
 * 장부는 캐시라 깨진 줄을 버려도 유실이 아니다(한 번 더 일할 뿐).
 */
class FolderRoundtripLedgerTest {

    // ── 지문 목록 ──

    @Test fun appendBounded_keepsOrderAndDropsOldestOverCap() {
        val result = FolderRoundtripLedger.appendBounded(listOf("a", "b", "c"), listOf("d"), cap = 3)
        assertEquals(listOf("b", "c", "d"), result)
    }

    @Test fun appendBounded_movesExistingToEnd() {
        // 계속 이동에 실패하는 파일이 상한 압박에 밀려 사라지면 다시 중복 편입된다.
        val result = FolderRoundtripLedger.appendBounded(listOf("a", "b", "c"), listOf("a"), cap = 3)
        assertEquals(listOf("b", "c", "a"), result)
    }

    @Test fun appendBounded_dedupesWithinBatch() {
        val result = FolderRoundtripLedger.appendBounded(emptyList(), listOf("a", "a", "b"), cap = 10)
        assertEquals(listOf("a", "b"), result)
    }

    @Test fun fingerprint_variesWithEveryField() {
        val base = FolderRoundtripLedger.fingerprintOf("가온/a.jpg", 100, 1000)
        assertTrue(base != FolderRoundtripLedger.fingerprintOf("가온/b.jpg", 100, 1000))
        assertTrue(base != FolderRoundtripLedger.fingerprintOf("가온/a.jpg", 101, 1000))
        assertTrue(base != FolderRoundtripLedger.fingerprintOf("가온/a.jpg", 100, 1001))
    }

    @Test fun fingerprint_stripsSeparatorsFromPath() {
        val fp = FolderRoundtripLedger.fingerprintOf("가온\n하위\tx.jpg", 1, 2)
        assertTrue(fp.none { it == '\n' || it == '\t' })
    }

    // ── 개명 별칭 ──

    @Test fun putRename_collapsesChains() {
        var map = FolderRoundtripLedger.putRenameBounded(emptyMap(), "v1", "v2")
        map = FolderRoundtripLedger.putRenameBounded(map, "v2", "v3")
        // v1 → v3 로 접혀야 항목이 늘지 않고 조회가 한 번에 끝난다.
        assertEquals("v3", map["v1"])
        assertEquals("v3", map["v2"])
        assertEquals(2, map.size)
    }

    @Test fun putRename_ignoresSelfMapping() {
        val map = FolderRoundtripLedger.putRenameBounded(emptyMap(), "same", "same")
        assertTrue(map.isEmpty())
    }

    @Test fun putRename_dropsOldestOverCap() {
        var map = LinkedHashMap<String, String>()
        for (i in 1..5) map = FolderRoundtripLedger.putRenameBounded(map, "old$i", "new$i", cap = 3)
        assertEquals(listOf("old3", "old4", "old5"), map.keys.toList())
    }

    @Test fun putRename_reRecordMovesKeyToEnd() {
        var map = FolderRoundtripLedger.putRenameBounded(emptyMap(), "a", "a2")
        map = FolderRoundtripLedger.putRenameBounded(map, "b", "b2")
        map = FolderRoundtripLedger.putRenameBounded(map, "a", "a3")
        assertEquals(listOf("b", "a"), map.keys.toList())
        assertEquals("a3", map["a"])
    }

    // ── 직렬화 ──

    @Test fun listRoundTrips() {
        val items = listOf("가온/a.jpg|1|2", "b.jpg|3|4")
        assertEquals(items, FolderRoundtripLedger.decodeList(FolderRoundtripLedger.encodeList(items)))
        assertTrue(FolderRoundtripLedger.decodeList(null).isEmpty())
        assertTrue(FolderRoundtripLedger.decodeList("").isEmpty())
    }

    @Test fun mapRoundTripsPreservingOrder() {
        val map = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val decoded = FolderRoundtripLedger.decodeMap(FolderRoundtripLedger.encodeMap(map))
        assertEquals(map, decoded)
        assertEquals(listOf("a", "b", "c"), decoded.keys.toList())
    }

    @Test fun mapDecode_skipsMalformedLines() {
        val decoded = FolderRoundtripLedger.decodeMap("a\t1\n깨진줄\n\tb\nc\t")
        assertEquals(mapOf("a" to "1"), decoded)
    }
}
