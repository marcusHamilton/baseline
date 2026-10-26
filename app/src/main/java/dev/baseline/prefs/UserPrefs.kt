package dev.baseline.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.baseline.scores.StrainCalibration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

enum class SyncInterval { HOURLY, THREE_HOURS, MANUAL }

enum class BanisterSet(val a: Double, val b: Double) {
    MALE_FITTED(0.64, 1.92),
    FEMALE_FITTED(0.86, 1.67),
}

val DEFAULT_BASELINE_ORDER = listOf("hrv", "rhr", "sleep", "resp", "bb", "stress", "steps")

class UserPrefs(private val store: DataStore<Preferences>) {
    val baselineOrder: Flow<List<String>> = store.data.map { p ->
        p[ORDER]?.split(',')?.filter { it.isNotEmpty() } ?: DEFAULT_BASELINE_ORDER
    }
    val hiddenRows: Flow<Set<String>> = store.data.map { it[HIDDEN] ?: emptySet() }
    val syncInterval: Flow<SyncInterval> = store.data.map { p ->
        p[SYNC]?.let { n -> SyncInterval.entries.firstOrNull { it.name == n } } ?: SyncInterval.HOURLY
    }
    val banister: Flow<BanisterSet> = store.data.map { p ->
        p[BANISTER]?.let { n -> BanisterSet.entries.firstOrNull { it.name == n } } ?: BanisterSet.MALE_FITTED
    }

    /** Null until the first calibration; tau and version are written together. */
    val strainCalibration: Flow<StrainCalibration?> = store.data.map { p ->
        val tau = p[TAU]; val version = p[TAU_VERSION]
        if (tau != null && version != null) StrainCalibration(tau, version, p[TAU_PROVISIONAL] ?: false) else null
    }
    val strainCalibratedOn: Flow<LocalDate?> = store.data.map { p -> p[TAU_DATE]?.let(LocalDate::parse) }

    /** When a sync run last completed; null before the first one. */
    val lastSyncedAt: Flow<Instant?> = store.data.map { p -> p[SYNCED_AT]?.let(Instant::ofEpochMilli) }

    /** Set before a history re-score and cleared once it succeeded; still set at the next sync means it was cut short. */
    val recalcPending: Flow<Boolean> = store.data.map { it[RECALC_PENDING] ?: false }

    val notificationsAsked: Flow<Boolean> = store.data.map { it[ASKED] ?: false }

    /** Already-encrypted JWT bytes; the caller owns encryption (see ApiKeyVault's cipher functions). */
    val cachedJwt: Flow<ByteArray?> = store.data.map { p -> p[JWT]?.let { Base64.getDecoder().decode(it) } }

    suspend fun setBaselineOrder(order: List<String>) {
        store.edit { it[ORDER] = order.joinToString(",") }
    }

    suspend fun setHiddenRows(rows: Set<String>) {
        store.edit { it[HIDDEN] = rows }
    }

    suspend fun setSyncInterval(value: SyncInterval) {
        store.edit { it[SYNC] = value.name }
    }

    suspend fun setBanister(value: BanisterSet) {
        store.edit { it[BANISTER] = value.name }
    }

    suspend fun setStrainCalibration(cal: StrainCalibration, on: LocalDate) {
        store.edit { it[TAU] = cal.tau; it[TAU_VERSION] = cal.version; it[TAU_PROVISIONAL] = cal.provisional; it[TAU_DATE] = on.toString() }
    }

    suspend fun setLastSyncedAt(at: Instant) {
        store.edit { it[SYNCED_AT] = at.toEpochMilli() }
    }

    suspend fun setRecalcPending(pending: Boolean) {
        store.edit { it[RECALC_PENDING] = pending }
    }

    suspend fun setNotificationsAsked() {
        store.edit { it[ASKED] = true }
    }

    suspend fun setCachedJwt(bytes: ByteArray?) {
        store.edit {
            if (bytes == null) it.remove(JWT) else it[JWT] = Base64.getEncoder().encodeToString(bytes)
        }
    }

    private companion object {
        val ORDER = stringPreferencesKey("baseline_order")
        val HIDDEN = stringSetPreferencesKey("hidden_rows")
        val SYNC = stringPreferencesKey("sync_interval")
        val BANISTER = stringPreferencesKey("banister")
        val TAU = doublePreferencesKey("strain_tau")
        val TAU_VERSION = intPreferencesKey("strain_tau_version")
        val TAU_PROVISIONAL = booleanPreferencesKey("strain_tau_provisional")
        val TAU_DATE = stringPreferencesKey("strain_tau_date")
        val SYNCED_AT = longPreferencesKey("last_synced_at")
        val RECALC_PENDING = booleanPreferencesKey("recalc_pending")
        val ASKED = booleanPreferencesKey("notifications_asked")
        val JWT = stringPreferencesKey("cached_jwt_enc")
    }
}
