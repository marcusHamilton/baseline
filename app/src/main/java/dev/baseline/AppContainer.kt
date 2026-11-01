package dev.baseline

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.baseline.ai.AiKeyEntry
import dev.baseline.ai.AiKeyVault
import dev.baseline.ai.AiPrefs
import dev.baseline.ai.applyAiDefaults
import dev.baseline.ai.context.ContextPacks
import dev.baseline.ai.context.HealthTools
import dev.baseline.ai.engine.CloudEngine
import dev.baseline.ai.engine.MlKitNanoBackend
import dev.baseline.ai.engine.NanoBackend
import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.ai.insight.InsightService
import dev.baseline.data.Repository
import dev.baseline.data.db.AppDatabase
import dev.baseline.fit.FitIngest
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminAuthenticator
import dev.baseline.garmin.GarminClient
import dev.baseline.garmin.KeyStatus
import dev.baseline.garmin.KeyTest
import dev.baseline.garmin.KeystoreAead
import dev.baseline.garmin.TokenProvider
import dev.baseline.net.onlineFlow
import dev.baseline.prefs.SyncInterval
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.AfterSync
import dev.baseline.scores.HistoryRecalculator
import dev.baseline.scores.ScorePipeline
import dev.baseline.sync.RawIngest
import dev.baseline.sync.SyncEngine
import dev.baseline.sync.SyncScheduler
import dev.baseline.sync.SyncWorkerDeps
import dev.baseline.update.AndroidUpdatePlatform
import dev.baseline.update.ReleaseSource
import dev.baseline.update.UpdatePrefs
import dev.baseline.update.Updater
import dev.baseline.update.updatesEnabled
import dev.baseline.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Where the in-app updater looks unless the build was made with -PupdatesUrl (tools/fake_garmin.py --release-apk). */
const val DEFAULT_UPDATES_URL = BuildConfig.DEFAULT_UPDATES_URL

private val Context.baselineStore: DataStore<Preferences> by preferencesDataStore(name = "baseline")

/** Manual DI root; later tasks add their dependencies here. */
open class AppContainer(context: Context, val clock: Clock = SystemZoneClock) {
    private val appContext = context.applicationContext

    open val dataStore: DataStore<Preferences> get() = appContext.baselineStore
    val aead by lazy { KeystoreAead() }
    val dateTicker = DateTicker(clock)
    val today get() = dateTicker.today

    // open: NavShellTest swaps in a keyed vault, an in-memory db and an offline engine.
    open val apiKeyVault by lazy { ApiKeyVault(dataStore, aead::encrypt, aead::decrypt, clock, dateTicker.today) }
    val userPrefs by lazy { UserPrefs(dataStore) }

    // open: tests drive the offline line without a real network.
    open val online: Flow<Boolean> by lazy { appContext.getSystemService(ConnectivityManager::class.java).onlineFlow() }
    open val db by lazy { AppDatabase.create(appContext) }

    // The real API unless the build was made with -PgarminBaseUrl (tools/fake_garmin.py).
    private val baseUrl = BuildConfig.GARMIN_BASE_URL.toHttpUrl()

    // callTimeout bounds the whole call (connect + retries + body): GarminClient holds its request lock across execute().
    // FIT downloads are single-digit-MB zips, so 120 s (~40 KB/s) still lets them finish on a poor connection; minting is tiny.
    private fun httpBuilder(callTimeoutSeconds: Long) = OkHttpClient
        .Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .apply { if (BuildConfig.DEBUG) addInterceptor(GarminClient.loggingInterceptor()) }

    // No Authenticator here: minting must not recurse into itself.
    private val mintHttp by lazy { httpBuilder(60).build() }
    val tokenProvider by lazy {
        TokenProvider(baseUrl, mintHttp, apiKeyVault, userPrefs, aead::encrypt, aead::decrypt, Clock.systemUTC())
    }
    val garminClient by lazy {
        GarminClient(baseUrl, httpBuilder(120).authenticator(GarminAuthenticator(tokenProvider)).build(), tokenProvider)
    }
    private val fitIngest by lazy { FitIngest(appContext.filesDir, db) }
    val rawIngest by lazy { RawIngest(db, fitIngest::ingest, clock) }

    // open: NavShellTest swaps in an offline test.
    open val testKey: KeyTest by lazy { garminClient::testKey }
    open val syncEngine by lazy { SyncEngine(garminClient::fetch, db, clock, rawIngest, ::delay) }
    val repository by lazy { Repository(db, userPrefs, apiKeyVault) }

    // open: NavShellTest swaps in a scheduler that doesn't hit the real WorkManager.
    open val scheduler by lazy { SyncScheduler(WorkManager.getInstance(appContext)) { db.taskDao().backfillPending() } }

    /** What the periodic job should be: the chosen interval, or off while there is no key. Re-emits only on change. */
    val scheduleInterval: Flow<SyncInterval> by lazy {
        combine(apiKeyVault.status.map { it is KeyStatus.Missing }, userPrefs.syncInterval) { missing, interval ->
            if (missing) SyncInterval.MANUAL else interval
        }.distinctUntilChanged()
    }

    val scorePipeline by lazy { ScorePipeline(db, userPrefs, clock) }

    /** Work that must outlive any screen; it lives as long as the process. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** For tests that build containers; the app itself never closes it. */
    fun close() = appScope.cancel()

    // GitHub, not Garmin: no Authenticator and no logging interceptor, so no Garmin credential can reach it (spec: in-app updates §4).
    internal val updateHttp by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    val updatePrefs by lazy { UpdatePrefs(dataStore) }

    // The AI provider, not Garmin or GitHub: no Authenticator, no interceptors (spec: AI assistant §4.2).
    internal val aiHttp by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // open: tests swap in a keyed vault and in-memory prefs.
    open val aiKeyVault by lazy { AiKeyVault(dataStore, aead::encrypt, aead::decrypt) }
    open val aiPrefs by lazy { AiPrefs(dataStore) }

    // open: tests swap in a fake; ML Kit can't load on the JVM.
    open val nanoBackend: NanoBackend by lazy { MlKitNanoBackend() }
    val nanoEngine by lazy { NanoEngine(nanoBackend, clock, dateTicker.zone) }

    val contextPacks by lazy { ContextPacks(db, repository, userPrefs, today, dateTicker.zone) }

    val aiKeyEntry by lazy {
        AiKeyEntry(aiHttp, aiKeyVault, aiPrefs, nanoSupported = {
            nanoEngine.status.value !is NanoStatus.Unavailable
        }, allowLoopbackHttp = BuildConfig.ALLOW_LOOPBACK_HTTP)
    }

    /** Checks Nano and settles the AI mode defaults (spec: AI assistant §3.1). Call once on app start; join the [Job] to wait for it. */
    fun startAi(): Job = appScope.launch { applyAiDefaults(nanoEngine, aiPrefs) }

    val healthTools by lazy { HealthTools(db, contextPacks, userPrefs, today) }

    // open: tests swap in a fake.
    open val cloudEngine by lazy { CloudEngine(aiHttp, aiKeyVault, healthTools, aiOnline::value) }

    val insightService by lazy { InsightService(contextPacks, nanoEngine, cloudEngine, db.aiInsightDao(), aiPrefs.mode, aiKeyVault.status, online, clock) }

    /** A synchronous "online" for callers that can't collect [online]; true until the first reading. */
    val aiOnline: StateFlow<Boolean> by lazy { online.stateIn(appScope, SharingStarted.Eagerly, true) }

    // open: tests swap in an updater with a fake platform.
    open val updater by lazy {
        val userAgent = "Baseline/${BuildConfig.VERSION_NAME}"
        Updater(
            updatePrefs,
            ReleaseSource(updateHttp, BuildConfig.UPDATES_URL, userAgent),
            updateHttp,
            AndroidUpdatePlatform(appContext) { WorkManager.getInstance(appContext) },
            clock,
            BuildConfig.VERSION_NAME,
            enabled = updatesEnabled(BuildConfig.UPDATES_URL, BuildConfig.DEBUG, BuildConfig.VERSION_NAME),
            appScope,
            userAgent,
        )
    }

    val historyRecalculator by lazy { HistoryRecalculator(appScope, userPrefs) { scorePipeline.recalibrateAndRescore(!db.taskDao().calibrationPending()) } }

    val afterSync by lazy {
        AfterSync(scorePipeline, historyRecalculator, { !db.taskDao().calibrationPending() }, { LocalDate.now(clock) })
    }
    open val widgetUpdater by lazy { WidgetUpdater(appContext) { this } }
    val widgetRefreshSignals: Flow<List<Any?>> by lazy {
        combine(
            listOf(
                userPrefs.baselineOrder,
                userPrefs.hiddenRows,
                repository.imperial,
                repository.firstDayOfWeek,
                apiKeyVault.status,
                historyRecalculator.running,
                dateTicker.today,
                dateTicker.zone,
                // Redraws when the worker really leaves RUNNING, whatever order the updates ran in.
                scheduler.workInfos.map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } },
            ),
        ) { it.toList() }.distinctUntilChanged()
    }
    val syncWorkerDeps by lazy {
        SyncWorkerDeps(
            syncEngine,
            clock,
            afterSync::invoke,
            userPrefs::setLastSyncedAt,
            reschedule = { scheduler.apply(scheduleInterval.first()) },
            continueSoon = { user -> scheduler.continueSoon(user) },
            updateWidgets = { syncing -> widgetUpdater.updateAll(syncing = syncing) },
        )
    }
}
