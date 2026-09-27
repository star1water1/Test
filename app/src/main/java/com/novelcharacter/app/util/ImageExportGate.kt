package com.novelcharacter.app.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Acquire before a Room transaction: export and reversible image deletion cannot interleave. */
object ImageExportGate {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
