package dev.baseline.scores

import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Scoring that follows a sync run. A history re-score that was cut short (process death) left every day on the old
 * coefficients, so it is redone first, which also covers this sync; otherwise only the days from [invoke]'s argument on
 * are rescored. [backfillDone] is `!TaskDao.calibrationPending()`.
 */
class AfterSync(
    private val pipeline: ScorePipeline,
    private val recalculator: HistoryRecalculator,
    private val backfillDone: suspend () -> Boolean,
    private val today: () -> LocalDate,
) {
    /** [changedFrom] is null when only FIT files or nothing arrived; today is still rescored. */
    suspend operator fun invoke(changedFrom: LocalDate?) {
        val now = today()
        pipeline.recalibrateIfDue(now, backfillDone = backfillDone())
        if (recalculator.redoIfPending()) {
            recalculator.running.first { !it }
        } else {
            pipeline.recompute(changedFrom ?: now)
        }
    }
}
