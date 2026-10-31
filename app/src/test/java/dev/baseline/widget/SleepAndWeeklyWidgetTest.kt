package dev.baseline.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.baseline.AppContainer
import dev.baseline.R
import dev.baseline.data.SportFamily
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

private class SleepWeeklyTestContainer(context: Context, private val store: DataStore<Preferences>) : AppContainer(context) {
    override val scheduler = SyncScheduler(WorkManager.getInstance(context), { false })
    override val apiKeyVault by lazy { ApiKeyVault(store, { it }, { it }, clock) }
    override val db: AppDatabase by lazy {
        inMemoryDatabase(context)
    }
    override val syncEngine by lazy { SyncEngine({ _: GarminEndpoint, _: String -> GarminResult.Empty }, db, clock) {} }
}

@RunWith(RobolectricTestRunner::class)
class SleepAndWeeklyWidgetTest {
    private val scopes = ScopeCleanup()
    private lateinit var ctx: Context
    private lateinit var file: File
    private lateinit var container: SleepWeeklyTestContainer

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        file = File.createTempFile("sleepweeklywidget", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        container = SleepWeeklyTestContainer(ctx, store)
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

    private fun sampleSnapshot(
        unreadable: Boolean = false,
        latest: LatestActivityUi? =
            LatestActivityUi(
                id = 99L,
                title = "Morning Trail Run",
                family = SportFamily.RUN,
                metaLine = "Today 7:15 AM · 10.2 km · 51:20",
                strainText = "14.2",
            ),
    ): WidgetSnapshot = WidgetSnapshot(
        today = LocalDate.of(2026, 10, 26),
        zone = ZoneId.of("America/Los_Angeles"),
        keyMissing = false,
        syncPill = SyncPillUi.Ready("↻ 7:41 AM"),
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
        bodyBattery = BodyBatteryModel(64, "Wake 88", emptyList(), listOf("12a", "6a", "12p", "6p"), unreadable),
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
        weekly =
        WeeklyActivityModel(
            headerTitle = "THIS WEEK · 4 ACTIVITIES",
            weekTotalsText = "5h 18m · 42.6 km",
            latest = latest,
            days =
            listOf(
                WeekDayCell("M", RecoveryZone.GREEN, true),
                WeekDayCell("T", RecoveryZone.AMBER, true),
                WeekDayCell("W", RecoveryZone.GREEN, false),
                WeekDayCell("T", RecoveryZone.RED, true),
                WeekDayCell("F", null, false),
                WeekDayCell("S", null, false),
                WeekDayCell("S", null, false),
            ),
        ),
    )

    @Test
    fun sleepWidgetRendersHoursStagesTonightNeedBedtimeAndDeepLinksToSleepToday() {
        val parent = FrameLayout(ctx)
        val shadowApp = Shadows.shadowOf(ctx as Application)

        val view = container.widgetUpdater.buildSleepViews(sampleSnapshot()).apply(ctx, parent)
        assertEquals("7h 42m", view.findViewById<TextView>(R.id.sleep_asleep_value).text.toString())
        assertEquals("/ 8h 20m need", view.findViewById<TextView>(R.id.sleep_need_value).text.toString())
        assertEquals("92%", view.findViewById<TextView>(R.id.sleep_pct_badge).text.toString())
        assertEquals("Score 86", view.findViewById<TextView>(R.id.sleep_garmin_score).text.toString())
        assertEquals("Tonight's need: 8h 5m", view.findViewById<TextView>(R.id.sleep_tonight_need).text.toString())
        assertEquals("Bedtime 10:15 PM", view.findViewById<TextView>(R.id.sleep_bedtime).text.toString())

        shadowApp.clearNextStartedActivities()
        view.findViewById<View>(R.id.widget_root).performClick()
        assertEquals(WidgetTarget.SleepToday, WidgetRoutes.parse(shadowApp.nextStartedActivity))

        val unreadableView = container.widgetUpdater.buildSleepViews(sampleSnapshot(unreadable = true)).apply(ctx, parent)
        assertEquals(
            "Couldn't read today's data",
            unreadableView.findViewById<TextView>(R.id.sleep_tonight_need).text.toString(),
        )
    }

    @Test
    fun weeklyActivityWidgetRoutesWorkoutCardToActivityDetailAndRootToActivities() {
        val parent = FrameLayout(ctx)
        val shadowApp = Shadows.shadowOf(ctx as Application)

        val view = container.widgetUpdater.buildWeeklyActivityViews(sampleSnapshot()).apply(ctx, parent)
        assertEquals("THIS WEEK · 4 ACTIVITIES", view.findViewById<TextView>(R.id.weekly_header_title).text.toString())
        assertEquals("5h 18m · 42.6 km", view.findViewById<TextView>(R.id.weekly_totals_text).text.toString())
        assertEquals("Morning Trail Run", view.findViewById<TextView>(R.id.weekly_activity_title).text.toString())
        assertEquals("14.2", view.findViewById<TextView>(R.id.weekly_activity_strain).text.toString())

        shadowApp.clearNextStartedActivities()
        view.findViewById<View>(R.id.weekly_workout_card).performClick()
        assertEquals(Uri.parse("baseline://widget/activity/99"), shadowApp.nextStartedActivity.data)

        shadowApp.clearNextStartedActivities()
        view.findViewById<View>(R.id.widget_root).performClick()
        assertEquals(Uri.parse("baseline://widget/activities"), shadowApp.nextStartedActivity.data)

        shadowApp.clearNextStartedActivities()
        view.findViewById<View>(R.id.weekly_strip).performClick()
        assertEquals(Uri.parse("baseline://widget/activities"), shadowApp.nextStartedActivity.data)

        val emptyView = container.widgetUpdater.buildWeeklyActivityViews(sampleSnapshot(latest = null)).apply(ctx, parent)
        assertEquals(View.VISIBLE, emptyView.findViewById<View>(R.id.weekly_empty_text).visibility)
        assertEquals(
            "No activities synced yet",
            emptyView.findViewById<TextView>(R.id.weekly_empty_text).text.toString(),
        )
        shadowApp.clearNextStartedActivities()
        emptyView.findViewById<View>(R.id.weekly_workout_card).performClick()
        assertEquals(Uri.parse("baseline://widget/activities"), shadowApp.nextStartedActivity.data)
    }

    @Test
    fun updaterUpdatesAllFiveWidgetProviders() = runTest {
        container.apiKeyVault.save("k", 365)
        val manager = AppWidgetManager.getInstance(ctx)
        val shadowMgr = Shadows.shadowOf(manager)
        val ids =
            listOf(
                shadowMgr.createWidget(ReadinessWidgetProvider::class.java, R.layout.widget_readiness_expanded),
                shadowMgr.createWidget(VitalsWidgetProvider::class.java, R.layout.widget_vitals),
                shadowMgr.createWidget(BodyBatteryWidgetProvider::class.java, R.layout.widget_body_battery),
                shadowMgr.createWidget(SleepWidgetProvider::class.java, R.layout.widget_sleep),
                shadowMgr.createWidget(WeeklyActivityWidgetProvider::class.java, R.layout.widget_weekly_activity),
            )
        assertTrue(container.widgetUpdater.hasAnyWidgets(manager))
        container.widgetUpdater.updateAll()
        ids.forEach { assertNotNull(shadowMgr.getViewFor(it)) }
    }
}
