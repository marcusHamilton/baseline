package dev.baseline.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminResult
import dev.baseline.garmin.KeyTest
import dev.baseline.garmin.KeyStatus
import dev.baseline.garmin.saveAndTest
import dev.baseline.prefs.BanisterSet
import dev.baseline.prefs.SyncInterval
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.HistoryRecalculator
import dev.baseline.sync.SyncScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

/** Earliest stored health day and activity day; null while there are none. */
data class HistoryCoverage(val healthSince: LocalDate?, val activitiesSince: LocalDate?)

sealed interface KeyEntryState {
    data object Idle : KeyEntryState
    data object Testing : KeyEntryState
    data class Error(val message: String) : KeyEntryState
    data object Saved : KeyEntryState
}

/** [keyStatus] is null until the vault first answers. */
data class SettingsUiState(
    val keyStatus: KeyStatus?,
    val interval: SyncInterval,
    val banister: BanisterSet,
    val lastSynced: Instant?,
    val coverage: HistoryCoverage,
    val imperial: Boolean,
    /** A history re-score is running (after a coefficient change or "Recalculate history"). */
    val recalculating: Boolean,
    /** Sync tasks that ended FAILED; "Retry failed" shows while this is above 0. */
    val failedCount: Int = 0,
    /** The failed tasks are being re-queued. */
    val retrying: Boolean = false,
)

/**
 * Settings. [testKey] is `GarminClient.testKey` and [clearToken] `TokenProvider.clear`; re-scoring goes through [recalculator], which outlives this screen;
 * [coverage] and [imperial] come from the Repository; [failedCount] and [requeueFailed] are `TaskDao.failedCount` / `requeueAllFailed`.
 */
class SettingsViewModel(
    private val vault: ApiKeyVault,
    private val prefs: UserPrefs,
    private val scheduler: SyncScheduler,
    private val testKey: KeyTest,
    private val clearToken: suspend () -> Unit,
    private val recalculator: HistoryRecalculator,
    coverage: Flow<HistoryCoverage>,
    imperial: Flow<Boolean>,
    failedCount: Flow<Int>,
    private val requeueFailed: suspend () -> Unit,
) : ViewModel() {
    private val _entry = MutableStateFlow<KeyEntryState>(KeyEntryState.Idle)
    val entry: StateFlow<KeyEntryState> = _entry

    private val recalculating = recalculator.running
    private val retrying = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        combine(vault.status, prefs.syncInterval, prefs.banister, ::Triple),
        combine(prefs.lastSyncedAt, coverage, imperial, recalculating, failedCount, retrying) { arrayOf(it[0], it[1], it[2], it[3], it[4], it[5]) },
    ) { (status, interval, banister), rest ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(status, interval, banister, rest[0] as Instant?, rest[1] as HistoryCoverage, rest[2] as Boolean, rest[3] as Boolean, rest[4] as Int, rest[5] as Boolean)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(null, SyncInterval.HOURLY, BanisterSet.MALE_FITTED, null, HistoryCoverage(null, null), false, false),
    )

    /** Same test-then-save as onboarding; a rejected key leaves the current one in place. Sync resumes once the new key works. */
    fun replaceKey(key: String, lifetimeDays: Int) {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || _entry.value == KeyEntryState.Testing) return
        _entry.value = KeyEntryState.Testing
        viewModelScope.launch {
            val failure = vault.saveAndTest(trimmed, lifetimeDays, testKey, clearToken)
            if (failure == null) {
                _entry.value = KeyEntryState.Saved
                scheduler.syncNow()
            } else {
                _entry.value = KeyEntryState.Error(failure)
            }
        }
    }

    /** Hides the saved/error line once the user starts typing again. */
    fun clearEntry() { if (_entry.value !is KeyEntryState.Testing) _entry.value = KeyEntryState.Idle }

    fun setInterval(interval: SyncInterval) {
        viewModelScope.launch {
            prefs.setSyncInterval(interval)
            scheduler.apply(interval)
        }
    }

    private val banisterLock = Mutex()

    /** Scores depend on the coefficients, so the whole history is re-scored with the new set. */
    fun setBanister(set: BanisterSet): Job =
        viewModelScope.launch {
            // Check and write as one step: two quick taps must not interleave and leave the first choice saved.
            banisterLock.withLock {
                if (prefs.banister.first() == set) return@launch
                prefs.setBanister(set)
                recalculator.request()
            }
        }

    fun recalculateHistory(): Job = viewModelScope.launch { recalculator.request() }

    /** Re-queues every FAILED task, then asks for a sync; the button stays disabled until both are done. */
    fun retryFailed() {
        if (!retrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                // Leaving Settings between the two steps would strand the tasks PENDING with no run queued.
                withContext(NonCancellable) {
                    requeueFailed()
                    scheduler.syncNow()
                }
            } finally {
                retrying.value = false
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch { scheduler.syncNow() }
    }
}
