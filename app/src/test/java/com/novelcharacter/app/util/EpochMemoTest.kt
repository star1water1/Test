package com.novelcharacter.app.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** EpochMemo 무효화 계약 — 메모 캐시의 정확성(변수 제어)을 지키는 핵심 회귀 가드. */
class EpochMemoTest {

    @Test
    fun sameKeySameEpoch_computesOnce() = runTest {
        var calls = 0
        val memo = EpochMemo<String, Int> { calls++; it.length }
        assertEquals(3, memo.get("abc", 0))
        assertEquals(3, memo.get("abc", 0))
        assertEquals(1, calls)
    }

    @Test
    fun epochChange_recomputes() = runTest {
        var calls = 0
        val memo = EpochMemo<String, Int> { calls++; it.length }
        memo.get("abc", 0)
        memo.get("abc", 1)
        assertEquals(2, calls)
    }

    @Test
    fun keyChange_recomputes_thenCachesNewKey() = runTest {
        var calls = 0
        val memo = EpochMemo<String, Int> { calls++; it.length }
        memo.get("abc", 0)
        assertEquals(4, memo.get("abcd", 0))
        assertEquals(2, calls)
        memo.get("abcd", 0)
        assertEquals(2, calls) // 새 키가 캐시됨
    }

    @Test
    fun invalidate_forcesRecompute() = runTest {
        var calls = 0
        val memo = EpochMemo<String, Int> { calls++; it.length }
        memo.get("abc", 0)
        memo.invalidate()
        memo.get("abc", 0)
        assertEquals(2, calls)
    }

    @Test
    fun epochBump_yieldsFreshValue_notStale() = runTest {
        var data = 10
        val memo = EpochMemo<String, Int> { data }
        assertEquals(10, memo.get("k", 0))
        data = 20
        assertEquals(10, memo.get("k", 0)) // 같은 (키,에폭) → 캐시(구값)
        assertEquals(20, memo.get("k", 1)) // 에폭 상승 → 최신값(stale 아님)
    }
}
