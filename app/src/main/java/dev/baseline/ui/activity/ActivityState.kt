package dev.baseline.ui.activity

import com.garmin.fit.ExerciseCategory
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.StrengthSetEntity
import dev.baseline.data.db.SwimLengthEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.fit.ActivityStreams
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.today.prettyType
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One summary tile; "—" in [value] when missing. [sub] is a second muted line ("Garmin load 112"). */
data class Tile(val label: String, val value: String, val sub: String? = null, val accent: Boolean = false)

data class ActivityHeader(val title: String, val subtitle: String, val family: SportFamily)

/** A chart line plus the muted caption beside its title (e.g. "avg 27.1 · max 51.2 km/h"). */
class Series(val points: List<Pair<Instant, Number>>, val caption: String)

/** One row per km (mile when imperial) plus a remainder row; [bar] is 0.4..1, faster is longer. */
data class SplitRow(val label: String, val paceSec: Double, val pace: String, val hr: String, val elev: String, val bar: Float)

/** [paceSec100] is seconds per 100 m whatever the display unit; [pace] is formatted. */
data class LengthRow(val n: Int, val stroke: String, val strokes: String, val time: String, val paceSec100: Double?, val pace: String)

data class ExerciseUi(val name: String, val summary: String, val sets: List<String>)

enum class SectionKind { MAP, HR, ZONES, SPLITS, RUNNING, RIDE, SWIM, STRENGTH }

sealed interface Section {
    val kind: SectionKind

    data class Map(val latLon: List<Pair<Double, Double>>) : Section {
        override val kind get() = SectionKind.MAP
    }
    data class Hr(val series: List<Pair<Instant, Number>>, val avg: Int?, val max: Int?, val ticks: List<String>) : Section {
        override val kind get() = SectionKind.HR
    }
    data class Zones(val secondsPerZone: List<Double?>) : Section {
        override val kind get() = SectionKind.ZONES
    }
    data class Splits(val unit: String, val rows: List<SplitRow>) : Section {
        override val kind get() = SectionKind.SPLITS
    }
    data class Ride(val speed: Series?, val power: Series?, val elevation: Series?) : Section {
        override val kind get() = SectionKind.RIDE
    }
    data class Swim(val lengths: List<LengthRow>, val pacePer100: Double?, val summary: String) : Section {
        override val kind get() = SectionKind.SWIM
    }
    data class Strength(val exercises: List<ExerciseUi>) : Section {
        override val kind get() = SectionKind.STRENGTH
    }
    data class Running(val cadence: String, val power: String, val stride: String) : Section {
        override val kind get() = SectionKind.RUNNING
    }
}

/** [header] is null while [loading] and when the activity does not exist. [note] explains missing detail data. */
data class ActivityUiState(
    val header: ActivityHeader?,
    val summary: List<Tile>,
    val sections: List<Section>,
    val note: String? = null,
    val loading: Boolean = false,
)

/**
 * The detail sections for a sport, in order. [hasPower] is part of the contract but changes nothing here: power is a chart
 * inside Ride and a figure inside Running, each shown (or dashed) by its own builder.
 */
@Suppress("UNUSED_PARAMETER")
fun sectionsFor(family: SportFamily, hasGps: Boolean, hasPower: Boolean): List<SectionKind> {
    val map = if (hasGps) listOf(SectionKind.MAP) else emptyList()
    return when (family) {
        SportFamily.RUN -> map + listOf(SectionKind.HR, SectionKind.ZONES, SectionKind.SPLITS, SectionKind.RUNNING)
        SportFamily.RIDE -> map + listOf(SectionKind.RIDE, SectionKind.HR, SectionKind.ZONES)
        SportFamily.SWIM -> listOf(SectionKind.SWIM, SectionKind.HR, SectionKind.ZONES)
        SportFamily.STRENGTH -> listOf(SectionKind.STRENGTH, SectionKind.HR)
        SportFamily.OTHER -> map + listOf(SectionKind.HR, SectionKind.ZONES)
    }
}

const val NOTE_DOWNLOADING = "Detailed data downloading…"
const val NOTE_UNREADABLE = "Couldn't read this data"
const val NOTE_NONE = "No detailed data for this activity"
private const val DASH = "—"
private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

internal fun buildActivityState(
    activity: ActivityEntity?,
    score: ActivityScoreEntity?,
    stream: ActivityStreamEntity?,
    lengths: List<SwimLengthEntity>,
    sets: List<StrengthSetEntity>,
    fitTask: TaskStatus?,
    fmt: Formatters,
): ActivityUiState {
    if (activity == null) return ActivityUiState(null, emptyList(), emptyList())
    val unpacked = stream?.let { runCatching { ActivityStreams.unpack(it.packed) } }
    val s = unpacked?.getOrNull()
    val note = when {
        s != null -> null
        unpacked != null || fitTask == TaskStatus.FAILED -> NOTE_UNREADABLE
        fitTask == TaskStatus.EMPTY -> NOTE_NONE
        else -> NOTE_DOWNLOADING
    }
    val activeSets = sets.filter { it.setType == "ACTIVE" }
    val activeLengths = lengths.filter { it.active }
    val route = s?.let(::routePoints).orEmpty()
    val sections = sectionsFor(activity.sportFamily, route.size >= 2, s?.powerW != null).mapNotNull { kind ->
        when (kind) {
            SectionKind.MAP -> Section.Map(route)
            SectionKind.HR -> s?.let { hrSection(activity, it, fmt) }
            SectionKind.ZONES -> zonesSection(activity)
            SectionKind.SPLITS -> s?.let { splitsSection(it, fmt) }
            SectionKind.RUNNING -> s?.let { runningSection(it, activity) }
            SectionKind.RIDE -> s?.let { rideSection(activity, it, fmt) }
            SectionKind.SWIM -> swimSection(activity, activeLengths, fmt)
            SectionKind.STRENGTH -> strengthSection(activeSets, fmt)
        }
    }
    return ActivityUiState(
        ActivityHeader(
            activity.name?.takeIf { it.isNotBlank() } ?: prettyType(activity.typeKey),
            activity.startTimeLocal.format(DAY) + " · " + fmt.clock(activity.startTimeLocal),
            activity.sportFamily,
        ),
        summaryTiles(activity, score, activeLengths.size, sections.filterIsInstance<Section.Swim>().firstOrNull()?.pacePer100, activeSets, fmt),
        sections,
        note,
    )
}

/** [swimPace] is seconds per 100 m of actual swimming (rests left out), as the lengths list shows it; it wins over elapsed time. */
private fun summaryTiles(
    a: ActivityEntity,
    score: ActivityScoreEntity?,
    lengths: Int,
    swimPace: Double?,
    sets: List<StrengthSetEntity>,
    fmt: Formatters,
): List<Tile> {
    fun tile(label: String, value: String?) = Tile(label, value ?: DASH)
    val time = tile("time", fmt.duration(a.durationSec))
    val avgHr = tile("avg HR bpm", a.avgHr?.takeIf { it.isFinite() && it > 0 }?.roundToInt()?.toString())
    val maxHr = tile("max HR bpm", a.maxHr?.takeIf { it.isFinite() && it > 0 }?.roundToInt()?.toString())
    val distance = tile("distance ${fmt.distanceUnit}", fmt.distanceValue(a.distanceM))
    val elev = tile("elev gain ${fmt.elevationUnit}", fmt.elevation(a.elevationGainM?.takeIf { it > 0 }))
    val strain = Tile(
        "strain",
        score?.strain?.takeIf { it.isFinite() }?.let { "%.1f".format(Locale.US, it) } ?: DASH,
        "Garmin load " + (a.garminLoad?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: DASH),
        accent = true,
    )
    val volumeKg = sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
    return when (a.sportFamily) {
        SportFamily.RUN -> listOf(
            distance,
            time,
            tile("avg pace ${fmt.paceUnit}", fmt.pace(fmt.secondsPerUnit(a.distanceM, a.durationSec))),
            avgHr,
            elev,
            strain,
        )

        SportFamily.RIDE -> listOf(
            distance,
            time,
            tile("avg speed ${fmt.speedUnit}", fmt.speedValue(a.distanceM?.let { d -> a.durationSec.takeIf { it > 0 }?.let { d / it } })),
            avgHr,
            elev,
            strain,
        )

        SportFamily.SWIM -> listOf(
            tile("distance ${fmt.swimDistanceUnit}", fmt.swimDistance(a.distanceM)),
            time,
            tile("avg pace ${fmt.swimPaceUnit}", fmt.swimPace(swimPace ?: a.distanceM?.takeIf { it > 0 }?.let { a.durationSec / it * 100 })),
            avgHr,
            tile("lengths", lengths.takeIf { it > 0 }?.toString()),
            strain,
        )

        SportFamily.STRENGTH -> listOf(
            time,
            tile("sets", sets.size.takeIf { it > 0 }?.toString()),
            strain,
            tile("volume ${fmt.weightUnit}", volumeKg.takeIf { it > 0 }?.let(fmt::volume)),
            avgHr,
            maxHr,
        )

        SportFamily.OTHER -> listOf(distance, time, avgHr, maxHr, elev, strain)
    }
}

private fun hrSection(a: ActivityEntity, s: ActivityStreams, fmt: Formatters): Section.Hr? {
    val pts = series(s) { i -> s.hr[i].takeIf { it > 0 }?.toDouble() }
    if (pts.size < 2) return null
    val values = s.hr.filter { it > 0 }
    val elapsed = (s.epochSec.last() - s.epochSec.first()).toDouble()
    return Section.Hr(
        pts,
        (a.avgHr?.takeIf { it > 0 } ?: values.average()).roundToInt(),
        (a.maxHr?.takeIf { it > 0 }?.roundToInt() ?: values.max()),
        (0..4).map { fmt.duration(elapsed * it / 4) ?: "0:00" },
    )
}

private fun zonesSection(a: ActivityEntity): Section.Zones? {
    val z = listOf(a.zone1Sec, a.zone2Sec, a.zone3Sec, a.zone4Sec, a.zone5Sec).map { it?.takeIf { v -> v.isFinite() && v >= 0 } }
    return Section.Zones(z).takeIf { z.sumOf { it ?: 0.0 } > 0 }
}

private fun splitsSection(s: ActivityStreams, fmt: Formatters): Section.Splits? {
    val unitM = if (fmt.imperial) Formatters.METERS_PER_MILE else 1000.0
    val raw = computeSplits(s, unitM)
    if (raw.isEmpty()) return null
    val paces = raw.map { if (it.seconds > 0) it.seconds / it.distanceM * unitM else 0.0 }
    val valid = paces.filter { it > 0 }
    val fast = valid.minOrNull() ?: 0.0
    val slow = valid.maxOrNull() ?: 0.0
    return Section.Splits(
        fmt.distanceUnit,
        raw.mapIndexed { i, r ->
            val p = paces[i]
            SplitRow(
                if (r.distanceM >= unitM - 0.5) (i + 1).toString() else "%.2f".format(Locale.US, r.distanceM / unitM),
                p,
                fmt.pace(p) ?: DASH,
                r.avgHr?.toString() ?: DASH,
                fmt.elevationDelta(r.elevChangeM) ?: DASH,
                if (p <= 0) {
                    0.4f
                } else if (slow - fast < 1e-6) {
                    0.7f
                } else {
                    (0.4 + 0.6 * (slow - p) / (slow - fast)).toFloat()
                },
            )
        },
    )
}

private fun runningSection(s: ActivityStreams, a: ActivityEntity): Section.Running {
    val r = runningDynamics(s, a.avgPower)
    return Section.Running(
        r.cadenceSpm?.toString() ?: DASH,
        r.powerW?.toString() ?: DASH,
        r.strideM?.let { "%.2f".format(Locale.US, it) } ?: DASH,
    )
}

private fun rideSection(a: ActivityEntity, s: ActivityStreams, fmt: Formatters): Section.Ride? {
    val speedPts = s.speedMs?.let { v -> series(s, average = true) { i -> fmt.speedNumber(v[i].takeIf(Float::valid)?.toDouble()) } }.orEmpty()
    val speed = speedPts.takeIf { it.size >= 2 }?.let { pts ->
        val avgSpeed = a.distanceM?.let { d -> a.durationSec.takeIf { it > 0 }?.let { d / it } }
        // The max comes from the raw samples: averaged buckets would understate the peak.
        val max = s.speedMs?.filter(Float::valid)?.maxOrNull()?.let { fmt.speedNumber(it.toDouble()) }
        Series(pts, "avg ${fmt.speedValue(avgSpeed) ?: DASH} · max ${max?.let { "%.1f".format(Locale.US, it) } ?: DASH} ${fmt.speedUnit}")
    }
    val power = s.powerW?.takeIf { p -> p.any { it > 0 } }?.let { p ->
        val pts = series(s) { i -> p[i].takeIf { it >= 0 }?.toDouble() }
        val avg = a.avgPower?.takeIf { it > 0 } ?: p.filter { it > 0 }.average()
        Series(pts, "avg ${avg.roundToInt()} · NP ${a.normPower?.takeIf { it > 0 }?.roundToInt() ?: DASH} W")
    }
    val elevation = s.altitudeM?.let { alt ->
        val pts = series(s) { i -> fmt.elevationNumber(alt[i].takeIf(Float::valid)?.toDouble()) }
        pts.takeIf { it.size >= 2 }?.let { p ->
            Series(
                p,
                "%d–%d %s".format(
                    Locale.US,
                    p.minOf {
                        it.second.toDouble()
                    }.roundToInt(),
                    p.maxOf { it.second.toDouble() }.roundToInt(),
                    fmt.elevationUnit,
                ),
            )
        }
    }
    return if (speed == null && power == null && elevation == null) null else Section.Ride(speed, power, elevation)
}

private fun swimSection(a: ActivityEntity, active: List<SwimLengthEntity>, fmt: Formatters): Section.Swim? {
    if (active.isEmpty()) return null
    val pool = a.poolLengthM?.takeIf { it > 0 }
    val rows = active.mapIndexed { i, l ->
        val dur = l.durationSec?.takeIf { it > 0 }
        val pace = if (pool != null && dur != null) dur / pool * 100 else null
        LengthRow(
            i + 1,
            l.strokeType?.let(::titleCase) ?: DASH,
            l.strokeCount?.toString() ?: DASH,
            fmt.duration(dur) ?: DASH,
            pace,
            fmt.swimPace(pace) ?: DASH,
        )
    }
    val timed = active.mapNotNull { it.durationSec?.takeIf { d -> d > 0 } }
    val overall = if (pool != null && timed.size == active.size) timed.sum() / (active.size * pool) * 100 else null
    val summary = listOfNotNull(
        pool?.let { "${(it / if (fmt.imperial) Formatters.METERS_PER_YARD else 1.0).roundToInt()} ${fmt.swimDistanceUnit} pool" },
        "${active.size} ${if (active.size == 1) "length" else "lengths"}",
        fmt.swimPace(overall)?.let { "$it ${fmt.swimPaceUnit}" },
    ).joinToString(" · ")
    return Section.Swim(rows, overall, summary)
}

private fun strengthSection(active: List<StrengthSetEntity>, fmt: Formatters): Section.Strength? {
    if (active.isEmpty()) return null
    val groups = active.groupBy { exerciseName(it.category) } // groupBy keeps first-appearance order
    return Section.Strength(
        groups.map { (name, sets) ->
            val chips = sets.map { st ->
                val reps = st.reps?.takeIf { it > 0 }
                val kg = st.weightKg?.takeIf { it > 0 }
                when {
                    reps != null && kg != null -> "$reps × ${fmt.weight(kg)}"
                    reps != null -> "$reps"
                    else -> fmt.duration(st.durationSec) ?: DASH
                }
            }
            val volume = sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
            val tail = when {
                volume > 0 -> " · ${fmt.volume(volume)} ${fmt.weightUnit}"
                sets.any { (it.reps ?: 0) > 0 } -> " · bodyweight"
                else -> ""
            }
            ExerciseUi(name, "${sets.size} ${if (sets.size == 1) "set" else "sets"}$tail", chips)
        },
    )
}

private fun exerciseName(category: Int?): String =
    category?.let { ExerciseCategory.getStringFromValue(it) }?.takeIf { it.isNotBlank() && it != "UNKNOWN" }?.let(::titleCase) ?: "Exercise"

/** "BENCH_PRESS" and "individual_medley" -> "Bench Press", "Individual Medley". */
private fun titleCase(s: String) = s.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
