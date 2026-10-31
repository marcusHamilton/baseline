package dev.baseline.widget

import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.DEFAULT_BASELINE_ORDER
import dev.baseline.scores.FormState
import dev.baseline.scores.RecoveryZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetSnapshotTest {
    private val defaultToday = LocalDate.of(2026, 10, 26) // Monday
    private val laZone = ZoneId.of("America/Los_Angeles")
    private val okKey = KeyStatus.Ok(LocalDate.of(2027, 10, 26), 365)

    private fun scoredHistory(days: Int, endingBefore: LocalDate = defaultToday): List<DayEntity> = (1..days).map { offset ->
        DayEntity(
            date = endingBefore.minusDays(offset.toLong()),
            restingHr = 48,
            respirationAvg = 13.8,
            stressAvg = 25,
            steps = 8_500,
            bodyBatteryWake = 82,
            sleepSeconds = 27_000,
            sleepNeedMinutes = 480,
            hrvLastNight = 63.0,
        )
    }

    private fun sampleSnapshot(
        today: LocalDate = defaultToday,
        zone: ZoneId = laZone,
        is24h: Boolean = false,
        keyStatus: KeyStatus = okKey,
        syncing: Boolean = false,
        lastSynced: Instant? = ZonedDateTime.of(today, LocalTime.of(7, 41), zone).toInstant(),
        day: DayEntity? =
            DayEntity(
                date = today,
                restingHr = 46,
                respirationAvg = 13.8,
                stressAvg = 24,
                steps = 4_200,
                bodyBatteryWake = 88,
                bodyBatteryCharged = 58,
                sleepSeconds = 27_720, // 7h 42m
                deepSeconds = 6_100,
                remSeconds = 6_900,
                lightSeconds = 13_300,
                awakeSeconds = 1_420,
                sleepScore = 86,
                sleepNeedMinutes = 500, // 8h 20m
                hrvLastNight = 68.0,
                nextSleepNeedBaselineMin = 465,
                nextSleepNeedActualMin = 485, // 8h 5m
                recommendedBedtimeMin = 22 * 60 + 15, // 10:15 PM
            ),
        score: ScoreEntity? =
            ScoreEntity(
                date = today,
                recovery = 74,
                recoveryPartial = false,
                sleepPerformance = 0.924,
                dayLoad = 95.0,
                strain = 11.4,
                strainTargetLow = 14.0,
                strainTargetHigh = 18.0,
                calibrationVersion = 1,
            ),
        history: List<DayEntity> = scoredHistory(14, today),
        unreadable: Boolean = false,
        bodyBatteryCurve: List<Pair<Instant, Int>> =
            listOf(
                ZonedDateTime.of(today, LocalTime.of(6, 0), zone).toInstant() to 88,
                ZonedDateTime.of(today, LocalTime.of(12, 0), zone).toInstant() to 64,
            ),
        order: List<String> = DEFAULT_BASELINE_ORDER,
        hidden: Set<String> = emptySet(),
        imperial: Boolean = false,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        recentActivities: List<ActivityEntity> = emptyList(),
        activityStrains: Map<Long, Double> = emptyMap(),
        activeSets: Map<Long, Int> = emptyMap(),
        weekScores: Map<LocalDate, ScoreEntity> = emptyMap(),
        weekState: FormState? = null,
    ): WidgetSnapshot = buildWidgetSnapshot(
        today = today,
        zone = zone,
        is24h = is24h,
        keyStatus = keyStatus,
        syncing = syncing,
        lastSynced = lastSynced,
        day = day,
        score = score,
        history = history,
        unreadable = unreadable,
        bodyBatteryCurve = bodyBatteryCurve,
        order = order,
        hidden = hidden,
        imperial = imperial,
        firstDayOfWeek = firstDayOfWeek,
        recentActivities = recentActivities,
        activityStrains = activityStrains,
        activeSets = activeSets,
        weekScores = weekScores,
        weekState = weekState,
    )

    @Test
    fun anOverreachingWeekEasesTheStrainTargetLikeToday() {
        assertEquals("14–18", sampleSnapshot().readiness.strainTargetText)
        assertEquals("14–18", sampleSnapshot(weekState = FormState.PRODUCTIVE).readiness.strainTargetText)
        assertEquals("10–14", sampleSnapshot(weekState = FormState.OVERREACHING).readiness.strainTargetText)
    }

    @Test
    fun morningBeforeSyncDoesNotLeakYesterday() {
        val snap = sampleSnapshot(day = null, score = null, bodyBatteryCurve = emptyList())
        assertNull(snap.readiness.recoveryPct)
        assertNull(snap.readiness.sleepPct)
        assertEquals("—", snap.readiness.strainText)
        assertNull(snap.readiness.bodyBattery)
        assertEquals("—", snap.sleep.asleepText)
        assertNull(snap.sleep.needText)
    }

    @Test
    fun syncPillStatesAndWallClockZones() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val morning = ZonedDateTime.of(defaultToday, LocalTime.of(7, 41), laZone).toInstant()
        val yesterday = ZonedDateTime.of(defaultToday.minusDays(1), LocalTime.of(21, 5), laZone).toInstant()

        assertEquals(SyncPillUi.Syncing, sampleSnapshot(syncing = true, lastSynced = morning).syncPill)
        assertEquals(SyncPillUi.KeyError, sampleSnapshot(keyStatus = KeyStatus.Invalid, syncing = true).syncPill)
        assertEquals(SyncPillUi.KeyError, sampleSnapshot(keyStatus = KeyStatus.Expired(defaultToday.minusDays(2))).syncPill)
        assertTrue(sampleSnapshot(keyStatus = KeyStatus.Missing).keyMissing)
        assertEquals(SyncPillUi.Ready("↻ Sync"), sampleSnapshot(lastSynced = null).syncPill)
        assertEquals(SyncPillUi.Ready("↻ 7:41 AM"), sampleSnapshot(zone = laZone, is24h = false, lastSynced = morning).syncPill)
        assertEquals(SyncPillUi.Ready("↻ 07:41"), sampleSnapshot(zone = laZone, is24h = true, lastSynced = morning).syncPill)
        assertEquals(SyncPillUi.Ready("↻ Oct 25"), sampleSnapshot(zone = laZone, is24h = false, lastSynced = yesterday).syncPill)
        // 7:41 AM Oct 26 in Los Angeles (-07:00) is 23:41 Oct 26 in Tokyo (+09:00).
        assertEquals(SyncPillUi.Ready("↻ 23:41"), sampleSnapshot(today = defaultToday, zone = tokyo, is24h = true, lastSynced = morning).syncPill)
    }

    @Test
    fun vitalsRespectsOrderHiddenAndWorseWarning() {
        val hiddenFive = setOf("hrv", "stress", "sleep", "resp", "bb")
        val snap = sampleSnapshot(order = listOf("steps", "rhr", "hrv"), hidden = hiddenFive)
        assertEquals(listOf("steps", "rhr"), snap.vitals.tiles.map { it.id })
        assertEquals(listOf("Steps", "Resting HR"), snap.vitals.tiles.map { it.shortLabel })
        assertFalse(snap.vitals.allHidden)
        assertFalse(snap.vitals.statusWarn)

        val worseDay =
            DayEntity(
                date = defaultToday,
                restingHr = 56, // worse than 48 baseline
                hrvLastNight = 45.0, // worse than 63 baseline
                sleepSeconds = 27_000,
                sleepNeedMinutes = 480,
            )
        val worseSnap = sampleSnapshot(day = worseDay)
        assertTrue(worseSnap.vitals.statusWarn)
        assertEquals("2 worse", worseSnap.vitals.statusSummary)

        val allHidden = sampleSnapshot(hidden = DEFAULT_BASELINE_ORDER.toSet())
        assertTrue(allHidden.vitals.allHidden)
        assertTrue(allHidden.vitals.tiles.isEmpty())
    }

    @Test
    fun sleepModelFormatsLastNightAndTonightGuidance() {
        val snap12 = sampleSnapshot(is24h = false)
        assertEquals("7h 42m", snap12.sleep.asleepText)
        assertEquals("/ 8h 20m need", snap12.sleep.needText)
        assertEquals("92%", snap12.sleep.sleepPctText)
        assertEquals("Score 86", snap12.sleep.garminScoreText)
        assertEquals("Tonight's need: 8h 5m", snap12.sleep.tonightNeedText)
        assertEquals("Bedtime 10:15 PM", snap12.sleep.bedtimeText)

        val snap24 = sampleSnapshot(is24h = true)
        assertEquals("Bedtime 22:15", snap24.sleep.bedtimeText)
    }

    @Test
    fun weeklySummaryScopesTotalsToWeekButKeepsLastWeeksLatestActivity() {
        val sundayRun =
            ActivityEntity(
                id = 99L,
                name = "Sunday Trail Run",
                typeKey = "trail_running",
                sportFamily = SportFamily.RUN,
                startTimeLocal = LocalDateTime.of(2026, 10, 25, 8, 30),
                day = LocalDate.of(2026, 10, 25),
                startGmt = Instant.parse("2026-10-25T15:30:00Z"),
                durationSec = 3_120.0,
                distanceM = 10_000.0,
                avgHr = 152.0,
                maxHr = 176.0,
                zone1Sec = null,
                zone2Sec = null,
                zone3Sec = null,
                zone4Sec = null,
                zone5Sec = null,
                garminLoad = 120.0,
                elevationGainM = 210.0,
                avgPower = null,
                normPower = null,
                fastestSplitsJson = null,
                fitPath = null,
                hrCoverage = 0.99,
                poolLengthM = null,
            )
        val mondaySnap =
            sampleSnapshot(
                today = LocalDate.of(2026, 10, 26),
                firstDayOfWeek = DayOfWeek.MONDAY,
                recentActivities = listOf(sundayRun),
                activityStrains = mapOf(99L to 14.2),
            )
        assertEquals("THIS WEEK · 0 ACTIVITIES", mondaySnap.weekly.headerTitle)
        assertNotNull(mondaySnap.weekly.latest)
        assertEquals(99L, mondaySnap.weekly.latest?.id)
        assertEquals("14.2", mondaySnap.weekly.latest?.strainText)
        assertTrue(mondaySnap.weekly.latest!!.metaLine.startsWith("Yesterday · Sun, Oct 25"))
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), mondaySnap.weekly.days.map { it.letter })

        // When firstDayOfWeek is SUNDAY, Sunday Oct 25 is inside the current week (Oct 25..Oct 31).
        val sundayWeekImperial =
            sampleSnapshot(
                today = LocalDate.of(2026, 10, 26),
                firstDayOfWeek = DayOfWeek.SUNDAY,
                imperial = true,
                recentActivities = listOf(sundayRun),
                activityStrains = mapOf(99L to 14.2),
                weekScores =
                mapOf(
                    LocalDate.of(2026, 10, 25) to
                        ScoreEntity(LocalDate.of(2026, 10, 25), 72, false, 0.9, 120.0, 14.2, 14.0, 18.0, 1),
                ),
            )
        assertEquals("THIS WEEK · 1 ACTIVITY", sundayWeekImperial.weekly.headerTitle)
        assertTrue(sundayWeekImperial.weekly.weekTotalsText.contains("6.2 mi"))
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), sundayWeekImperial.weekly.days.map { it.letter })
        assertEquals(RecoveryZone.GREEN, sundayWeekImperial.weekly.days[0].recoveryZone)
        assertTrue(sundayWeekImperial.weekly.days[0].hasActivity)
    }

    @Test
    fun nonFiniteAndNegativeInputsRenderDashesAndUnreadablePropagates() {
        val badScore = ScoreEntity(defaultToday, 74, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.POSITIVE_INFINITY, 1)
        val badDay = DayEntity(defaultToday, sleepSeconds = -60, sleepNeedMinutes = -10, hrvLastNight = Double.NaN)
        val badActivity =
            ActivityEntity(
                id = 5L,
                name = null,
                typeKey = "running",
                sportFamily = SportFamily.RUN,
                startTimeLocal = LocalDateTime.of(2026, 10, 26, 7, 0),
                day = defaultToday,
                startGmt = Instant.parse("2026-10-26T14:00:00Z"),
                durationSec = Double.NaN,
                distanceM = Double.POSITIVE_INFINITY,
                avgHr = null,
                maxHr = null,
                zone1Sec = null,
                zone2Sec = null,
                zone3Sec = null,
                zone4Sec = null,
                zone5Sec = null,
                garminLoad = null,
                elevationGainM = null,
                avgPower = null,
                normPower = null,
                fastestSplitsJson = null,
                fitPath = null,
                hrCoverage = null,
                poolLengthM = null,
            )
        val snap =
            sampleSnapshot(
                day = badDay,
                score = badScore,
                unreadable = true,
                recentActivities = listOf(badActivity),
                activityStrains = mapOf(5L to Double.NaN),
            )
        assertNull(snap.readiness.strain)
        assertEquals("—", snap.readiness.strainText)
        assertNull(snap.readiness.strainTargetText)
        assertEquals("—", snap.sleep.asleepText)
        assertEquals("—", snap.weekly.latest?.strainText)
        assertTrue(snap.readiness.unreadable)
        assertTrue(snap.vitals.unreadable)
        assertTrue(snap.bodyBattery.unreadable)
        assertTrue(snap.sleep.unreadable)
    }
}
