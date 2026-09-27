package com.novelcharacter.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Requests sharing a result slot also share cancellation and publication order. */
class LatestRequest(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0L

    fun <T> launch(load: suspend () -> T, publish: (T) -> Unit, onError: (Exception) -> Unit) {
        val request = ++generation
        job?.cancel()
        job = scope.launch {
            try {
                val value = load()
                if (request == generation) publish(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == generation) onError(e)
            }
        }
    }
}
