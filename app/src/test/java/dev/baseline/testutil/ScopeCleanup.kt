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
 * builds directly (nothing clears those, so their `stateIn` collectors would keep reading Room after `db.close()` and
 * throw on a background thread, failing a later test). Call [close] first in tearDown, before `db.close()`. A "no such
 * table" mid-test is a different trap: see [inMemoryDatabase].
 */
class ScopeCleanup {
    /** Pass as `PreferenceDataStoreFactory.create(scope = ...)`. */
    val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = mutableListOf<ViewModel>()

    fun <T : ViewModel> track(vm: T): T = vm.also { synchronized(viewModels) { viewModels += it } }

    suspend fun close() {
        val all = synchronized(viewModels) { viewModels.toList().also { viewModels.clear() } }
        all.forEach {
            it.viewModelScope.coroutineContext.job
                .cancelAndJoin()
        }
        storeScope.coroutineContext.job.cancelAndJoin()
    }
}
