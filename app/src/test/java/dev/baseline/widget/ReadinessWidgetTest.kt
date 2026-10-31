package dev.baseline.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.AppContainer
import dev.baseline.R
import dev.baseline.data.db.AppDatabase
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminResult
import dev.baseline.scores.RecoveryZone
import dev.baseline.sync.SyncEngine
import dev.baseline.sync.SyncScheduler
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

private class WidgetCountingScheduler(wm: WorkManager) : SyncScheduler(wm, { false }) {
    val syncNows = AtomicInteger()

    override suspend fun syncNow() {
        syncNows.incrementAndGet()
        super.syncNow()
    }
}

private class WidgetTestContainer(context: Context, private val store: DataStore<Preferences>) : AppContainer(context) {
    override val scheduler = WidgetCountingScheduler(WorkManager.getInstance(context))
    override val apiKeyVault by lazy { ApiKeyVault(store, { it }, { it }, clock) }
    override val db: AppDatabase by lazy {
        inMemoryDatabase(context)
    }
    override val syncEngine by lazy { SyncEngine({ _: GarminEndpoint, _: String -> GarminResult.Empty }, db, clock) {} }
}

@RunWith(RobolectricTestRunner::class)
class ReadinessWidgetTest {
    private val scopes = ScopeCleanup()
    private lateinit var ctx: Context
    private lateinit var file: File
    private lateinit var container: WidgetTestContainer

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        file = File.createTempFile("readinesswidget", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        container = WidgetTestContainer(ctx, store)
        WidgetUpdater.testContainerOverride = container
    }

    @After
    fun tearDown(): Unit = runBlocking {
        WidgetUpdater.testContainerOverride = null
        scopes.close()
        container.close()
        container.db.close()
        dev.baseline.sync.closeWorkManagerDb(ctx)
        file.delete()
    }

    private fun sampleReadySnapshot(
        keyMissing: Boolean = false,
        syncPill: SyncPillUi = SyncPillUi.Ready("↻ 7:41 AM"),
        unreadable: Boolean = false,
    ): WidgetSnapshot = WidgetSnapshot(
        today = LocalDate.of(2026, 10, 26),
        zone = ZoneId.of("America/Los_Angeles"),
        keyMissing = keyMissing,
        syncPill = syncPill,
        readiness =
        ReadinessModel(
            headerDate = "TODAY · MON OCT 26",
            recoveryPct = 74,
            recoveryZone = RecoveryZone.GREEN,
            recoveryPartial = false,
            recoveryReason = null,
            strain = 11.4,
            strainText = "11.4",
            strainTargetText = "14–18",
            sleepPct = 92,
            bodyBattery = 64,
            unreadable = unreadable,
        ),
        vitals = VitalsModel("4 in range", false, emptyList(), allHidden = false, unreadable = unreadable),
        bodyBattery = BodyBatteryModel(64, "Wake 88 · Charged +58", emptyList(), listOf("12a", "6a", "12p", "6p"), unreadable),
        sleep =
        SleepWidgetModel(
            asleepText = "7h 42m",
            needText = "/ 8h 20m need",
            sleepPctText = "92%",
            garminScoreText = "Score 86",
            deepSec = 5_000,
            remSec = 6_000,
            lightSec = 12_000,
            awakeSec = 1_000,
            tonightNeedText = "Tonight's need: 8h 5m",
            bedtimeText = "Bedtime 10:15 PM",
            unreadable = unreadable,
        ),
        weekly = WeeklyActivityModel("THIS WEEK · 0 ACTIVITIES", "0m · 0.0 km", null, emptyList()),
    )

    @Test
    fun shortCircuitsWhenNoWidgetsArePlaced() = runTest {
        assertFalse(container.widgetUpdater.hasAnyWidgets())
        // Close DB first: if updateAll() touched Room when 0 widgets are placed, this would throw IllegalStateException.
        container.db.close()
        container.widgetUpdater.updateAll()
    }

    private fun collectImageIds(view: View): List<Int> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val own = if (view is ImageView && view.id != View.NO_ID) listOf(view.id) else emptyList()
        val children =
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { collectImageIds(view.getChildAt(it)) }
            } else {
                emptyList()
            }
        return own + children
    }

    private fun collectTexts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val own = if (view is TextView && view.text.isNotBlank()) listOf(view.text.toString()) else emptyList()
        val children =
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { collectTexts(view.getChildAt(it)) }
            } else {
                emptyList()
            }
        return own + children
    }

    @Test
    fun expandedAndCompactReadinessRenderDialsTargetsAnd48dpSyncHitbox() {
        val parent = FrameLayout(ctx)
        val expanded = container.widgetUpdater.buildReadinessViews(sampleReadySnapshot(), compact = false).apply(ctx, parent)
        assertEquals("TODAY · MON OCT 26", expanded.findViewById<TextView>(R.id.widget_header_title).text.toString())
        val body = expanded.findViewById<ViewGroup>(R.id.widget_body)
        assertEquals(
            listOf(R.id.dial_sleep, R.id.dial_recovery, R.id.dial_strain),
            collectImageIds(body),
        )
        assertEquals(
            listOf("SLEEP", "RECOVERY", "STRAIN"),
            collectTexts(body),
        )
        assertEquals("Recovery 74%", expanded.findViewById<ImageView>(R.id.dial_recovery).contentDescription.toString())
        assertEquals("Strain 11.4", expanded.findViewById<ImageView>(R.id.dial_strain).contentDescription.toString())
        assertEquals("Sleep 92%", expanded.findViewById<ImageView>(R.id.dial_sleep).contentDescription.toString())
        assertEquals("Target strain 14–18", expanded.findViewById<TextView>(R.id.readiness_footer_left).text.toString())
        assertEquals("BB 64", expanded.findViewById<TextView>(R.id.readiness_footer_bb).text.toString())

        val syncHit = expanded.findViewById<View>(R.id.widget_sync_hitbox)
        val minPx = (48 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(syncHit.minimumWidth >= minPx && syncHit.minimumHeight >= minPx)

        val unreadableView =
            container.widgetUpdater
                .buildReadinessViews(sampleReadySnapshot(unreadable = true), compact = false)
                .apply(ctx, parent)
        assertEquals(
            "Couldn't read today's data",
            unreadableView.findViewById<TextView>(R.id.readiness_footer_left).text.toString(),
        )

        val compact = container.widgetUpdater.buildReadinessViews(sampleReadySnapshot(), compact = true).apply(ctx, parent)
        assertEquals("74%", compact.findViewById<TextView>(R.id.compact_recovery_value).text.toString())
        assertEquals("11.4", compact.findViewById<TextView>(R.id.compact_strain_value).text.toString())
        assertEquals("Target 14–18", compact.findViewById<TextView>(R.id.compact_strain_target).text.toString())
        assertEquals("64", compact.findViewById<TextView>(R.id.compact_bb_value).text.toString())
    }

    @Test
    fun missingKeyRendersSetupPromptAndKeyErrorPillLinksToSettings() {
        val parent = FrameLayout(ctx)
        val shadowApp = Shadows.shadowOf(ctx as Application)

        val missing =
            container.widgetUpdater
                .buildReadinessViews(sampleReadySnapshot(keyMissing = true), compact = false)
                .apply(ctx, parent)
        assertEquals(
            "Connect Garmin API key to start",
            missing.findViewById<TextView>(R.id.widget_missing_prompt).text.toString(),
        )

        val keyErr =
            container.widgetUpdater
                .buildReadinessViews(sampleReadySnapshot(syncPill = SyncPillUi.KeyError), compact = false)
                .apply(ctx, parent)
        assertEquals("Key error", keyErr.findViewById<TextView>(R.id.widget_sync_pill).text.toString())
        shadowApp.clearNextStartedActivities()
        keyErr.findViewById<View>(R.id.widget_sync_hitbox).performClick()
        assertEquals(WidgetTarget.Settings, WidgetRoutes.parse(shadowApp.nextStartedActivity))

        val ready =
            container.widgetUpdater
                .buildReadinessViews(sampleReadySnapshot(syncPill = SyncPillUi.Ready("↻ 7:41 AM")), compact = false)
                .apply(ctx, parent)
        shadowApp.clearBroadcastIntents()
        ready.findViewById<View>(R.id.widget_sync_hitbox).performClick()
        assertTrue(shadowApp.broadcastIntents.any { it.action == WidgetActionReceiver.ACTION_WIDGET_SYNC })
    }

    @Test
    fun zeroSizeOptionsBundleFallsBackWithoutCrashing() {
        val parent = FrameLayout(ctx)
        val zeroOpts =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            }
        assertNotNull(container.widgetUpdater.buildReadinessViews(sampleReadySnapshot(), compact = false, options = Bundle()).apply(ctx, parent))
        assertNotNull(container.widgetUpdater.buildReadinessViews(sampleReadySnapshot(), compact = true, options = zeroOpts).apply(ctx, parent))
    }

    @Test
    fun actionWidgetSyncEnqueuesSyncNowWhenKeyPresentAndUpdatesPlacedWidgets() = runTest {
        val receiver = WidgetActionReceiver()
        // Key missing: should NOT enqueue syncNow.
        receiver.handleIntent(ctx, Intent(WidgetActionReceiver.ACTION_WIDGET_SYNC))
        assertEquals(0, container.scheduler.syncNows.get())

        // Save key + place a Readiness widget via ShadowAppWidgetManager.
        container.apiKeyVault.save("k", 365)
        val manager = AppWidgetManager.getInstance(ctx)
        val shadowMgr = Shadows.shadowOf(manager)
        val widgetId = shadowMgr.createWidget(ReadinessWidgetProvider::class.java, R.layout.widget_readiness_expanded)
        assertTrue(container.widgetUpdater.hasAnyWidgets(manager))

        receiver.handleIntent(ctx, Intent(WidgetActionReceiver.ACTION_WIDGET_SYNC))
        assertEquals(1, container.scheduler.syncNows.get())
        assertNotNull(shadowMgr.getViewFor(widgetId))
    }
}
