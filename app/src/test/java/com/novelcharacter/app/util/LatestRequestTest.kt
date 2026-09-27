package com.novelcharacter.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LatestRequestTest {
    @Test fun deselectingCriterionInvalidatesPendingRankingWithoutAnotherRequest() = runTest {
        val requests = LatestRequest(backgroundScope)
        val pending = CompletableDeferred<Unit>()
        var published = false
        requests.launch(load = { withContext(NonCancellable) { pending.await() }; "old ranking" },
            publish = { published = true }, onError = { throw it })
        runCurrent()
        requests.cancel()
        pending.complete(Unit)
        runCurrent()
        assertFalse(published)
    }
    @Test fun olderNonCooperativeCalculationCannotReplaceNewRanking() = runTest {
        val requests = LatestRequest(backgroundScope)
        val old = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        requests.launch(load = { withContext(NonCancellable) { old.await() }; "field descending" },
            publish = { published.add(it) }, onError = { throw it })
        runCurrent()
        requests.launch(load = { "duel ascending" }, publish = { published.add(it) }, onError = { throw it })
        runCurrent()
        old.complete(Unit)
        runCurrent()
        assertEquals(listOf("duel ascending"), published)
    }

    @Test fun obsoleteErrorsDoNotReplaceLatestEmptyResult() = runTest {
        val requests = LatestRequest(backgroundScope)
        val old = CompletableDeferred<Unit>()
        var errors = 0
        var result = "initial"
        requests.launch<String>(load = { withContext(NonCancellable) { old.await(); error("old error") } },
            publish = { result = it }, onError = { errors++ })
        runCurrent()
        requests.launch(load = { "empty" }, publish = { result = it }, onError = { errors++ })
        runCurrent()
        old.complete(Unit)
        runCurrent()
        assertEquals("empty", result)
        assertEquals(0, errors)
    }
}
