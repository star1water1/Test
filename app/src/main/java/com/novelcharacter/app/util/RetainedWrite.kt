package com.novelcharacter.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One write owned by a surviving scope, with a result consumed by the current screen. */
class RetainedWrite<T>(
    private val scope: CoroutineScope,
    private val onRunningChanged: (Boolean) -> Unit
) {
    private val pending = MutableStateFlow<Result<T>?>(null)
    val result: StateFlow<Result<T>?> = pending
    var isRunning: Boolean = false
        private set

    fun start(block: suspend () -> T): Boolean {
        if (isRunning || pending.value != null) return false
        isRunning = true
        onRunningChanged(true)
        scope.launch {
            try {
                pending.value = Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending.value = Result.failure(e)
            } finally {
                isRunning = false
                onRunningChanged(false)
            }
        }
        return true
    }

    fun consume(): Result<T>? = pending.value.also { pending.value = null }
}
