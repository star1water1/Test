package com.novelcharacter.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RetainedWriteTest {
    @Test fun destroyedCallerCannotUnlockOrRepeatWriteAndReplacementConsumesSuccessOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val states = mutableListOf<Boolean>()
        val retained = RetainedWrite<Long>(backgroundScope) { states.add(it) }
        var inserts = 0
        val caller = launch {
            assertTrue(retained.start { gate.await(); inserts++; 7L })
        }
        runCurrent()
        caller.cancel()
        assertTrue(retained.isRunning)
        assertFalse(retained.start { inserts++; 8L })
        gate.complete(Unit)
        runCurrent()
        assertFalse(retained.isRunning)
        assertFalse(retained.start { 9L }) // success awaits delivery, even while detached
        assertEquals(7L, retained.consume()!!.getOrThrow())
        assertNull(retained.consume())
        assertEquals(1, inserts)
        assertEquals(listOf(true, false), states)
    }

    @Test fun failureIsDeliveredOnceAndAllowsExplicitRetry() = runTest {
        val retained = RetainedWrite<Int>(backgroundScope) {}
        assertTrue(retained.start { error("write failed") })
        runCurrent()
        assertFalse(retained.isRunning)
        assertTrue(retained.consume()!!.isFailure)
        assertTrue(retained.start { 2 })
        runCurrent()
        assertEquals(2, retained.consume()!!.getOrThrow())
    }

    @Test fun owningScopeCancellationReleasesLockWithoutReportingSaveSuccess() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler))
        val retained = RetainedWrite<Int>(scope) {}
        retained.start { CompletableDeferred<Unit>().await(); 1 }
        runCurrent()
        scope.cancel()
        runCurrent()
        assertFalse(retained.isRunning)
        assertNull(retained.consume())
    }
}
