package dev.baseline.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Where an offered release is in the one-tap flow. Stored by name. */
enum class Phase { AVAILABLE, NEEDS_PERMISSION, DOWNLOADING, READY, INSTALLING, FAILED }

/** Everything the updater keeps between runs; [release] is null when nothing newer is on offer. */
data class UpdateRecord(
    val autoCheck: Boolean = true,
    val etag: String? = null,
    val checkedAt: Instant? = null,
    val checkError: String? = null,
    val release: Release? = null,
    val phase: Phase = Phase.AVAILABLE,
    val failure: String? = null,
    val sessionId: Int? = null,
    val dismissedTag: String? = null,
)

/** The updater's state in the app's DataStore (spec: in-app updates §4). No Room table: nothing here is history. */
class UpdatePrefs(private val store: DataStore<Preferences>) {
    val record: Flow<UpdateRecord> = store.data.map { p ->
        val release =
            p[TAG]?.let { tag ->
                Release(tag, p[NOTES].orEmpty(), p[PAGE].orEmpty(), p[APK_URL] ?: return@let null, p[APK_SIZE] ?: return@let null, p[SHA])
            }
        UpdateRecord(
            autoCheck = p[AUTO] ?: true,
            etag = p[ETAG],
            checkedAt = p[CHECKED_AT]?.let(Instant::ofEpochMilli),
            checkError = p[CHECK_ERROR],
            release = release,
            phase = p[PHASE]?.let { n -> Phase.entries.firstOrNull { it.name == n } } ?: Phase.AVAILABLE,
            failure = p[FAILURE],
            sessionId = p[SESSION],
            dismissedTag = p[DISMISSED],
        )
    }

    suspend fun current(): UpdateRecord = record.first()

    suspend fun setAutoCheck(on: Boolean) {
        store.edit { it[AUTO] = on }
    }

    /** One finished check. [etag] is kept only with a usable answer, so a bad one is fetched in full next time. */
    suspend fun checked(at: Instant, error: String?, etag: String? = null) {
        store.edit { p ->
            p[CHECKED_AT] = at.toEpochMilli()
            if (error == null) p.remove(CHECK_ERROR) else p[CHECK_ERROR] = error
            if (etag != null) p[ETAG] = etag
        }
    }

    /** Offers [release]. The same release again keeps its progress; another tag, or a re-uploaded APK, starts over at AVAILABLE. */
    suspend fun offer(release: Release) {
        store.edit { p ->
            val sameRelease = p[TAG] == release.tag && p[APK_URL] == release.apkUrl && p[APK_SIZE] == release.apkSize && p[SHA] == release.sha256
            p[TAG] = release.tag
            p[NOTES] = release.notes
            p[PAGE] = release.pageUrl
            p[APK_URL] = release.apkUrl
            p[APK_SIZE] = release.apkSize
            if (release.sha256 == null) p.remove(SHA) else p[SHA] = release.sha256
            if (!sameRelease) p.resetPhase()
        }
    }

    /** Nothing newer is on offer (the check said so, or the update was installed). */
    suspend fun clearRelease() {
        store.edit { p ->
            listOf(TAG, NOTES, PAGE, APK_URL, SHA, DISMISSED).forEach { p.remove(it) }
            p.remove(APK_SIZE)
            p.resetPhase()
        }
    }

    suspend fun setPhase(phase: Phase, failure: String? = null, sessionId: Int? = null) {
        store.edit { p ->
            p[PHASE] = phase.name
            if (failure == null) p.remove(FAILURE) else p[FAILURE] = failure
            if (sessionId == null) p.remove(SESSION) else p[SESSION] = sessionId
        }
    }

    suspend fun dismiss(tag: String) {
        store.edit { it[DISMISSED] = tag }
    }

    private fun MutablePreferences.resetPhase() {
        remove(PHASE)
        remove(FAILURE)
        remove(SESSION)
    }

    private companion object {
        val AUTO = booleanPreferencesKey("update_auto_check")
        val ETAG = stringPreferencesKey("update_etag")
        val CHECKED_AT = longPreferencesKey("update_checked_at")
        val CHECK_ERROR = stringPreferencesKey("update_check_error")
        val TAG = stringPreferencesKey("update_tag")
        val NOTES = stringPreferencesKey("update_notes")
        val PAGE = stringPreferencesKey("update_page")
        val APK_URL = stringPreferencesKey("update_apk_url")
        val APK_SIZE = longPreferencesKey("update_apk_size")
        val SHA = stringPreferencesKey("update_sha256")
        val PHASE = stringPreferencesKey("update_phase")
        val FAILURE = stringPreferencesKey("update_failure")
        val SESSION = intPreferencesKey("update_session")
        val DISMISSED = stringPreferencesKey("update_dismissed_tag")
    }
}
