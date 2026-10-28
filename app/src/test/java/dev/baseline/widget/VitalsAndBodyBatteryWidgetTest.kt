package dev.baseline.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
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
import dev.baseline.data.db.AppDatabase_Impl
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminResult
import dev.baseline.scores.RecoveryZone
import dev.baseline.sync.SyncEngine
import dev.baseline.sync.SyncScheduler
import dev.baseline.testutil.ScopeCleanup
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private class VitalsBbTestContainer(context: Context, private val store: DataStore<Preferences>) : AppContainer(context) {
    override val scheduler = SyncScheduler(WorkManager.getInstance(context), { false })
    override val apiKeyVault by lazy { ApiKeyVault(store, { it }, { it }, clock) }
    override val db: AppDatabase by lazy {
        Room.inMemoryDatabaseBuilder<AppDatabase>(context) { AppDatabase_Impl() }.allowMainThreadQueries().build()
    }
    override val syncEngine by lazy { SyncEngine({ _: GarminEndpoint, _: String -> GarminResult.Empty }, db, clock) {} }
}

@RunWith(RobolectricTestRunner::class)
class VitalsAndBodyBatteryWidgetTest {
    private val scopes = ScopeCleanup()
    private lateinit var ctx: Context
    private lateinit var file: File
    private lateinit var container: VitalsBbTestContainer

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        file = File.createTempFile("vitalsbbwidget", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scopes.storeScope) { file }
        container = VitalsBbTestContainer(ctx, store)
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

    private val today = LocalDate.of(2026, 10, 26)
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun snapshotWith(
        vitals: VitalsModel =
            VitalsModel(
                statusSummary = "1 worse",
                statusWarn = true,
                tiles =
                listOf(
                    VitalTileModel("hrv", "HRV", "68", "ms", "▲ 8%", true),
                    VitalTileModel("rhr", "Resting HR", "46", "bpm", "▼ 2", true),
                    VitalTileModel("stress", "Avg stress", "39", "", "▲ 14", false),
                    VitalTileModel("steps", "Steps", "4,200", "", "so far", null),
                ),
                allHidden = false,
                unreadable = false,
            ),
        bodyBattery: BodyBatteryModel =
            BodyBatteryModel(
                current = 64,
                metaLine = "Wake 88 · Charged +58 · Stress 24",
                curve =
                listOf(
                    ZonedDateTime.of(today, LocalTime.of(6, 0), zone).toInstant() to 88,
                    ZonedDateTime.of(today, LocalTime.of(12, 0), zone).toInstant() to 64,
                ),
                axisLabels = listOf("12a", "6a", "12p", "6p"),
                unreadable = false,
            ),
    ): WidgetSnapshot = WidgetSnapshot(
        today = today,
        zone = zone,
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
            unreadable = false,
        ),
        vitals = vitals,
        bodyBattery = bodyBattery,
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
            unreadable = false,
        ),
        weekly = WeeklyActivityModel("THIS WEEK · 0 ACTIVITIES", "0m · 0.0 km", null, emptyList()),
    )

    @Test
    fun vitalsWidgetRendersTilesDeltasHiddenFallbackAndUnreadableWarning() {
        val parent = FrameLayout(ctx)
        val view = container.widgetUpdater.buildVitalsViews(snapshotWith()).apply(ctx, parent)
        assertEquals("HRV", view.findViewById<TextView>(R.id.vital_label_1).text.toString())
        assertEquals("68 ms", view.findViewById<TextView>(R.id.vital_value_1).text.toString())
        assertEquals("▲ 8%", view.findViewById<TextView>(R.id.vital_delta_1).text.toString())
        assertEquals("1 worse", view.findViewById<TextView>(R.id.vitals_status_summary).text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.vital_tile_4).visibility)

        val twoTiles =
            snapshotWith(
                vitals =
                VitalsModel(
                    statusSummary = "2 in range",
                    statusWarn = false,
                    tiles =
                    listOf(
                        VitalTileModel("steps", "Steps", "4,200", "", "so far", null),
                        VitalTileModel("rhr", "Resting HR", "46", "bpm", "▼ 2", true),
                    ),
                    allHidden = false,
                    unreadable = false,
                ),
            )
        val twoView = container.widgetUpdater.buildVitalsViews(twoTiles).apply(ctx, parent)
        assertEquals(View.VISIBLE, twoView.findViewById<View>(R.id.vital_tile_1).visibility)
        assertEquals(View.VISIBLE, twoView.findViewById<View>(R.id.vital_tile_2).visibility)
        assertEquals(View.GONE, twoView.findViewById<View>(R.id.vitals_row_2).visibility)

        val allHidden =
            snapshotWith(
                vitals = VitalsModel(null, false, emptyList(), allHidden = true, unreadable = true),
            )
        val hiddenView = container.widgetUpdater.buildVitalsViews(allHidden).apply(ctx, parent)
        assertEquals(View.VISIBLE, hiddenView.findViewById<View>(R.id.vitals_all_hidden).visibility)
        assertEquals(
            "All baseline metrics hidden in Today",
            hiddenView.findViewById<TextView>(R.id.vitals_all_hidden).text.toString(),
        )
        assertEquals(View.VISIBLE, hiddenView.findViewById<View>(R.id.vitals_unreadable).visibility)
        assertEquals(
            "Couldn't read today's data",
            hiddenView.findViewById<TextView>(R.id.vitals_unreadable).text.toString(),
        )
    }

    @Test
    fun bodyBatteryWidgetRendersLevelMetaAndSparklineOrEmptyMessage() {
        val parent = FrameLayout(ctx)
        val withCurve = container.widgetUpdater.buildBodyBatteryViews(snapshotWith()).apply(ctx, parent)
        assertEquals("64", withCurve.findViewById<TextView>(R.id.bb_current_value).text.toString())
        assertEquals("Wake 88 · Charged +58 · Stress 24", withCurve.findViewById<TextView>(R.id.bb_meta_line).text.toString())
        assertEquals(View.VISIBLE, withCurve.findViewById<ImageView>(R.id.bb_sparkline).visibility)
        assertEquals(View.GONE, withCurve.findViewById<TextView>(R.id.bb_empty_note).visibility)
        assertEquals("12a", withCurve.findViewById<TextView>(R.id.bb_axis_1).text.toString())
        assertEquals("6p", withCurve.findViewById<TextView>(R.id.bb_axis_4).text.toString())

        val emptyCurve =
            snapshotWith(
                bodyBattery =
                BodyBatteryModel(
                    current = null,
                    metaLine = "—",
                    curve = emptyList(),
                    axisLabels = listOf("00", "06", "12", "18"),
                    unreadable = false,
                ),
            )
        val emptyView = container.widgetUpdater.buildBodyBatteryViews(emptyCurve).apply(ctx, parent)
        assertEquals("—", emptyView.findViewById<TextView>(R.id.bb_current_value).text.toString())
        assertEquals(View.GONE, emptyView.findViewById<ImageView>(R.id.bb_sparkline).visibility)
        assertEquals(View.VISIBLE, emptyView.findViewById<TextView>(R.id.bb_empty_note).visibility)
        assertEquals(
            "No Body Battery curve yet today",
            emptyView.findViewById<TextView>(R.id.bb_empty_note).text.toString(),
        )
    }

    @Test
    fun updaterUpdatesPlacedVitalsAndBodyBatteryWidgets() = runTest {
        container.apiKeyVault.save("k", 365)
        val manager = AppWidgetManager.getInstance(ctx)
        val shadowMgr = Shadows.shadowOf(manager)
        val vitalsId = shadowMgr.createWidget(VitalsWidgetProvider::class.java, R.layout.widget_vitals)
        val bbId = shadowMgr.createWidget(BodyBatteryWidgetProvider::class.java, R.layout.widget_body_battery)
        assertTrue(container.widgetUpdater.hasAnyWidgets(manager))

        container.widgetUpdater.updateAll()
        assertNotNull(shadowMgr.getViewFor(vitalsId))
        assertNotNull(shadowMgr.getViewFor(bbId))
    }
}
