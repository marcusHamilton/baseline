package dev.baseline.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/**
 * Owns the coroutine scopes a test would otherwise leak: the DataStore scope and the scope of every ViewModel the test
 * builds directly (nothing clears those, so their `stateIn` collectors would keep reading Room after `db.close()`, and
 * Room's invalidation tracker then reopens an empty in-memory database and throws "no such table" on a background thread).
 * Call [close] first in tearDown, before `db.close()`.
 */
class ScopeCleanup {
    /** Pass as `PreferenceDataStoreFactory.create(scope = ...)`. */
    val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = mutableListOf<ViewModel>()

    fun <T : ViewModel> track(vm: T): T = vm.also { synchronized(viewModels) { viewModels += it } }

    suspend fun close() {
        val all = synchronized(viewModels) { viewModels.toList().also { viewModels.clear() } }
        all.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        storeScope.coroutineContext.job.cancelAndJoin()
    }
}
