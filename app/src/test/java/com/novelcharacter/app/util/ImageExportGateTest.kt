package com.novelcharacter.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageExportGateTest {
    @Test fun deletionWaitsUntilImagePackagingFinishes() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { ImageExportGate.run { order.add("snapshot"); release.await(); order.add("packaged") } }
        runCurrent()
        launch { ImageExportGate.run { order.add("deleted") } }
        runCurrent()
        assertEquals(listOf("snapshot"), order)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("snapshot", "packaged", "deleted"), order)
    }
    @Test fun cancelledExportReleasesImageGate() = runTest {
        val export = launch { ImageExportGate.run { CompletableDeferred<Unit>().await() } }
        runCurrent()
        export.cancel()
        var deleted = false
        launch { ImageExportGate.run { deleted = true } }
        advanceUntilIdle()
        assertTrue(deleted)
    }
}
