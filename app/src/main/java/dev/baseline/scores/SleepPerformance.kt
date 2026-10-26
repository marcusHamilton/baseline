package dev.baseline.scores

import kotlin.math.min

object SleepPerformance {
    /** `min(1, sleepSeconds / (needMinutes * 60))`; null if either input is missing or <= 0 (no sleep at all means no Recovery). */
    fun of(sleepSeconds: Int?, needMinutes: Int?): Double? {
        if (sleepSeconds == null || sleepSeconds <= 0 || needMinutes == null || needMinutes <= 0) return null
        return min(1.0, sleepSeconds / (needMinutes * 60.0))
    }
}
