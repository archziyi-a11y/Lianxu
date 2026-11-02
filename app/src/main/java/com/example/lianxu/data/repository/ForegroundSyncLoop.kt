package com.example.lianxu.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Called only inside a STARTED lifecycle. Background retries remain WorkManager's responsibility. */
class ForegroundSyncLoop(private val intervalMs: Long = 5_000, private val sync: suspend () -> Unit) {
    init { require(intervalMs >= 100) }
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            try { sync() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            delay(intervalMs)
        }
    }
}
