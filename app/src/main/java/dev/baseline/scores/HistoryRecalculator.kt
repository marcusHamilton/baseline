package dev.baseline.scores

import android.util.Log
import dev.baseline.prefs.UserPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs "re-score the whole history" requests in an app-level [scope], so leaving Settings doesn't cancel a run. A request
 * that arrives mid-run triggers exactly one more run. [UserPrefs.recalcPending] is set before the first run and cleared
 * after a successful one, so a run cut short by process death is redone at the next sync.
 */
class HistoryRecalculator(
    scope: CoroutineScope,
    private val prefs: UserPrefs,
    private val recalculate: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    // Guards "is there another request?" + "clear pending, stop running" against request(), so a request landing in
    // that window is either seen by the loop or sets pending/running after it.
    private val gate = Mutex()

    init {
        scope.launch {
            for (ignored in requests) {
                _running.value = true
                try {
                    do {
                        recalculate()
                    } while (finishRun())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not recalculate history: ${e.javaClass.name}: ${e.message}") // pending stays set: the next sync redoes it
                    withContext(NonCancellable) { gate.withLock { _running.value = false } }
                }
            }
        }
    }

    /** True when another request arrived during the run (go again); otherwise clears pending and running. */
    private suspend fun finishRun(): Boolean = withContext(NonCancellable) {
        gate.withLock {
            if (requests.tryReceive().isSuccess) {
                true
            } else {
                prefs.setRecalcPending(false)
                _running.value = false
                false
            }
        }
    }

    suspend fun request() {
        withContext(NonCancellable) {
            gate.withLock {
                prefs.setRecalcPending(true)
                _running.value = true
                requests.trySend(Unit)
            }
        }
    }

    /** Queues a run when an earlier one was cut short and none is in flight. Returns whether it did; wait on [running] for the end. */
    suspend fun redoIfPending(): Boolean = withContext(NonCancellable) {
        gate.withLock {
            val redo = prefs.recalcPending.first() && !_running.value
            if (redo) {
                _running.value = true
                requests.trySend(Unit)
            }
            redo
        }
    }

    private companion object {
        const val TAG = "HistoryRecalculator"
    }
}
