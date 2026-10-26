package dev.baseline.records

import androidx.room3.withWriteTransaction
import dev.baseline.data.SportFamily
import dev.baseline.data.db.AppDatabase
import dev.baseline.fit.ActivityStreams
import dev.baseline.fit.SwimLength

/**
 * Rebuilds the best_effort table from scratch, so an activity deleted on Garmin loses its records.
 * shortcut: unpacks every ride stream on each run; keep a per-activity cache if the history grows past a few thousand rides.
 */
class RecordsRefresher(private val db: AppDatabase) {
    suspend fun refresh() {
        val activities = db.activityDao().list()
        val rows =
            BestEfforts.runFromSplits(activities) +
                activities.flatMap { a ->
                    when (a.sportFamily) {
                        SportFamily.RIDE -> BestEfforts.rideEfforts(a, stream(a.id))
                        SportFamily.SWIM -> BestEfforts.swimEfforts(a, lengths(a.id))
                        else -> emptyList()
                    }
                }
        val best = BestEfforts.best(rows)
        db.withWriteTransaction {
            db.bestEffortDao().deleteAll()
            db.bestEffortDao().insertAll(best)
        }
    }

    /** Null when the FIT is not ingested yet or the blob is unreadable; one bad row must not stop the sync. */
    private suspend fun stream(id: Long): ActivityStreams? =
        db.activityStreamDao().get(id)?.let { runCatching { ActivityStreams.unpack(it.packed) }.getOrNull() }

    private suspend fun lengths(id: Long): List<SwimLength> = db.swimLengthDao().forActivity(id).map {
        SwimLength(it.startOffsetSec ?: 0.0, it.durationSec ?: 0.0, it.strokeCount, it.strokeType, it.active && it.durationSec != null)
    }
}
