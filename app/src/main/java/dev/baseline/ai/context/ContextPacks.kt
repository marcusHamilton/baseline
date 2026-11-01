package dev.baseline.ai.context

import com.garmin.fit.ExerciseCategory
import dev.baseline.data.Repository
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.HrPacking
import dev.baseline.data.db.LapEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.fit.ActivityStreams
import dev.baseline.prefs.UserPrefs
import dev.baseline.records.BestEfforts
import dev.baseline.scores.Baseline
import dev.baseline.scores.Baselines
import dev.baseline.scores.FormState
import dev.baseline.scores.Recovery
import dev.baseline.scores.RecoveryZone
import dev.baseline.scores.SleepPerformance
import dev.baseline.scores.TrainingLoad
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.activity.runningDynamics
import dev.baseline.ui.components.formPctText
import dev.baseline.ui.components.formStateLabel
import dev.baseline.ui.components.weekLine
import dev.baseline.ui.format.Formatters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

enum class PackSize { COMPACT, FULL }

/** [stageFurthest] is the sleep stage furthest from its 30-day average ("deep", "light" or "REM") and whether it is above it. */
data class PackFacts(val recovery: Int?, val sleepScore: Int?, val stageFurthest: Pair<String, Boolean>?, val rangeLabel: String?)

/**
 * [basis] is [text] without its volatile lines: the running totals of a day still in progress (steps, strain so far, Body Battery
 * latest and drained, average stress, the week's percentage). The insight cache fingerprints the basis, so those totals moving on
 * every sync neither regenerate an insight nor change its text (spec §7.1). It equals [text] for every other pack.
 */
data class Pack(val text: String, val facts: PackFacts, val hasData: Boolean, val basis: String = text)

/** How long Room changes must be quiet before an insight's basis is rebuilt (spec §7.1). */
const val BASIS_SETTLE_MILLIS = 3_000L

/**
 * [basis] after each of [reads]: the first read at once, later ones only after [settleMillis] without another, so a burst of
 * writes costs one rebuild. Emits when the basis differs from the last one emitted.
 */
@OptIn(FlowPreview::class)
internal fun settledBasis(reads: Flow<Unit>, settleMillis: Long, basis: suspend () -> String): Flow<Unit> = flow {
    var seen = 0
    emitAll(reads.debounce { if (seen++ == 0) 0L else settleMillis }.map { basis() }.distinctUntilChanged().map { })
}

/** Four characters to a token. */
fun estimateTokens(text: String): Int = (text.length + 3) / 4

private const val NO_DATA = "no data"
private const val MAX_LAPS = 20
private const val MAX_EXERCISES = 12
private const val MAX_SET_CHIPS = 8

/** A ranking among fewer sessions than this says nothing ("fastest of 3 runs" read as a personal best). */
private const val MIN_RANK_PEERS = 4

/** A day's strain this far from its 30-day average, either way, is a link worth naming. */
private const val NOTABLE_STRAIN = 3.0
private const val LATE_WORKOUT_SECONDS = 3 * 3600L
private const val BEDTIME_SHIFT_MINUTES = 45
private const val SLEEP_DEBT_SECONDS = 90 * 60.0
private const val HRV_BAND = 0.05
private const val HRV_RUN_DAYS = 3
private const val GLOSSARY =
    "Glossary: Recovery 0–100% (green ≥67, amber 34–66, red ≤33) from HRV, resting HR, sleep and breathing vs 30-day baselines.\n" +
        "Strain 0–21 (90th-percentile day ≈ 17). Sleep performance = sleep vs need. Fitness/Fatigue = 42/7-day load averages;\n" +
        "Form = fitness − fatigue; week state Overreaching/Productive/Maintaining/Fresh/Detraining."

private val FULL_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.US)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

private val HRV: (DayEntity) -> Double? = { it.hrvLastNight?.takeIf { v -> v > 0 && v.isFinite() } }
private val RHR: (DayEntity) -> Double? = { it.restingHr?.takeIf { v -> v > 0 }?.toDouble() }
private val SLEEP: (DayEntity) -> Double? = { it.sleepSeconds?.takeIf { v -> v > 0 }?.toDouble() }
private val RESP: (DayEntity) -> Double? = { it.respirationAvg?.takeIf { v -> v > 0 && v.isFinite() } }

/**
 * Plain-text summaries of the user's data for the AI engines (spec §5.1). Dates and local times come from [today] and
 * [zone], never the system clock or zone. Nothing here reads GPS, routes, activity names, device or Garmin IDs.
 */
class ContextPacks(
    private val db: AppDatabase,
    private val repository: Repository,
    private val userPrefs: UserPrefs,
    private val today: StateFlow<LocalDate>,
    private val zone: StateFlow<ZoneId>,
    private val settleMillis: Long = BASIS_SETTLE_MILLIS,
) {
    /**
     * Emits once when collected and again each time the compact pack's basis for [scope] changes, driven by the Room flows the
     * scope reads (so a change that only touches volatile lines emits nothing). Changes settle for [settleMillis] first, so a
     * sync writing several rows costs one rebuild and one regeneration; the first emission is immediate. It never completes.
     * Used by the insight cards.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun basisChanges(scope: AskScope): Flow<Unit> {
        val reads: Flow<Unit> = when (scope) {
            is AskScope.Today -> todayReads(scope.date)

            AskScope.General -> today.flatMapLatest(::todayReads)

            is AskScope.Sleep -> nightReads(scope.date)

            is AskScope.Activity ->
                repository.activity(scope.id).map { it?.day }.distinctUntilChanged().flatMapLatest { date ->
                    val around = date?.let {
                        combine(repository.days(it.minusDays(1), it.plusDays(1)), repository.scores(it.minusDays(1), it.plusDays(1))) { _, _ -> }
                    }
                    combine(repository.activity(scope.id), repository.activityScore(scope.id), around ?: flowOf(Unit)) { _, _, _ -> }
                }

            is AskScope.Trends -> return flowOf(Unit)
        }
        return settledBasis(reads, settleMillis) { build(scope, PackSize.COMPACT).basis }
    }

    private fun dayReads(date: LocalDate): Flow<Unit> =
        combine(repository.day(date), repository.score(date), repository.activitiesOn(date), repository.activityScores(date)) { _, _, _, _ -> }

    /** The rows a night's Links read: the nights before it (bedtimes, short sleep) and the day before with its activities. */
    private fun nightReads(wake: LocalDate): Flow<Unit> =
        combine(repository.days(wake.minusDays(14), wake), repository.scores(wake.minusDays(1), wake), dayReads(wake.minusDays(1))) { _, _, _ -> }

    private fun todayReads(date: LocalDate): Flow<Unit> = combine(dayReads(date), nightReads(date)) { _, _ -> }

    /** [AskScope.General] builds Today's pack for the current date. */
    suspend fun build(scope: AskScope, size: PackSize): Pack {
        val fmt = Formatters(repository.imperial.first())
        return when (scope) {
            is AskScope.Today -> todayPack(scope.date, size, fmt)

            AskScope.General -> todayPack(today.value, size, fmt)

            is AskScope.Sleep -> {
                val sleep = sleep(scope.date, size)
                val facts = PackFacts(sleep?.recovery, sleep?.day?.sleepScore, sleep?.furthest, null)
                pack("sleep, night of ${scope.date.minusDays(1).format(DAY)}.", sleep?.body, "No sleep recorded for this night.", facts, fmt)
            }

            is AskScope.Activity -> {
                val a = db.activityDao().get(scope.id)
                pack("activity, ${a?.let(::heading) ?: "not found"}.", a?.let { activity(it, size, fmt) }, "No data for this activity.", NO_FACTS, fmt)
            }

            is AskScope.Trends -> trendsPack(scope.days, size, fmt)
        }
    }

    /** The Activity pack without its header, or null when there is no such activity. Used by the `activity_detail` tool. */
    suspend fun activityBody(id: Long, size: PackSize): String? = db.activityDao().get(id)?.let { activity(it, size, Formatters(repository.imperial.first())) }

    /** The Sleep pack without its header for the night that ended on [date] (the wake date), or null when it has no sleep. Used by `sleep_night`. */
    suspend fun sleepBody(date: LocalDate, size: PackSize): String? = sleep(date, size)?.body

    private fun pack(scope: String, body: String?, missing: String, facts: PackFacts, fmt: Formatters, basisBody: String? = body): Pack {
        val header =
            "Today is ${today.value.format(FULL_DATE)} (${zone.value.id}). Units: ${if (fmt.imperial) "imperial" else "metric"}.\n" +
                "Scope: $scope\n$GLOSSARY\n"
        return Pack(header + (body ?: missing), facts, hasData = body != null, basis = header + (basisBody ?: missing))
    }

    /**
     * The lines of a pack body. [add] puts a line in the text, and in the basis as [stable]: the line itself by default, null to
     * leave it out of the basis, or only the part of it that doesn't move.
     */
    private class Lines {
        private val text = mutableListOf<String>()
        private val basis = mutableListOf<String>()

        fun add(line: String, stable: String? = line) {
            text += line
            stable?.let { basis += it }
        }

        fun text() = text.joinToString("\n")

        fun basis() = basis.joinToString("\n")
    }

    // ---- Today ----

    private suspend fun todayPack(date: LocalDate, size: PackSize, fmt: Formatters): Pack {
        val day = db.dayDao().get(date)
        val score = db.scoreDao().get(date)
        val scope = (if (date == today.value) "today, " else "day, ") + date.format(DAY) + "."
        val facts = PackFacts(score?.recovery, day?.sleepScore, null, null)
        if (day == null && score == null) return pack(scope, null, "No data recorded for this day.", facts, fmt)
        val w = Window(date, repository.days(date.minusDays(30), date.minusDays(1)).first())
        // The day in progress has running totals; everything else about it, and every past day, is stable (see Pack.basis).
        val live = date == today.value
        val lines = Lines()

        fun running(line: String, stable: String? = null) = lines.add(line, if (live) stable else line)
        val sleepPerf = score?.sleepPerformance ?: SleepPerformance.of(day?.sleepSeconds, day?.sleepNeedMinutes)
        lines.add(recoveryLine(score))
        val top = score?.recovery?.let { day?.let { d -> Recovery.compute(d, w.recovery, sleepPerf) } }?.top.orEmpty()
        if (top.isNotEmpty()) lines.add("Main drivers vs baseline: " + top.joinToString(", ") { driver(it.metric, it.delta) })
        lines.add(metric("HRV", " ms", day?.let(HRV), w.hrv, ::whole, ::pct))
        lines.add(metric("Resting HR", " bpm", day?.let(RHR), w.rhr, ::whole, absolute(" bpm", 0)))
        lines.add(metric("Sleep", "", day?.let(SLEEP), w.mean(SLEEP), ::hm, ::hmDelta))
        lines.add("Sleep performance " + (sleepPerf?.takeIf { it.isFinite() }?.let { "${(it * 100).roundToInt()}%" } ?: NO_DATA) + garminScore(day))
        lines.add(metric("Respiration", " brpm", day?.let(RESP), w.mean(RESP), ::one, absolute(" brpm", 1)))
        lines.add(metric("Body Battery at wake", "", day?.bodyBatteryWake?.toDouble(), w.mean({ it.bodyBatteryWake?.toDouble() }), ::whole, absolute("", 0)))
        running(metric("Avg stress", "", day?.stressAvg?.toDouble(), w.mean({ it.stressAvg?.toDouble() }), ::whole, absolute("", 0)))
        val steps = metric("Steps", "", day?.steps?.toDouble(), w.mean({ it.steps?.toDouble() }, { it > 0 }), ::grouped, ::pct)
        running(if (live && day?.steps != null) "$steps, so far today" else steps)
        val load = load(date)
        val (strain, strainTarget) = strainLine(score, load?.first?.state, live)
        lines.add(strain, if (live) strainTarget else strain)
        val (battery, batteryCharged) = batteryLine(day, date)
        lines.add(battery, if (live) batteryCharged else battery)
        // Fitness and fatigue for a date are built from the days before it, so this line holds all day.
        lines.add(trainingLine(load?.first))
        load?.second?.let { running(weekLine(it)) }
        val activities = db.activityDao().byDay(date)
        if (activities.isEmpty()) {
            lines.add("Activities: none")
        } else {
            lines.add("Activities:")
            activities.forEach { lines.add("- " + describe(it, fmt, strain = db.activityScoreDao().get(it.id)?.strain)) }
        }
        val history = w.history
        links(nightLinks(date, day, history, fmt) + listOfNotNull(hrvRun(date, day, history, w.hrv))).forEach { lines.add(it) }
        return pack(scope, lines.text(), "", facts, fmt, basisBody = lines.basis())
    }

    private fun recoveryLine(score: ScoreEntity?): String {
        val r = score?.recovery ?: return "Recovery $NO_DATA"
        val zoneWord = RecoveryZone.of(r).name.lowercase()
        return "Recovery $r% ($zoneWord)" + if (score.recoveryPartial) ", partial: HRV or resting HR missing" else ""
    }

    private fun driver(metric: String, delta: Double): String = when (metric) {
        "hrv" -> "HRV ${pctText(delta.roundToInt())}"
        "rhr" -> "resting HR ${signed(delta.roundToInt())} bpm"
        "sleep" -> "sleep ${signed(delta.roundToInt())} pts"
        else -> "breathing ${signedDouble(delta, 1)} brpm"
    }

    private fun garminScore(day: DayEntity?) = day?.sleepScore?.let { " (Garmin sleep score $it)" }.orEmpty()

    /** The line, and the line without the running number ("Strain 8.4 so far; target 14.0–18.0" and "Strain target 14.0–18.0"). */
    private fun strainLine(score: ScoreEntity?, state: FormState?, isToday: Boolean): Pair<String, String> {
        if (score == null) return "Strain $NO_DATA".let { it to it }
        val stored = score.strainTargetLow?.let { lo -> score.strainTargetHigh?.let { hi -> lo..hi } }
        val eased = stored?.let { TrainingLoad.easedTarget(it, state) }
        val target =
            (eased ?: stored)?.let { "target ${one(it.start)}–${one(it.endInclusive)}" + if (eased != null) " (eased: overreaching week)" else "" }
                ?: "target $NO_DATA"
        return "Strain ${one(score.strain)}" + (if (isToday) " so far" else "") + "; $target" to "Strain $target"
    }

    /** The line, and the line without the latest level and what has drained so far. */
    private suspend fun batteryLine(day: DayEntity?, date: LocalDate): Pair<String, String> {
        val curve = repository.bodyBatteryCurve(date).first().map { it.second }
        val drained = if (curve.size >= 2) curve.zipWithNext { a, b -> maxOf(0, a - b) }.sum() else null
        val charged = "Body Battery: charged " + (day?.bodyBatteryCharged?.let { "+$it" } ?: NO_DATA)
        return charged + ", latest " + (curve.lastOrNull() ?: NO_DATA) + ", drained " + (drained?.let { "−$it" } ?: NO_DATA) to charged
    }

    private fun trainingLine(d: TrainingLoadDay?): String = when {
        d == null -> "Training load $NO_DATA"

        d.state == FormState.CALIBRATING -> "Training load: calibrating, ${d.calibratingDaysLeft} days left"

        d.state == FormState.NO_RECENT_TRAINING -> "Training load: no recent training"

        else -> "Training load: ${formStateLabel(
            d.state,
        )} (form ${d.formPct?.let(::formPctText) ?: NO_DATA}, fitness ${one(d.fitness)}, fatigue ${one(d.fatigue)})"
    }

    /** The day's training-load read and week budget, as the Today screen builds them; null before the first score. */
    private suspend fun load(date: LocalDate): Pair<TrainingLoadDay, WeekBudget?>? {
        val earliest = repository.historyStart.first().first
        if (earliest != null && date.isBefore(earliest)) return null
        val loads = repository.scores(date.minusDays(7 + TrainingLoad.WARM_UP_DAYS), date).first().associate { it.date to it.dayLoad }
        val tau = userPrefs.strainCalibration.first()?.tau
        val firstDay = repository.firstDayOfWeek.first()
        return TrainingLoad.series(loads, date, date, earliest, tau).single() to TrainingLoad.weekBudget(loads, date, firstDay, earliest, tau)
    }

    // ---- Sleep ----

    /** [deviation] is the night's minutes over the 30-day average, minus one; null without both figures. */
    private class Stage(val name: String, val seconds: Int?, val avg: Double?) {
        val deviation: Double? = if (seconds != null && avg != null && avg > 0) seconds / avg - 1 else null
    }

    private class SleepView(val body: String, val day: DayEntity, val recovery: Int?, val furthest: Pair<String, Boolean>?)

    /** [wake] is the date the night ended on, which is also its `DayEntity` row and the date the Sleep screen shows. */
    private suspend fun sleep(wake: LocalDate, size: PackSize): SleepView? {
        val night = wake.minusDays(1)
        val day = db.dayDao().get(wake)?.takeIf { SLEEP(it) != null } ?: return null
        val score = db.scoreDao().get(wake)
        val history = repository.days(wake.minusDays(30), wake.minusDays(1)).first()
        val w = Window(wake, history)
        val perf = score?.sleepPerformance ?: SleepPerformance.of(day.sleepSeconds, day.sleepNeedMinutes)
        val stages = listOf(
            Stage("deep", day.deepSeconds, w.mean({ it.deepSeconds?.toDouble() })),
            Stage("light", day.lightSeconds, w.mean({ it.lightSeconds?.toDouble() })),
            Stage("REM", day.remSeconds, w.mean({ it.remSeconds?.toDouble() })),
        )
        val furthest = stages
            .mapNotNull { s -> s.deviation?.let { s.name to it } }
            .maxByOrNull { abs(it.second) }
            ?.let { it.first to (it.second > 0) }
        val n = if (size == PackSize.FULL) 30 else 7
        val nights = repository.days(wake.minusDays(n - 1L), wake).first().associateBy { it.date }
        val scores = repository.scores(wake.minusDays(n - 1L), wake).first().associateBy { it.date }
        val body = buildList {
            add("Night of ${night.format(DAY)}, ending ${wake.format(DAY)}.")
            add(
                "Sleep performance " + (perf?.takeIf { it.isFinite() }?.let { "${(it * 100).roundToInt()}%" } ?: NO_DATA) +
                    ": ${hm(day.sleepSeconds!!.toDouble())} asleep vs need " + (day.sleepNeedMinutes?.takeIf { it > 0 }?.let { hm(it * 60.0) } ?: NO_DATA) +
                    ". Garmin score ${day.sleepScore ?: NO_DATA}.",
            )
            add("Bed ${clock(day.sleepStart)}, wake ${clock(day.sleepEnd)}.")
            for (s in stages) {
                val tail = s.deviation?.let { " (30-day avg ${hm(s.avg!!)}, ${pctText((it * 100).roundToInt())})" }.orEmpty()
                add("${s.name.replaceFirstChar(Char::uppercase)} sleep ${s.seconds?.let { hm(it.toDouble()) } ?: NO_DATA}$tail")
            }
            add("Awake ${day.awakeSeconds?.let { hm(it.toDouble()) } ?: NO_DATA}")
            add(metric("Overnight HRV", " ms", HRV(day), w.hrv, ::whole, ::pct))
            add("Lowest HR " + (lowestHr(day)?.let { "$it bpm" } ?: NO_DATA))
            add("Body Battery charged " + (day.bodyBatteryCharged?.let { "+$it" } ?: NO_DATA))
            add("SpO2 " + (day.spo2Avg?.takeIf { it > 0 && it.isFinite() }?.let { "${it.roundToInt()}%" } ?: NO_DATA))
            add(metric("Respiration", " brpm", RESP(day), w.mean(RESP), ::one, absolute(" brpm", 1)))
            score?.recovery?.let { add("Recovery the morning after: $it% (${RecoveryZone.of(it).name.lowercase()})") }
            val overreaching = load(wake)?.first?.state == FormState.OVERREACHING
            addAll(
                links(
                    nightLinks(wake, day, history, Formatters(repository.imperial.first())) +
                        listOfNotNull("Training load is overreaching.".takeIf { overreaching }),
                ),
            )
            if (size == PackSize.COMPACT) {
                // A small model reads rows poorly: one line with the averages (spec §5.1).
                val rows = nights.values.filter { SLEEP(it) != null }
                val perfs = rows.mapNotNull { r ->
                    (scores[r.date]?.sleepPerformance ?: SleepPerformance.of(r.sleepSeconds, r.sleepNeedMinutes))?.takeIf { it.isFinite() }
                }
                if (rows.isNotEmpty()) {
                    add(
                        "Last $n nights: average ${hm(rows.mapNotNull(SLEEP).average())} asleep" +
                            (perfs.takeIf { it.isNotEmpty() }?.let { ", performance ${(it.average() * 100).roundToInt()}%" }.orEmpty()) + ".",
                    )
                }
                return@buildList
            }
            add("Last $n nights:")
            for (i in n - 1 downTo 0) {
                val d = wake.minusDays(i.toLong())
                val row = nights[d]?.takeIf { SLEEP(it) != null }
                val p = scores[d]?.sleepPerformance ?: row?.let { SleepPerformance.of(it.sleepSeconds, it.sleepNeedMinutes) }
                val label = "- night of ${d.minusDays(1).format(DAY)}: "
                add(
                    if (row ==
                        null
                    ) {
                        label + NO_DATA
                    } else {
                        label + hm(row.sleepSeconds!!.toDouble()) + ", performance " +
                            (p?.let { "${(it * 100).roundToInt()}%" } ?: NO_DATA)
                    },
                )
            }
        }.joinToString("\n")
        return SleepView(body, day, score?.recovery, furthest)
    }

    private suspend fun lowestHr(day: DayEntity): Int? {
        val start = day.sleepStart ?: return null
        val end = day.sleepEnd ?: return null
        val date = day.date
        val points = listOf(date.minusDays(1), date).flatMap { d -> db.hrDayDao().get(d)?.let { HrPacking.unpack(it.packed) }.orEmpty() }
        return points.filter { it.epochSec in start.epochSecond..end.epochSecond }.minOfOrNull { it.bpm }
    }

    private fun clock(at: Instant?) = at?.atZone(zone.value)?.format(CLOCK) ?: NO_DATA

    // ---- Links across pages ----

    /** The Links block of a pack (spec §5.1): what other pages say about this one, or a line saying nothing stood out. */
    private fun links(found: List<String>): List<String> = if (found.isEmpty()) {
        listOf("Links: nothing unusual on the days around it.")
    } else {
        listOf("Links (from other pages; possible causes, not proof):") + found.map { "- $it" }
    }

    /** A day's strain and the average of the 30 days before it; null without a score or a week of history. */
    private class DayStrain(val strain: Double, val usual: Double) {
        val diff get() = strain - usual
    }

    private suspend fun dayStrain(date: LocalDate): DayStrain? {
        val strain = db.scoreDao().get(date)?.strain ?: return null
        val usual = repository.scores(date.minusDays(30), date.minusDays(1)).first().map { it.strain }.takeIf { it.size >= 7 }?.average()
        return usual?.let { DayStrain(strain, it) }
    }

    /** "The day before (Sat 31 Oct) was much harder than usual: …" when its strain is [NOTABLE_STRAIN] or more from its average. */
    private suspend fun dayBefore(date: LocalDate, fmt: Formatters): String? {
        val before = date.minusDays(1)
        val l = dayStrain(before)?.takeIf { abs(it.diff) >= NOTABLE_STRAIN } ?: return null
        val activities = db.activityDao().byDay(before).map { describe(it, fmt, db.activityScoreDao().get(it.id)?.strain) }
        return "The day before (${before.format(DAY)}) was ${if (l.diff > 0) "harder" else "lighter"} than usual: day strain " +
            "${one(l.strain)} vs 30-day average ${one(l.usual)}" + activities.joinToString("") { "; $it" } + "."
    }

    /** Seconds from [a]'s end to [bed] when it ended no more than [LATE_WORKOUT_SECONDS] before it, else null. */
    private fun beforeBed(a: ActivityEntity, bed: Instant?): Long? {
        val gap = bed?.epochSecond?.minus(a.startGmt.epochSecond + a.durationSec.toLong()) ?: return null
        return gap.takeIf { it in 0..LATE_WORKOUT_SECONDS }
    }

    /** Minutes after noon, so bedtimes either side of midnight compare (23:30 is 690, 00:30 is 750). */
    private fun bedMinutes(at: Instant?): Int? = at?.atZone(zone.value)?.toLocalTime()?.let { (it.toSecondOfDay() / 60 + 720) % 1440 }

    /**
     * What explains the night that ended on [wake] (its row is [day]), worked out here because a small model can't find it in rows:
     * the day before against its usual strain, a workout that ended close to bed, the bedtime against the usual one, and a run of
     * short nights before it. A line is written only when it clears its threshold. [history] holds the days before [wake].
     * When one of those strains showed up, a last line says whether the night's HRV dropped as they usually make it, or held up:
     * Nano otherwise credited a hard day for a good night.
     */
    private suspend fun nightLinks(wake: LocalDate, day: DayEntity?, history: List<DayEntity>, fmt: Formatters): List<String> = buildList {
        var strains = 0
        dayBefore(wake, fmt)?.let {
            add(it)
            if (" harder " in it) strains++
        }
        val bed = day?.sleepStart
        for (a in db.activityDao().byDay(wake.minusDays(1))) {
            val gap = beforeBed(a, bed) ?: continue
            val strain = db.activityScoreDao().get(a.id)?.strain?.let { ", strain ${one(it)}" }.orEmpty()
            add("${sportLabel(a)}$strain, ended ${hm(gap.toDouble())} before bed.")
            strains++
        }
        val mine = bedMinutes(bed)
        val recent = history.filter { it.date >= wake.minusDays(14) }.mapNotNull { bedMinutes(it.sleepStart) }.sorted()
        val usual = recent.takeIf { it.size >= 5 }?.let { it[it.size / 2] }
        if (mine != null && usual != null && abs(mine - usual) >= BEDTIME_SHIFT_MINUTES) {
            val usualClock = java.time.LocalTime.ofSecondOfDay(((usual + 720) % 1440) * 60L).format(CLOCK)
            val shift = "${hm(abs(mine - usual) * 60.0)} ${if (mine > usual) "later" else "earlier"}"
            add("Bed ${clock(bed)}, $shift than usual ($usualClock, the median of the last 14 nights).")
            if (mine > usual) strains++
        }
        val short = (1L..3L).mapNotNull { i ->
            history.firstOrNull { it.date == wake.minusDays(i) }?.let { d ->
                d.sleepSeconds?.let { s -> d.sleepNeedMinutes?.takeIf { it > 0 }?.let { it * 60.0 - s } }
            }
        }
        if (short.size == 3 && short.sum() >= SLEEP_DEBT_SECONDS) {
            add("The 3 nights before were short: ${hm(short.sum())} under need in total.")
            strains++
        }
        val hrv = day?.let(HRV)
        val base = Window(wake, history).hrv
        if (strains > 0 && hrv != null && base != null && base > 0) {
            val change = pctText(((hrv / base - 1) * 100).roundToInt())
            add(
                if (hrv < base * (1 - HRV_BAND)) {
                    "Overnight HRV came in $change vs baseline, as these usually make it."
                } else {
                    "Despite these, overnight HRV held up ($change vs baseline): they don't show a toll."
                },
            )
        }
    }

    /** "HRV below baseline 4 days running" when [date] and the days before it sit more than [HRV_BAND] on the same side of [base]. */
    private fun hrvRun(date: LocalDate, day: DayEntity?, history: List<DayEntity>, base: Double?): String? {
        if (base == null) return null
        val byDate = history.associateBy { it.date } + listOfNotNull(day).associateBy { it.date }
        fun side(d: LocalDate): Int = byDate[d]?.let(HRV)?.let { v ->
            when {
                v < base * (1 - HRV_BAND) -> -1
                v > base * (1 + HRV_BAND) -> 1
                else -> 0
            }
        } ?: 0
        val s = side(date).takeIf { it != 0 } ?: return null
        val n = generateSequence(date) { it.minusDays(1) }.takeWhile { side(it) == s }.count()
        return if (n >= HRV_RUN_DAYS) "HRV ${if (s < 0) "below" else "above"} baseline $n days running." else null
    }

    /**
     * What surrounds an activity: what the user came in with (that morning's recovery and the night before), how hard the day before
     * and the whole day were, and how the night after and the next morning responded, once they are recorded.
     */
    private suspend fun activityLinks(a: ActivityEntity, fmt: Formatters): List<String> = buildList {
        val date = a.day
        val next = date.plusDays(1)
        val days = repository.days(date.minusDays(30), next).first()
        val rows = days.associateBy { it.date }
        val goingIn = listOfNotNull(
            db.scoreDao().get(date)?.recovery?.let { "recovery that morning $it% (${RecoveryZone.of(it).name.lowercase()})" },
            rows[date]?.let { d -> SLEEP(d)?.let { "sleep the night before ${hm(it)}" + needShare(d) } },
        )
        if (goingIn.isNotEmpty()) add("Going in: " + goingIn.joinToString("; ") + ".")
        dayBefore(date, fmt)?.let(::add)
        dayStrain(date)?.let { add("Day strain that day ${one(it.strain)} vs 30-day average ${one(it.usual)}.") }
        val after = rows[next]?.takeIf { SLEEP(it) != null }
        when {
            next.isAfter(today.value) -> add("The night after: not recorded yet.")

            after == null -> add("The night after: no sleep recorded.")

            else -> {
                val w = Window(next, days.filter { it.date < next })
                val parts = listOfNotNull(
                    "${hm(SLEEP(after)!!)} asleep" + needShare(after),
                    beforeBed(a, after.sleepStart)?.let { "bed ${hm(it.toDouble())} after it ended" },
                    metric("HRV", " ms", HRV(after), w.hrv, ::whole, ::pct),
                    metric("resting HR", " bpm", RHR(after), w.rhr, ::whole, absolute(" bpm", 0)),
                    db.scoreDao().get(next)?.recovery?.let { "recovery next morning $it% (${RecoveryZone.of(it).name.lowercase()})" },
                )
                add("The night after: " + parts.joinToString("; ") + ".")
            }
        }
    }

    /** ", 82% of need", or nothing without a need. */
    private fun needShare(d: DayEntity): String =
        SleepPerformance.of(d.sleepSeconds, d.sleepNeedMinutes)?.takeIf { it.isFinite() }?.let { ", ${(it * 100).roundToInt()}% of need" }.orEmpty()

    // ---- Activity ----

    private fun heading(a: ActivityEntity) = "${sportLabel(a)}, ${a.startTimeLocal.format(DAY_TIME)}"

    private fun sportLabel(a: ActivityEntity) = when (a.sportFamily) {
        SportFamily.RUN -> "Run"
        SportFamily.RIDE -> "Ride"
        SportFamily.SWIM -> "Swim"
        SportFamily.STRENGTH -> "Strength"
        SportFamily.OTHER -> titleCase(a.typeKey).ifEmpty { "Activity" }
    }

    /** "Run, Sun 1 Nov 07:05, 10.21 km · 52:14 · 5:07 /km, strain 9.1". */
    private fun describe(a: ActivityEntity, fmt: Formatters, strain: Double?): String = listOf(
        heading(a),
        fmt.meta(a.sportFamily, a.distanceM, a.durationSec, a.avgPower, null).ifEmpty { NO_DATA },
        "strain " + (strain?.let(::one) ?: NO_DATA),
    )
        .joinToString(", ")

    private suspend fun activity(a: ActivityEntity, size: PackSize, fmt: Formatters): String {
        val family = a.sportFamily
        val score = db.activityScoreDao().get(a.id)
        val stream = db.activityStreamDao().get(a.id)?.let { runCatching { ActivityStreams.unpack(it.packed) }.getOrNull() }
        val dist = a.distanceM?.takeIf { it.isFinite() && it > 0 }
        return buildList {
            add(heading(a) + " (${a.typeKey.replace('_', ' ')})")
            val distance = if (family == SportFamily.SWIM) dist?.let { "${fmt.swimDistance(it)} ${fmt.swimDistanceUnit}" } else fmt.distance(dist)
            val rate = rate(family, dist, a.durationSec, fmt)
            add(
                listOfNotNull(
                    "Duration ${fmt.duration(a.durationSec) ?: NO_DATA}",
                    if (family != SportFamily.STRENGTH) "distance ${distance ?: NO_DATA}" else null,
                    rate?.let { "average ${if (family == SportFamily.RIDE) "speed" else "pace"} $it" },
                ).joinToString(", ") + ".",
            )
            add("Average HR ${a.avgHr?.let { "${it.roundToInt()} bpm" } ?: NO_DATA}, max ${a.maxHr?.let { "${it.roundToInt()} bpm" } ?: NO_DATA}")
            val zones = listOf(a.zone1Sec, a.zone2Sec, a.zone3Sec, a.zone4Sec, a.zone5Sec)
            val zoneText = zones.mapIndexedNotNull { i, s -> s?.takeIf { it > 0 }?.let { "Z${i + 1} ${fmt.duration(it)}" } }
            add("Time in HR zones: " + zoneText.ifEmpty { listOf(NO_DATA) }.joinToString(", "))
            add("Baseline strain ${score?.strain?.let(::one) ?: NO_DATA}. Garmin training load ${a.garminLoad?.let { "${it.roundToInt()}" } ?: NO_DATA}.")
            a.elevationGainM?.takeIf { it > 0 }?.let { add("Elevation gain ${fmt.elevation(it)} ${fmt.elevationUnit}") }
            form(a, stream, fmt)?.let(::add)
            when (family) {
                SportFamily.SWIM -> add(swimSummary(a, fmt))
                SportFamily.STRENGTH -> addAll(strengthLines(a, fmt))
                else -> Unit
            }
            if (family != SportFamily.STRENGTH) {
                val laps = db.lapDao().forActivity(a.id)
                if (laps.isNotEmpty()) addAll(listOf("Laps (${laps.size}):") + lapRows(laps, family, fmt))
            }
            val all = db.activityDao().list()
            addAll(standing(a, all, fmt))
            addAll(links(activityLinks(a, fmt)))
            // Compact leaves the rows out: "Against your usual" sums them up, and a small model compares against a single row.
            val previous = all
                .filter { it.sportFamily == family && it.id != a.id && it.startGmt < a.startGmt }
                .sortedByDescending { it.startGmt }
                .take(if (size == PackSize.FULL) 10 else 0)
            if (previous.isNotEmpty()) {
                add("Previous ${sportLabel(a).lowercase()} sessions:")
                previous.forEach { p ->
                    add(
                        "- prev ${p.startTimeLocal.format(DAY_TIME)}: ${fmt.meta(family, p.distanceM, p.durationSec, p.avgPower, null).ifEmpty {
                            NO_DATA
                        }}, strain ${db.activityScoreDao().get(p.id)?.strain?.let(::one) ?: NO_DATA}",
                    )
                }
            }
        }.joinToString("\n")
    }

    /**
     * What makes this activity stand out in the user's history: the all-time records it set (best_effort) and where its distance,
     * duration and pace or speed rank among their other sessions of the sport, when it is in the top 3. Without these the model
     * can't know a run was a personal best. Pace is compared only against sessions of a similar distance (within a quarter).
     */
    private suspend fun standing(a: ActivityEntity, all: List<ActivityEntity>, fmt: Formatters): List<String> {
        val family = a.sportFamily
        if (family != SportFamily.RUN && family != SportFamily.RIDE && family != SportFamily.SWIM) return emptyList()
        val lines = mutableListOf<String>()
        // Longest distance (or power window) first: the biggest record is the headline, and a small model leads with the first item.
        val records = db.bestEffortDao().forSport(family).filter { it.activityId == a.id }.mapNotNull { e ->
            if (BestEfforts.isPower(e.effort)) {
                val sec = e.effort.removePrefix("power_").removeSuffix("s").toIntOrNull() ?: return@mapNotNull null
                sec to "best ${if (sec % 60 == 0) "${sec / 60} min" else "$sec s"} power ${e.valueSec.roundToInt()} W"
            } else {
                val m = e.effort.removeSuffix("m").toIntOrNull() ?: return@mapNotNull null
                m to "fastest ${fmt.distance(m.toDouble()) ?: return@mapNotNull null} in ${fmt.duration(e.valueSec) ?: return@mapNotNull null}"
            }
        }.sortedByDescending { it.first }.map { it.second }
        if (records.isNotEmpty()) lines.add("All-time records set in this activity: ${records.joinToString("; ")}.")

        val peers = all.filter { it.sportFamily == family && it.id != a.id }
        val noun = sportLabel(a).lowercase() + "s"
        fun distText(x: ActivityEntity) = x.distanceM?.let {
            if (family ==
                SportFamily.SWIM
            ) {
                "${fmt.swimDistance(it)} ${fmt.swimDistanceUnit}"
            } else {
                fmt.distance(it)
            }
        }
        fun rank(
            what: String,
            best: String,
            higher: Boolean,
            pool: List<ActivityEntity>,
            value: (ActivityEntity) -> Double?,
            show: (ActivityEntity) -> String?,
        ) {
            val mine = value(a)?.takeIf { it.isFinite() && it > 0 } ?: return
            val others = pool.mapNotNull { p -> value(p)?.takeIf { it.isFinite() && it > 0 }?.let { p to it } }
            if (others.size < MIN_RANK_PEERS) return
            val rank = others.count { if (higher) it.second > mine else it.second < mine } + 1
            if (rank > 3) return
            val nth = when (rank) {
                1 -> ""
                2 -> "2nd "
                else -> "3rd "
            }
            val previous = if (rank == 1) {
                val p = if (higher) others.maxBy { it.second }.first else others.minBy { it.second }.first
                show(p)?.let { ", previous $best $it on ${p.startTimeLocal.format(FULL_DATE)}" }.orEmpty()
            } else {
                ""
            }
            lines.add("$nth$best $what of your ${others.size + 1} $noun$previous.")
        }
        rank("distance", "longest", true, peers, { it.distanceM }, ::distText)
        rank("duration", "longest", true, peers, { it.durationSec }) { fmt.duration(it.durationSec) }
        val dist = a.distanceM?.takeIf { it > 0 }
        if (dist != null && family != SportFamily.SWIM) {
            val similar = peers.filter { p -> p.distanceM?.let { abs(it - dist) / dist <= 0.25 } == true }
            val speed = { x: ActivityEntity -> if (x.distanceM != null && x.durationSec != null && x.durationSec > 0) x.distanceM / x.durationSec else null }
            val show = { x: ActivityEntity -> rate(family, x.distanceM, x.durationSec, fmt) }
            rank("${if (family == SportFamily.RIDE) "speed" else "pace"} over similar distances", "fastest", true, similar, speed, show)
            versusTypical(a, similar, fmt)?.let(lines::add)
        }
        return lines
    }

    /**
     * "This one against your typical one", worked out here because a small model can't subtract: the median pace or speed, average
     * heart rate and strain of the last 10 sessions of a similar distance, and by how much this one differs.
     */
    private suspend fun versusTypical(a: ActivityEntity, similar: List<ActivityEntity>, fmt: Formatters): String? {
        val family = a.sportFamily
        val recent = similar.sortedByDescending { it.startGmt }.take(10)
        if (recent.size < 2) return null
        fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) null else (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
        val parts = mutableListOf<String>()
        if (family == SportFamily.RIDE) {
            val mine = a.distanceM?.let { d -> a.durationSec?.takeIf { it > 0 }?.let { d / it } }
            val typical = median(recent.mapNotNull { p -> p.distanceM?.let { d -> p.durationSec?.takeIf { it > 0 }?.let { d / it } } })
            if (mine != null && typical != null) {
                val diff = mine - typical
                parts.add(
                    "speed ${fmt.speedValue(
                        mine,
                    )} ${fmt.speedUnit}, ${fmt.speedValue(
                        abs(diff),
                    )} ${fmt.speedUnit} ${if (diff >= 0) "faster" else "slower"} than usual (${fmt.speedValue(typical)})",
                )
            }
        } else {
            val mine = fmt.secondsPerUnit(a.distanceM, a.durationSec)
            val typical = median(recent.mapNotNull { fmt.secondsPerUnit(it.distanceM, it.durationSec) })
            if (mine != null && typical != null) {
                val diff = (typical - mine).roundToInt()
                parts.add(
                    "pace ${fmt.pace(
                        mine,
                    )} ${fmt.paceUnit}, ${abs(diff)} s${fmt.paceUnit} ${if (diff >= 0) "faster" else "slower"} than usual (${fmt.pace(typical)})",
                )
            }
        }
        val hr = a.avgHr
        val typicalHr = median(recent.mapNotNull { it.avgHr })
        if (hr != null && typicalHr != null) {
            val diff = (hr - typicalHr).roundToInt()
            parts.add("average HR ${hr.roundToInt()} bpm, ${if (diff >= 0) "+" else "-"}${abs(diff)} bpm vs usual (${typicalHr.roundToInt()})")
        }
        val strain = db.activityScoreDao().get(a.id)?.strain
        val typicalStrain = median(recent.mapNotNull { db.activityScoreDao().get(it.id)?.strain })
        if (strain != null && typicalStrain != null) {
            val diff = strain - typicalStrain
            parts.add("strain ${one(strain)}, ${if (diff >= 0) "+" else "-"}${one(abs(diff))} vs usual (${one(typicalStrain)})")
        }
        return if (parts.isEmpty()) null else "Against your usual of your last ${recent.size} similar-distance sessions: ${parts.joinToString("; ")}."
    }

    private fun rate(family: SportFamily, distM: Double?, durSec: Double?, fmt: Formatters): String? {
        if (distM == null || durSec == null || distM <= 0 || durSec <= 0) return null
        return when (family) {
            SportFamily.RUN -> fmt.pace(fmt.secondsPerUnit(distM, durSec))?.let { "$it min${fmt.paceUnit}" }
            SportFamily.RIDE -> fmt.speedValue(distM / durSec)?.let { "$it ${fmt.speedUnit}" }
            SportFamily.SWIM -> fmt.swimPace(durSec / (distM / 100))?.let { "$it ${fmt.swimPaceUnit}" }
            else -> null
        }
    }

    /** Cadence, power and stride where present: running form for a run, power and cadence for a ride. */
    private fun form(a: ActivityEntity, stream: ActivityStreams?, fmt: Formatters): String? {
        val parts =
            when (a.sportFamily) {
                SportFamily.RUN -> {
                    val r = stream?.let { runningDynamics(it, a.avgPower) }
                    val power = r?.powerW ?: a.avgPower?.takeIf { it > 0 }?.roundToInt()
                    listOfNotNull(
                        r?.cadenceSpm?.let { "cadence $it spm" },
                        power?.let { "power $it W" },
                        r?.strideM?.let { s -> fmt.elevationNumber(s)?.let { "stride ${"%.2f".format(Locale.US, it)} ${fmt.elevationUnit}" } },
                    )
                }

                SportFamily.RIDE -> {
                    val moving = stream?.cadence?.filter { it > 0 }.orEmpty()
                    listOfNotNull(
                        a.avgPower?.takeIf { it > 0 }?.let { "average power ${it.roundToInt()} W" },
                        a.normPower?.takeIf { it > 0 }?.let { "normalized power ${it.roundToInt()} W" },
                        moving.takeIf { it.isNotEmpty() }?.let { "cadence ${it.average().roundToInt()} rpm" },
                    )
                }

                else -> emptyList()
            }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Form: ")
    }

    /** At most [MAX_LAPS] rows; a longer list is merged into even groups. */
    private fun lapRows(laps: List<LapEntity>, family: SportFamily, fmt: Formatters): List<String> {
        val groups = laps.size.coerceAtMost(MAX_LAPS)
        var start = 0
        return List(groups) { g ->
            val end = start + laps.size / groups + if (g < laps.size % groups) 1 else 0
            val part = laps.subList(start, end).also { start = end }
            val dist = part.sumOf { it.distanceM ?: 0.0 }.takeIf { it > 0 }
            val dur = part.sumOf { it.durationSec ?: 0.0 }.takeIf { it > 0 }
            val hrParts = part.filter { it.avgHr != null && (it.durationSec ?: 0.0) > 0 }
            val hr = hrParts.sumOf { it.durationSec!! }.takeIf { it > 0 }?.let { total -> hrParts.sumOf { it.avgHr!! * it.durationSec!! } / total }
            val first = part.first().lapIndex + 1
            val last = part.last().lapIndex + 1
            "- ${if (first == last) "lap $first" else "laps $first-$last"}: " + listOfNotNull(
                if (family == SportFamily.SWIM) dist?.let { "${fmt.swimDistance(it)} ${fmt.swimDistanceUnit}" } else fmt.distance(dist),
                fmt.duration(dur),
                rate(family, dist, dur, fmt),
                hr?.let { "HR ${it.roundToInt()}" },
            ).joinToString(", ").ifEmpty { NO_DATA }
        }
    }

    private suspend fun swimSummary(a: ActivityEntity, fmt: Formatters): String {
        val active = db.swimLengthDao().forActivity(a.id).filter { it.active && (it.durationSec ?: 0.0) > 0 }
        if (active.isEmpty()) return "Swim lengths: $NO_DATA"
        val pool = a.poolLengthM?.takeIf { it > 0 }
        val strokes = active.mapNotNull { it.strokeCount }
        val time = active.sumOf { it.durationSec!! }

        fun pace(secPer100m: Double) = fmt.swimPace(secPer100m)?.let { "$it ${fmt.swimPaceUnit}" }
        val paces = if (pool == null) emptyList() else active.map { it.durationSec!! / pool * 100 }
        return "Swim: " + listOfNotNull(
            "${active.size} lengths" + (pool?.let { " of ${fmt.swimDistance(it)} ${fmt.swimDistanceUnit}" } ?: ""),
            paces.takeIf { it.isNotEmpty() }?.let { "average ${pace(it.average())}, fastest length ${pace(it.min())}, slowest ${pace(it.max())}" },
            strokes.takeIf { it.isNotEmpty() }?.let { "${one(it.average())} strokes per length, stroke rate ${(it.sum() / time * 60).roundToInt()} /min" },
            active.mapNotNull { it.strokeType }.distinct().takeIf { it.isNotEmpty() }?.let { "strokes: " + it.joinToString("/") },
        ).joinToString(", ")
    }

    private suspend fun strengthLines(a: ActivityEntity, fmt: Formatters): List<String> {
        val sets = db.strengthSetDao().forActivity(a.id).filter { it.setType == "ACTIVE" }
        if (sets.isEmpty()) return listOf("Sets: $NO_DATA")
        val groups = sets.groupBy { exerciseName(it.category) }
        return listOf("Sets: ${sets.size} across ${groups.size} ${if (groups.size == 1) "exercise" else "exercises"}") +
            groups.entries.take(MAX_EXERCISES).map { (name, group) ->
                val chips = group.take(MAX_SET_CHIPS).map { s ->
                    val reps = s.reps?.takeIf { it > 0 }
                    val kg = s.weightKg?.takeIf { it > 0 }
                    when {
                        reps != null && kg != null -> "$reps × ${fmt.weight(kg)} ${fmt.weightUnit}"
                        reps != null -> "$reps reps"
                        else -> fmt.duration(s.durationSec) ?: NO_DATA
                    }
                }
                val more = if (group.size > MAX_SET_CHIPS) ", …" else ""
                "- $name: ${group.size} ${if (group.size == 1) "set" else "sets"} (${chips.joinToString(", ")}$more)"
            }
    }

    private fun exerciseName(category: Int?): String =
        category?.let { ExerciseCategory.getStringFromValue(it) }?.takeIf { it.isNotBlank() && it != "UNKNOWN" }?.let(::titleCase) ?: "Exercise"

    // ---- Trends ----

    private class TrendDay(
        val date: LocalDate,
        val recovery: Int?,
        val hrv: Double?,
        val rhr: Int?,
        val sleepPct: Double?,
        val strain: Double?,
        val load: TrainingLoadDay?,
    )

    private class TrendMetric(val name: String, val digits: Int, val pick: (TrendDay) -> Double?)

    private suspend fun trendsPack(range: Int, size: PackSize, fmt: Formatters): Pack {
        val to = today.value
        val from = to.minusDays(range - 1L)
        val days = repository.days(from, to).first().associateBy { it.date }
        val scores = repository.scores(from.minusDays(TrainingLoad.WARM_UP_DAYS), to).first()
        val earliest = repository.historyStart.first().first
        val tau = userPrefs.strainCalibration.first()?.tau
        val series = TrainingLoad.series(scores.associate { it.date to it.dayLoad }, from, to, earliest, tau)
            .associateBy { it.date }
            .mapValues { (d, v) -> v.takeUnless { earliest == null || d.isBefore(earliest) } }
        val byScore = scores.associateBy { it.date }
        val rows = List(range) { i ->
            val d = from.plusDays(i.toLong())
            TrendDay(
                d,
                byScore[d]?.recovery,
                days[d]?.let(HRV),
                days[d]?.let(RHR)?.toInt(),
                byScore[d]?.sleepPerformance?.takeIf {
                    it.isFinite()
                }?.times(100),
                byScore[d]?.strain,
                series[d],
            )
        }
        val label = when (range) {
            7 -> "week"
            30 -> "month"
            90 -> "3 months"
            else -> "$range days"
        }
        val facts = PackFacts(null, null, null, label)
        val any = rows.any { it.recovery != null || it.hrv != null || it.rhr != null || it.sleepPct != null || it.strain != null }
        if (!any) return pack("trends, last $range days.", null, "No data in this range.", facts, fmt)
        val metrics = listOf(
            TrendMetric("Recovery", 0) { it.recovery?.toDouble() },
            TrendMetric("HRV", 0) { it.hrv },
            TrendMetric("Resting HR", 0) { it.rhr?.toDouble() },
            TrendMetric("Sleep performance", 0) { it.sleepPct },
            TrendMetric("Strain", 1) { it.strain },
            TrendMetric("Fitness", 1) { it.load?.fitness },
            TrendMetric("Fatigue", 1) { it.load?.fatigue },
            TrendMetric("Form", 0) { it.load?.formPct?.toDouble() },
        )
        val body = buildList {
            add("Summary over the range (mean, min, max, slope per day; days without data skipped):")
            for (m in metrics) add(summary(m.name, m.digits, rows.map(m.pick)))
            add(if (size == PackSize.FULL) "Daily rows:" else "Last ${minOf(14, range)} days:")
            for (r in if (size == PackSize.FULL) rows else rows.takeLast(14)) add(row(r))
        }
        return pack("trends, last $range days (${from.format(DAY)} to ${to.format(DAY)}).", body.joinToString("\n"), "", facts, fmt)
    }

    private fun summary(name: String, digits: Int, values: List<Double?>): String {
        val v = values.filterNotNull()
        if (v.isEmpty()) return "$name: $NO_DATA"
        fun f(x: Double) = "%.${digits}f".format(Locale.US, x).replace('-', '−')
        val slope = slope(values)?.let { "${signedDouble(it, 2)}/day" } ?: NO_DATA
        return "$name: mean ${f(v.average())}, min ${f(v.min())}, max ${f(v.max())}, slope $slope"
    }

    /** Least-squares slope per day over the days that have a value; null with fewer than three. */
    private fun slope(values: List<Double?>): Double? {
        val pts = values.mapIndexedNotNull { i, y -> y?.let { i.toDouble() to it } }
        if (pts.size < 3) return null
        val mx = pts.map { it.first }.average()
        val my = pts.map { it.second }.average()
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        return if (sxx == 0.0) null else pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
    }

    private fun row(r: TrendDay): String {
        val parts = listOf(
            "recovery " + (r.recovery?.let { "$it%" } ?: NO_DATA),
            "HRV " + (r.hrv?.let(::whole) ?: NO_DATA),
            "RHR " + (r.rhr ?: NO_DATA),
            "sleep " + (r.sleepPct?.let { "${it.roundToInt()}%" } ?: NO_DATA),
            "strain " + (r.strain?.let(::one) ?: NO_DATA),
            "fitness " + (r.load?.fitness?.let(::one) ?: NO_DATA),
            "fatigue " + (r.load?.fatigue?.let(::one) ?: NO_DATA),
            "form " + (r.load?.formPct?.let(::formPctText) ?: NO_DATA),
        )
        val empty = r.recovery == null && r.hrv == null && r.rhr == null && r.sleepPct == null && r.strain == null
        return "${r.date.format(DAY)}: " + if (empty) NO_DATA else parts.joinToString(", ")
    }
}

private val NO_FACTS = PackFacts(null, null, null, null)

/** The 30 days before [date], for the plain-mean baselines, and the Recovery baseline (HRV and RHR) where it exists. */
private class Window(date: LocalDate, val history: List<DayEntity>) {
    val recovery: Baseline? = Baselines.at(date, history)
    private val days = history.filter { it.date in date.minusDays(30)..date.minusDays(1) }

    fun mean(pick: (DayEntity) -> Double?, keep: (Double) -> Boolean = { true }): Double? =
        days.mapNotNull(pick).filter(keep).takeIf { it.isNotEmpty() }?.average()

    val hrv: Double? get() = recovery?.let { exp(it.lnHrvMean) } ?: mean(HRV)
    val rhr: Double? get() = recovery?.rhrMean ?: mean(RHR)
}

/** "HRV 58 ms vs 30-day baseline 53 ms (+9%)"; "Resting HR no data" without a value, "(no baseline)" without a baseline. */
private fun metric(label: String, unit: String, v: Double?, base: Double?, show: (Double) -> String, delta: (Double, Double) -> String?): String = when {
    v == null -> "$label $NO_DATA"
    base == null || !base.isFinite() -> "$label ${show(v)}$unit (no baseline)"
    else -> "$label ${show(v)}$unit vs 30-day baseline ${show(base)}$unit" + (delta(v, base)?.let { " ($it)" } ?: "")
}

private fun whole(v: Double) = v.roundToInt().toString()

private fun one(v: Double) = "%.1f".format(Locale.US, v)

private fun grouped(v: Double) = "%,d".format(Locale.US, v.roundToInt())

private fun hm(seconds: Double) = Formatters.hoursMinutes(seconds) ?: NO_DATA

private fun signed(v: Int) = if (v < 0) {
    "−${-v}"
} else if (v > 0) {
    "+$v"
} else {
    "0"
}

private fun pctText(v: Int) = signed(v) + "%"

private fun signedDouble(v: Double, digits: Int): String {
    val s = "%.${digits}f".format(Locale.US, abs(v))
    return if (s.all { it == '0' || it == '.' }) s else (if (v < 0) "−" else "+") + s
}

private fun pct(v: Double, base: Double): String? = if (base == 0.0) null else pctText(Math.round((v - base) / base * 100).toInt())

private fun absolute(unit: String, digits: Int): (Double, Double) -> String? = { v, b ->
    if (digits == 0) signed(Math.round(v - b).toInt()) + unit else signedDouble(v - b, digits) + unit
}

private fun hmDelta(v: Double, base: Double): String? {
    val minutes = Math.round((v - base) / 60).toInt()
    return if (minutes == 0) "0m" else (if (minutes < 0) "−" else "+") + hm(abs(minutes) * 60.0)
}

private fun titleCase(s: String) = s.split('_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
