package dev.baseline.scores

import android.util.Log
import androidx.room3.withWriteTransaction
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.ScoreEntity
import dev.baseline.data.parse.HrZones
import dev.baseline.data.parse.HrZonesParser
import dev.baseline.fit.ActivityStreams
import dev.baseline.prefs.BanisterSet
import dev.baseline.prefs.UserPrefs
import dev.baseline.records.RecordsRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

/**
 * Raw cache -> ScoreEntity / ActivityScoreEntity (spec §7). Scaling factors are fitted on every activity; rows
 * (activity coverage and score, day score) are only written for the recomputed range. Public entry points run off the
 * main thread and one at a time, so a sync's scoring and a Settings re-score never interleave; the private variants
 * assume the lock is held.
 */
class ScorePipeline(
    private val db: AppDatabase,
    private val prefs: UserPrefs,
    private val clock: Clock,
    private val refreshRecords: suspend () -> Unit = RecordsRefresher(db)::refresh,
) {
    private val lock = Mutex()

    /** Everything derived from activities, built from scratch each run. */
    private class Loads(
        val zones: HrZones?, val set: BanisterSet,
        val activities: List<ActivityEntity>,
        val coverage: Map<Long, Double>,
        val resolved: Map<Long, ResolvedLoad>,
        /** Earliest day of an activity whose stream has not been scored yet (no hrCoverage written). */
        val firstUnscored: LocalDate?,
    ) {
        val byDay = activities.groupBy { it.day }
    }

    /**
     * Due when none exists, when the stored one was made in an earlier month, or when it is provisional and the
     * backfill (including the FIT stage) has finished; in that last case history is re-scored so no day keeps a tau
     * from partial data. [backfillDone] is `!TaskDao.calibrationPending()`.
     */
    suspend fun recalibrateIfDue(today: LocalDate, backfillDone: Boolean) = exclusive { recalibrateIfDueLocked(today, backfillDone) }

    private suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.Default) { block() } }

    private suspend fun recalibrateIfDueLocked(today: LocalDate, backfillDone: Boolean) {
        val cur = prefs.strainCalibration.first()
        val finalizing = cur != null && cur.provisional && backfillDone
        if (cur == null || finalizing || newMonth(today)) {
            calibrate(loads(), (cur?.version ?: 0) + 1, today, provisional = !backfillDone && (cur == null || cur.provisional))
            if (finalizing) recalculateHistoryLocked()
        }
    }

    private suspend fun newMonth(today: LocalDate) =
        prefs.strainCalibratedOn.first().let { it == null || YearMonth.from(it) < YearMonth.from(today) }

    /**
     * Re-fits the strain scale (tau = p90 of daily loads under the current coefficient set) and then re-scores every
     * day with it, so a coefficient change keeps "your 90th-percentile day ~ 17". With no load at all nothing is
     * stored (see [calibrate]). [backfillDone] is `!TaskDao.calibrationPending()`.
     */
    suspend fun recalibrateAndRescore(backfillDone: Boolean) = exclusive {
        val cur = prefs.strainCalibration.first()
        calibrate(loads(), (cur?.version ?: 0) + 1, LocalDate.now(clock), provisional = !backfillDone && (cur == null || cur.provisional))
        recalculateHistoryLocked()
    }

    /** Re-scores every day with the current calibration, so old rows take its version. */
    suspend fun recalculateHistory() = exclusive { recalculateHistoryLocked() }

    private suspend fun recalculateHistoryLocked() {
        // No day rows yet: still score today, so the data that triggered this is not left unscored.
        recomputeLocked(db.dayDao().all().firstOrNull()?.date ?: LocalDate.now(clock))
    }

    suspend fun recompute(fromDay: LocalDate) = exclusive { recomputeLocked(fromDay) }

    private suspend fun recomputeLocked(fromDay: LocalDate) {
        val today = LocalDate.now(clock)
        val loads = loads()
        val from = listOfNotNull(fromDay, loads.firstUnscored).min()
        val cal = prefs.strainCalibration.first() ?: calibrate(loads, 1, today, provisional = true) // unknown backfill state: let the next recalibrateIfDue finalize it
        val history = db.dayDao().all()
        val byDate = history.associateBy { it.date }

        val scores = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= today }.toList().map { date ->
            val day = byDate[date] ?: DayEntity(date)
            val load = dayLoad(date, loads)
            val sleepPerf = SleepPerformance.of(day.sleepSeconds, day.sleepNeedMinutes)
            val r = Recovery.compute(day, Baselines.at(date, history), sleepPerf)
            val target = r?.let { StrainTarget.forRecovery(it.score) }
            ScoreEntity(
                date, r?.score, r?.partial ?: false, sleepPerf, load, Strain.of(load, cal),
                target?.start, target?.endInclusive, cal.version,
            )
        }

        db.withWriteTransaction {
            for (a in loads.activities.filter { it.day >= from }) {
                loads.coverage[a.id]?.let { db.activityDao().setHrCoverage(a.id, it) }
                val r = loads.resolved[a.id]
                if (r != null) db.activityScoreDao().upsert(ActivityScoreEntity(a.id, r.load, r.source, Strain.of(r.load, cal)))
                else db.activityScoreDao().delete(a.id)
            }
            scores.forEach { db.scoreDao().upsert(it) }
        }
        recordsRefreshed()
    }

    /** Records are derived and rebuilt next run: a failure here is logged and must not fail the sync that scored. */
    private suspend fun recordsRefreshed() {
        try {
            refreshRecords()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh records: ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Calibrates over every day that has a DayEntity. A run with no load at all (empty cache) is not stored, so it
     * can't pin tau = 1.0; its transient result has version 0 so it never collides with the first real version.
     */
    private suspend fun calibrate(loads: Loads, version: Int, today: LocalDate, provisional: Boolean): StrainCalibration {
        val dayLoads = db.dayDao().all().map { dayLoad(it.date, loads) }
        val cal = Strain.calibrate(dayLoads, version).copy(provisional = provisional)
        if (dayLoads.none { it > 0 }) return cal.copy(version = 0)
        prefs.setStrainCalibration(cal, today)
        return cal
    }

    private suspend fun dayLoad(date: LocalDate, l: Loads): Double {
        val acts = l.byDay[date].orEmpty()
        val fromActivities = acts.sumOf { l.resolved[it.id]?.load ?: 0.0 }
        val z = l.zones ?: return fromActivities
        val hr = db.hrDayDao().get(date) ?: return fromActivities
        // Neighbouring days too: an activity running past midnight excludes samples on both sides.
        val windows = (l.byDay[date.minusDays(1)].orEmpty() + acts + l.byDay[date.plusDays(1)].orEmpty()).map { it.startGmt.epochSecond..it.startGmt.epochSecond + it.durationSec.toLong() }
        return fromActivities + NonActivityLoad.compute(HrPacking.unpack(hr.packed), windows, z.zone1Floor, z.restingHr, z.maxHr, l.set)
    }

    // shortcut: unpacks every stored stream on each run to get all TRIMPs for the scaling factors; cache TRIMP if the history grows past a few thousand activities.
    private suspend fun loads(): Loads {
        val set = prefs.banister.first()
        val zones = db.rawDao().get("HR_ZONES", "-")
            ?.let { runCatching { HrZonesParser.parse(Json.parseToJsonElement(String(it.json))) }.getOrNull() }
        val activities = db.activityDao().list()

        val coverage = mutableMapOf<Long, Double>()
        val samples = mutableListOf<LoadSample>()
        val trimps = mutableMapOf<Long, Double>()
        var firstUnscored: LocalDate? = null
        if (zones != null) for (a in activities) {
            val stream = db.activityStreamDao().get(a.id)
                ?.let { runCatching { ActivityStreams.unpack(it.packed) }.getOrNull() } ?: continue
            val pts = stream.toHrPoints()
            val trimp = Trimp.load(pts, zones.restingHr, zones.maxHr, set)
            val cov = Trimp.coverage(pts, a.durationSec)
            trimps[a.id] = trimp; coverage[a.id] = cov
            samples += LoadSample(a.sportFamily, trimp, cov, a.garminLoad)
            if (a.hrCoverage == null) firstUnscored = listOfNotNull(firstUnscored, a.day).min()
        }

        val factors = ActivityLoad.scalingFactors(samples)
        val resolved = activities.mapNotNull { a ->
            ActivityLoad.resolve(trimps[a.id], coverage[a.id], a.garminLoad, factors[a.sportFamily] ?: 1.0)?.let { a.id to it }
        }.toMap()
        return Loads(zones, set, activities, coverage, resolved, firstUnscored)
    }

    private companion object {
        const val TAG = "ScorePipeline"
    }
}
