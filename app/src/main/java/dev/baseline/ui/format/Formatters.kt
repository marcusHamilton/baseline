package dev.baseline.ui.format

import dev.baseline.data.SportFamily
import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Display formatting shared by the screens. Missing or non-finite input is "—", never NaN. */
class Formatters(val imperial: Boolean) {
    /** "10.21 km", or "6.34 mi" when [imperial]; null for a missing or zero distance. */
    fun distance(meters: Double?): String? = longDistance(meters, 2)

    /** "52:14", or "1:05:30" from an hour up; null for a missing or zero duration. */
    fun duration(seconds: Double?): String? {
        if (seconds == null || !seconds.isFinite() || seconds <= 0) return null
        val total = seconds.roundToInt()
        val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
    }

    /** "10.21 km", "42.6 km" ([decimals] = 1), or the mile equivalents; null for a missing or zero distance. */
    private fun longDistance(meters: Double?, decimals: Int): String? {
        if (meters == null || !meters.isFinite() || meters <= 0) return null
        return if (imperial) "%.${decimals}f mi".format(Locale.US, meters / METERS_PER_MILE) else "%.${decimals}f km".format(Locale.US, meters / 1000)
    }

    /**
     * The second line of an activity card, per sport: run "10.21 km · 52:14 · 5:07 /km", ride "42.6 km · 1:34:20 · 27.1 km/h · 196 W avg",
     * swim "2,000 m · 41:08 · 2:03 /100m", strength "46 min · 18 sets", anything else distance then duration.
     * A missing part is left out with its separator; [sets] is the count of active sets (null or 0 when there are none).
     */
    fun meta(family: SportFamily, distanceM: Double?, durationSec: Double?, avgPower: Double?, sets: Int?): String {
        val dist = distanceM?.takeIf { it.isFinite() && it > 0 }
        val dur = durationSec?.takeIf { it.isFinite() && it > 0 }
        val both = if (dist != null && dur != null) dist to dur else null
        val parts = when (family) {
            SportFamily.RUN -> listOf(
                longDistance(dist, 2), duration(dur),
                both?.let { (d, t) -> duration(t / (d / if (imperial) METERS_PER_MILE else 1000.0))?.let { "$it ${if (imperial) "/mi" else "/km"}" } },
            )
            SportFamily.RIDE -> listOf(
                longDistance(dist, 1), duration(dur),
                both?.let { (d, t) -> "%.1f %s".format(Locale.US, d / t * 3.6 / if (imperial) KM_PER_MILE else 1.0, if (imperial) "mph" else "km/h") },
                avgPower?.takeIf { it.isFinite() && it > 0 }?.let { "${it.roundToInt()} W avg" },
            )
            SportFamily.SWIM -> {
                val unit = if (imperial) METERS_PER_YARD else 1.0 // metres per displayed unit
                listOf(
                    dist?.let { "%,d %s".format(Locale.US, (it / unit).roundToInt(), if (imperial) "yd" else "m") }, duration(dur),
                    both?.let { (d, t) -> duration(t / (d / unit / 100))?.let { "$it /100${if (imperial) "yd" else "m"}" } },
                )
            }
            SportFamily.STRENGTH -> listOf(
                dur?.let { "${(it / 60).roundToInt()} min" },
                sets?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "set" else "sets"}" },
            )
            SportFamily.OTHER -> listOf(longDistance(dist, 2), duration(dur))
        }
        return parts.filterNotNull().joinToString(" · ")
    }

    val distanceUnit get() = if (imperial) "mi" else "km"
    val paceUnit get() = if (imperial) "/mi" else "/km"
    val speedUnit get() = if (imperial) "mph" else "km/h"
    val elevationUnit get() = if (imperial) "ft" else "m"
    val weightUnit get() = if (imperial) "lb" else "kg"
    val swimPaceUnit get() = if (imperial) "/100yd" else "/100m"

    /** "10.21" (no unit; see [distanceUnit]); null for a missing or zero distance. */
    fun distanceValue(meters: Double?): String? =
        meters?.takeIf { it.isFinite() && it > 0 }?.let { "%.2f".format(Locale.US, it / (if (imperial) METERS_PER_MILE else 1000.0)) }

    /** Seconds per km (or mile) over [distanceM]; null when either input is missing or zero. */
    fun secondsPerUnit(distanceM: Double?, durationSec: Double?): Double? {
        if (distanceM == null || durationSec == null || !distanceM.isFinite() || !durationSec.isFinite() || distanceM <= 0 || durationSec <= 0) return null
        return durationSec / (distanceM / if (imperial) METERS_PER_MILE else 1000.0)
    }

    /** "5:07" from seconds per km (or mile); null for a missing, zero or non-finite pace. */
    fun pace(secondsPerUnit: Double?): String? = duration(secondsPerUnit)

    /** "27.1" km/h (mph) from metres per second; null for a missing or negative speed. */
    fun speedValue(metersPerSecond: Double?): String? = speedNumber(metersPerSecond)?.let { "%.1f".format(Locale.US, it) }

    /** [metersPerSecond] in km/h (mph); null when missing, non-finite or negative. */
    fun speedNumber(metersPerSecond: Double?): Double? =
        metersPerSecond?.takeIf { it.isFinite() && it >= 0 }?.let { it * 3.6 / if (imperial) KM_PER_MILE else 1.0 }

    /** [meters] in m (ft); null when missing or non-finite. */
    fun elevationNumber(meters: Double?): Double? = meters?.takeIf { it.isFinite() }?.let { it * if (imperial) FEET_PER_METER else 1.0 }

    /** "86" (rounded, no unit; see [elevationUnit]); null when missing. */
    fun elevation(meters: Double?): String? = elevationNumber(meters)?.let { "%d".format(Locale.US, it.roundToInt()) }

    /** "+12" or "−4" (true minus sign); null when missing. */
    fun elevationDelta(meters: Double?): String? = elevationNumber(meters)?.roundToInt()?.let { if (it < 0) "−${-it}" else "+$it" }

    /** "2:03" from seconds per 100 m (per 100 yd when imperial); null for a missing or zero pace. */
    fun swimPace(secondsPer100m: Double?): String? = duration(secondsPer100m?.times(if (imperial) METERS_PER_YARD else 1.0))

    /** "100" or "52.5": a FIT weight in kg shown in kg (lb); no unit. */
    fun weight(kg: Double): String {
        val v = kg * if (imperial) LB_PER_KG else 1.0
        return if (v == v.roundToInt().toDouble()) "%d".format(Locale.US, v.roundToInt()) else "%.1f".format(Locale.US, v)
    }

    /** "2,000" metres (yards when imperial, see [swimDistanceUnit]); null for a missing or zero distance. */
    fun swimDistance(meters: Double?): String? =
        meters?.takeIf { it.isFinite() && it > 0 }?.let { "%,d".format(Locale.US, (it / if (imperial) METERS_PER_YARD else 1.0).roundToInt()) }

    val swimDistanceUnit get() = if (imperial) "yd" else "m"

    /** "2,420": total lifted weight of [kg] kilograms in kg (lb); no unit. */
    fun volume(kg: Double): String = "%,d".format(Locale.US, (kg * if (imperial) LB_PER_KG else 1.0).roundToInt())

    fun clock(t: LocalDateTime): String = t.format(CLOCK)

    /** "<start> · <distance> · <duration>" with absent parts (and their separators) left out. */
    fun activityMeta(start: LocalDateTime, distanceM: Double?, durationSec: Double?): String =
        listOfNotNull(clock(start), distance(distanceM), duration(durationSec)).joinToString(" · ")

    companion object {
        internal const val METERS_PER_MILE = 1609.344
        internal const val METERS_PER_YARD = 0.9144
        private const val KM_PER_MILE = 1.609344
        private const val FEET_PER_METER = 3.28084
        private const val LB_PER_KG = 2.20462
        private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        private val SYNC_DAY = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

        /** "Synced 7:41 AM" when [at] is on [today], else "Synced Oct 22, 7:41 AM". */
        fun synced(at: Instant, today: LocalDate, zone: ZoneId): String {
            val local = at.atZone(zone)
            return "Synced " + if (local.toLocalDate() == today) local.format(CLOCK) else local.format(SYNC_DAY)
        }

        /** "Today, 6:42 AM", or "Oct 22, 7:41 AM" on another day. */
        fun lastSync(at: Instant, today: LocalDate, zone: ZoneId): String {
            val local = at.atZone(zone)
            return if (local.toLocalDate() == today) "Today, " + local.format(CLOCK) else local.format(SYNC_DAY)
        }

        /** "just now", "5 min ago", "3 h ago", "2 days ago"; a time in the future reads as "just now". */
        fun ago(at: Instant, now: Instant): String {
            val minutes = Duration.between(at, now).toMinutes()
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 24 * 60 -> "${minutes / 60} h ago"
                else -> (minutes / (24 * 60)).let { if (it == 1L) "1 day ago" else "$it days ago" }
            }
        }

        private val CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

        /** "11:02 PM", or "23:02" when [is24h] (the device's 12/24-hour setting). */
        fun time(t: LocalTime, is24h: Boolean): String = t.format(if (is24h) CLOCK_24 else CLOCK)

        private val DAY_HEADER = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

        /** "Today · Sat, Oct 24", "Yesterday · Fri, Oct 23", else "Thu, Oct 22". */
        fun dayHeader(date: LocalDate, today: LocalDate): String {
            val d = date.format(DAY_HEADER)
            return when (date) { today -> "Today · $d"; today.minusDays(1) -> "Yesterday · $d"; else -> d }
        }

        /** "6:48" (rounded to the minute); "—" for a zero duration. */
        fun hoursColonMinutes(d: Duration): String {
            if (d.isZero || d.isNegative) return "—"
            val m = (d.seconds / 60.0).roundToInt()
            return "%d:%02d".format(Locale.US, m / 60, m % 60)
        }

        /** "7h 42m"; "—" for a missing or zero duration. */
        fun hoursMinutes(d: Duration): String = if (d.isZero || d.isNegative) "—" else hoursMinutes(d.seconds.toDouble()) ?: "—"

        /** "+ 1h 18m", "− 20m", or "No change" for zero. */
        fun signedHoursMinutes(d: Duration): String = when {
            d.isZero -> "No change"
            d.isNegative -> "− " + hoursMinutes(d.negated())
            else -> "+ " + hoursMinutes(d)
        }

        /** "7h 42m" ("42m" under an hour); minutes are rounded, so 59.6 minutes reads "1h 0m". */
        fun hoursMinutes(seconds: Double?): String? {
            if (seconds == null || !seconds.isFinite() || seconds < 0) return null
            val minutes = (seconds / 60).roundToInt()
            return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
        }
    }
}
