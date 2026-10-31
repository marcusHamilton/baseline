package dev.baseline.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.NetworkType
import androidx.work.WorkInfo
import dev.baseline.data.Repository
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.data.db.TaskCounts
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.DEFAULT_BASELINE_ORDER
import dev.baseline.prefs.UserPrefs
import dev.baseline.scores.Baseline
import dev.baseline.scores.Baselines
import dev.baseline.scores.Recovery
import dev.baseline.scores.RecoveryResult
import dev.baseline.scores.SleepPerformance
import dev.baseline.scores.TrainingLoad
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.sync.SyncScheduler
import dev.baseline.ui.components.ActivityCardUi
import dev.baseline.ui.format.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** [latest] is the last sample of [curve]; [drained] stays null until the day table stores it. */
data class BodyBatteryUi(val latest: Int?, val curve: List<Pair<Instant, Int>>, val charged: Int?, val drained: Int?, val expanded: Boolean)

/** Today's Training load bar (fitness-fatigue-form spec §4); [budget] is null while calibrating or with no recent training. */
data class TrainingLoadUi(val day: TrainingLoadDay, val budget: WeekBudget?, val expanded: Boolean)

/**
 * [delta] is e.g. "▲ 6%"; [better] says whether that move is good (true), bad (false) or neither (null).
 * [verdict] is the word shown with it: "better", "worse", "normal" (breathing below baseline) or null.
 */
data class StatRowUi(
    val id: String,
    val label: String,
    val value: String,
    val unit: String,
    val baseline: String?,
    val delta: String?,
    val better: Boolean?,
    val verdict: String? = null,
    /** Only ever true in edit mode: hidden rows are listed there (dimmed) so they can be shown again. */
    val hidden: Boolean = false,
)

/** [recovery] is null when it can't be computed; [recoveryReason] then says why. [strain] is null without a score. */
data class TodayUiState(
    val date: LocalDate,
    val isToday: Boolean,
    val sleepPct: Int?,
    val recovery: RecoveryResult?,
    val recoveryReason: String?,
    val strain: Double?,
    val strainTarget: ClosedFloatingPointRange<Double>?,
    val activities: List<ActivityCardUi>,
    val rows: List<StatRowUi>,
    val bodyBattery: BodyBatteryUi,
    val lastSynced: Instant?,
    /** The view model's today, so the screen words "Synced yesterday" from it and never reads the clock itself. */
    val today: LocalDate,
    /** True until the first real emission, so the screen shows a placeholder instead of "Not enough data". */
    val loading: Boolean = false,
    /** Baseline-list edit mode: [rows] then include the hidden ones. */
    val editing: Boolean = false,
    /** The day's data arrived but could not be parsed (spec §10); the screen says so instead of showing blanks silently. */
    val unreadable: Boolean = false,
    /** Null before the first score, so paging back past the history shows no bar. */
    val trainingLoad: TrainingLoadUi? = null,
)

/** The sync line shown above Today; null when WorkManager has nothing to say. */
fun syncStatus(infos: List<WorkInfo>, counts: TaskCounts, backfillPending: Boolean, key: KeyStatus?, online: Boolean = true): String? = when {
    key is KeyStatus.Invalid || key is KeyStatus.Expired -> "Garmin key problem: check Settings"

    infos.any { it.state == WorkInfo.State.RUNNING } ->
        if (backfillPending) "Syncing… ${counts.done}/${counts.total}" else "Syncing…"

    infos.any { it.state == WorkInfo.State.FAILED || (it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0) } ->
        if (online) "Retrying shortly" else "Offline, will retry"

    // The next slice (a PARTIAL run's follow-up, or the first sync) is held back until Wi-Fi. Not the periodic job, and not
    // while the user's own any-network request is queued.
    backfillPending && infos.any {
        it.state == WorkInfo.State.ENQUEUED && it.periodicityInfo == null &&
            it.constraints.requiredNetworkType == NetworkType.UNMETERED
    } && infos.none {
        !it.state.isFinished && SyncScheduler.USER in it.tags && it.constraints.requiredNetworkType == NetworkType.CONNECTED
    } -> "Waiting for Wi-Fi"

    // A PARTIAL run also ends SUCCEEDED; while the backfill is unfinished the follow-up is queued, so don't claim "Up to date".
    infos.any { it.state == WorkInfo.State.SUCCEEDED } ->
        if (backfillPending) "Syncing… ${counts.done}/${counts.total}" else "Up to date"

    else -> null
}

/** Today's state. Only the SyncWorker talks to Garmin; this just asks it to run. */
class TodayViewModel(
    private val repo: Repository,
    private val scheduler: SyncScheduler,
    private val prefs: UserPrefs,
    counts: Flow<TaskCounts>,
    backfillPending: Flow<Boolean>,
    keyStatusFlow: Flow<KeyStatus>,
    online: Flow<Boolean>,
    private val todayFlow: StateFlow<LocalDate>,
) : ViewModel() {
    constructor(
        repo: Repository,
        scheduler: SyncScheduler,
        prefs: UserPrefs,
        counts: Flow<TaskCounts>,
        backfillPending: Flow<Boolean>,
        keyStatusFlow: Flow<KeyStatus>,
        online: Flow<Boolean>,
        today: LocalDate,
    ) : this(repo, scheduler, prefs, counts, backfillPending, keyStatusFlow, online, MutableStateFlow(today))

    private val whileSubscribed = SharingStarted.WhileSubscribed(5_000)

    val today: LocalDate get() = todayFlow.value
    private val date = MutableStateFlow(todayFlow.value)

    init {
        // Past midnight: someone looking at the old today follows to the new one; a past day stays put.
        viewModelScope.launch {
            var prev = todayFlow.value
            todayFlow.collect { now ->
                val viewingToday = date.value == prev
                prev = now
                if (viewingToday || date.value.isAfter(now)) date.value = now
            }
        }
    }
    private val bodyBatteryExpanded = MutableStateFlow(false)
    private val trainingLoadExpanded = MutableStateFlow(false)
    private val editing = MutableStateFlow(false)
    private val prefsLock = Mutex()

    fun setDate(d: LocalDate) {
        date.value = if (d.isAfter(today)) today else d
    }
    fun prevDay() = setDate(date.value.minusDays(1))
    fun nextDay() = setDate(date.value.plusDays(1))
    fun toggleBodyBattery() {
        bodyBatteryExpanded.value = !bodyBatteryExpanded.value
    }
    fun toggleTrainingLoad() {
        trainingLoadExpanded.value = !trainingLoadExpanded.value
    }
    fun toggleEdit() {
        editing.value = !editing.value
    }

    /** [from] and [to] index the list as shown in edit mode (all rows, hidden ones included). */
    fun moveRow(from: Int, to: Int) {
        viewModelScope.launch {
            prefsLock.withLock {
                val order = fullOrder(prefs.baselineOrder.first()).toMutableList()
                if (from !in order.indices || to !in order.indices || from == to) return@withLock
                order.add(to, order.removeAt(from))
                prefs.setBaselineOrder(order)
            }
        }
    }

    fun toggleHidden(id: String) {
        if (id !in DEFAULT_BASELINE_ORDER) return
        viewModelScope.launch {
            prefsLock.withLock {
                val hidden = prefs.hiddenRows.first()
                prefs.setHiddenRows(if (id in hidden) hidden - id else hidden + id)
            }
        }
    }

    private class DayInputs(val day: DayEntity?, val score: ScoreEntity?, val history: List<DayEntity>, val unreadable: Boolean)
    private class ActivityInputs(val activities: List<ActivityEntity>, val strains: Map<Long, Double>, val curve: List<Pair<Instant, Int>>)
    private class LoadInputs(val scores: List<ScoreEntity>, val earliest: LocalDate?, val firstDayOfWeek: DayOfWeek, val tau: Double?, val expanded: Boolean)
    private class DisplayInputs(val order: List<String>, val hidden: Set<String>, val imperial: Boolean, val lastSynced: Instant?, val editing: Boolean)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TodayUiState> = combine(date, todayFlow, ::Pair).flatMapLatest { (d, today) ->
        val dayInputs = combine(repo.day(d), repo.score(d), repo.days(d.minusDays(HISTORY_DAYS), d.minusDays(1)), repo.dayUnreadable(d), ::DayInputs)
        val activityInputs = combine(
            repo.activitiesOn(d),
            repo.activityScores(d).map { all -> all.associate { it.activityId to it.strain } },
            repo.bodyBatteryCurve(d),
            ::ActivityInputs,
        )
        val displayInputs = combine(prefs.baselineOrder, prefs.hiddenRows, repo.imperial, prefs.lastSyncedAt.distinctUntilChanged(), editing, ::DisplayInputs)
        val loadInputs = combine(
            repo.scores(d.minusDays(7 + TrainingLoad.WARM_UP_DAYS), d),
            repo.historyStart.map { it.first }.distinctUntilChanged(),
            repo.firstDayOfWeek,
            prefs.strainCalibration.map { it?.tau }.distinctUntilChanged(),
            trainingLoadExpanded,
            ::LoadInputs,
        )
        combine(dayInputs, activityInputs, displayInputs, bodyBatteryExpanded, loadInputs) { a, b, c, expanded, l ->
            buildTodayState(
                d, today, a.day, a.score, a.history, b.activities, b.strains, b.curve,
                c.order, c.hidden, Formatters(c.imperial), c.lastSynced, expanded, c.editing, a.unreadable,
                buildTrainingLoadUi(d, l.scores, l.earliest, l.firstDayOfWeek, l.tau, l.expanded),
            )
        }
    }.flowOn(Dispatchers.Default) // backfill rewrites tables constantly; keep the rebuilds off the main thread
        .stateIn(viewModelScope, whileSubscribed, emptyState(todayFlow.value))

    val keyStatus: StateFlow<KeyStatus?> = keyStatusFlow.stateIn(viewModelScope, whileSubscribed, null)

    private val infos: StateFlow<List<WorkInfo>> = scheduler.workInfos.stateIn(viewModelScope, whileSubscribed, emptyList())

    val sync: StateFlow<String?> =
        combine(infos, counts, backfillPending, keyStatusFlow, online, ::syncStatus).stateIn(viewModelScope, whileSubscribed, null)

    private fun List<WorkInfo>.syncNow(vararg states: WorkInfo.State) = any { SyncScheduler.SYNC_NOW in it.tags && it.state in states }

    // The spinner belongs to a pull: it ends when the sync-now run goes from RUNNING to not running.
    private val pulled = MutableStateFlow(false)
    private var wasRunning = false
    val refreshing: StateFlow<Boolean> = combine(pulled, infos.map { it.syncNow(WorkInfo.State.RUNNING) }.distinctUntilChanged(), ::Pair)
        .onEach { (p, running) ->
            if (p && wasRunning && !running) pulled.value = false
            wasRunning = running
        }
        .map { (p, running) -> p && running }
        .stateIn(viewModelScope, whileSubscribed, false)

    init {
        viewModelScope.launch { scheduler.syncSoon() }
    }

    fun refresh() {
        pulled.value = true // also when a waiting syncSoon is taken over, or a running sync covers this pull
        viewModelScope.launch { scheduler.syncNow() }
    }

    /** True once per install: the caller then shows the Android permission prompt (API 33+, if not already granted). */
    suspend fun claimNotificationPrompt(): Boolean {
        if (prefs.notificationsAsked.first()) return false
        prefs.setNotificationsAsked()
        return true
    }

    private companion object {
        const val HISTORY_DAYS = 30L
    }
}

private fun emptyState(today: LocalDate) = TodayUiState(
    today, true, null, null, null, null, null, emptyList(), emptyList(),
    BodyBatteryUi(null, emptyList(), null, null, false), null, today, loading = true,
)

@Suppress("LongParameterList")
internal fun buildTodayState(
    date: LocalDate,
    today: LocalDate,
    day: DayEntity?,
    score: ScoreEntity?,
    history: List<DayEntity>,
    activities: List<ActivityEntity>,
    activityStrains: Map<Long, Double>,
    curve: List<Pair<Instant, Int>>,
    order: List<String>,
    hidden: Set<String>,
    fmt: Formatters,
    lastSynced: Instant?,
    bodyBatteryExpanded: Boolean,
    editing: Boolean = false,
    unreadable: Boolean = false,
    trainingLoad: TrainingLoadUi? = null,
): TodayUiState {
    val baseline = Baselines.at(date, history)
    val sleepPerf = SleepPerformance.of(day?.sleepSeconds, day?.sleepNeedMinutes)
    val recovery = day?.let { Recovery.compute(it, baseline, sleepPerf) }
    val reason = when {
        recovery != null -> null
        baseline == null -> "calibrating — need 7 nights"
        day?.sleepSeconds?.let { it > 0 } != true -> "no sleep recorded"
        else -> "sleep need unavailable"
    }
    val perf = score?.sleepPerformance ?: sleepPerf
    val window = history.filter { it.date in date.minusDays(30)..date.minusDays(1) }
    val target = score?.let { s -> s.strainTargetLow?.let { lo -> s.strainTargetHigh?.let { hi -> lo..hi } } }
    return TodayUiState(
        date = date,
        isToday = date == today,
        sleepPct = perf?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt() },
        recovery = recovery,
        recoveryReason = reason,
        strain = score?.strain?.takeIf { it.isFinite() },
        strainTarget = target,
        activities = activities.map { a ->
            ActivityCardUi(
                a.id,
                a.name?.takeIf { it.isNotBlank() } ?: prettyType(a.typeKey),
                a.sportFamily,
                a.typeKey,
                fmt.activityMeta(a.startTimeLocal, a.distanceM, a.durationSec),
                activityStrains[a.id],
            )
        },
        rows = statRows(day, window, baseline, order, hidden, date == today, editing),
        bodyBattery = BodyBatteryUi(curve.lastOrNull()?.second, curve, day?.bodyBatteryCharged, null, bodyBatteryExpanded),
        lastSynced = lastSynced,
        today = today,
        editing = editing,
        unreadable = unreadable,
        trainingLoad = trainingLoad,
    )
}

/** [scores] should reach back `7 + WARM_UP_DAYS` before [date]; null when [date] is before the first score. */
internal fun buildTrainingLoadUi(
    date: LocalDate,
    scores: List<ScoreEntity>,
    earliestScore: LocalDate?,
    firstDayOfWeek: DayOfWeek,
    tau: Double?,
    expanded: Boolean,
): TrainingLoadUi? {
    if (earliestScore != null && date.isBefore(earliestScore)) return null
    val loads = scores.associate { it.date to it.dayLoad }
    return TrainingLoadUi(
        TrainingLoad.series(loads, date, date, earliestScore, tau).single(),
        TrainingLoad.weekBudget(loads, date, firstDayOfWeek, earliestScore, tau),
        expanded,
    )
}

internal fun prettyType(typeKey: String) =
    typeKey.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.ifEmpty { "Activity" }

/** ONLY_LOWER: above baseline is bad, below is just normal (breathing rate, as in Recovery). */
private enum class Good { HIGHER, LOWER, ONLY_LOWER }

private class RowSpec(
    val id: String,
    val label: String,
    val unit: String,
    val good: Good,
    val pick: (DayEntity) -> Double?,
    /** Rounds a figure to what the row prints (used for the baseline text only). */
    val shown: (Double) -> Double,
    /** Rounds the exact delta once, for display; the delta is never computed from the rounded figures (it must match the insight). */
    val roundDelta: (Double) -> Double,
    val value: (Double) -> String,
    val delta: (Double) -> String,
    val relative: Boolean = false,
    /** Days that don't count towards the baseline mean (a zero-step day is a day without the watch). */
    val inBaseline: (Double) -> Boolean = { true },
)

/** The saved order first (unknown ids dropped), then any row it doesn't mention in the default order. */
private fun fullOrder(saved: List<String>) = (saved.filter { it in DEFAULT_BASELINE_ORDER }.distinct() + DEFAULT_BASELINE_ORDER).distinct()

private fun whole(v: Double) = v.roundToInt().toDouble()
private fun grouped(v: Double) = java.text.NumberFormat.getIntegerInstance(Locale.US).format(v.roundToInt())

private val ROWS: Map<String, RowSpec> = listOf(
    RowSpec(
        "hrv", "HRV (overnight)", "ms", Good.HIGHER, { it.hrvLastNight?.takeIf { v -> v > 0 } }, ::whole, ::whole,
        { "%.0f".format(Locale.US, it) }, { "${it.roundToInt()}%" }, relative = true,
    ),
    RowSpec(
        "rhr", "Resting heart rate", "bpm", Good.LOWER, { it.restingHr?.takeIf { v -> v > 0 }?.toDouble() }, ::whole, ::whole,
        { "%.0f".format(Locale.US, it) }, { "${it.roundToInt()}" },
    ),
    RowSpec(
        "sleep", "Sleep", "", Good.HIGHER, { it.sleepSeconds?.takeIf { v -> v > 0 }?.toDouble() }, { (it / 60).roundToInt() * 60.0 }, {
            (it / 60).roundToInt() *
                60.0
        },
        { Formatters.hoursMinutes(it) ?: "—" }, { Formatters.hoursMinutes(it) ?: "—" },
    ),
    RowSpec(
        "resp", "Respiration (sleep)", "brpm", Good.ONLY_LOWER, { it.respirationAvg?.takeIf { v -> v > 0 } }, { (it * 10).roundToInt() / 10.0 }, {
            (it * 10).roundToInt() /
                10.0
        },
        { "%.1f".format(Locale.US, it) }, { "%.1f".format(Locale.US, it) },
    ),
    RowSpec(
        "bb", "Body Battery at wake", "", Good.HIGHER, { it.bodyBatteryWake?.toDouble() }, ::whole, ::whole,
        { "%.0f".format(Locale.US, it) }, { "${it.roundToInt()}" },
    ),
    RowSpec(
        "stress", "Avg stress", "", Good.LOWER, { it.stressAvg?.toDouble() }, ::whole, ::whole,
        { "%.0f".format(Locale.US, it) }, { "${it.roundToInt()}" },
    ),
    RowSpec("steps", "Steps", "", Good.HIGHER, { it.steps?.toDouble() }, ::whole, ::whole, ::grouped, ::grouped, inBaseline = { it > 0 }),
).associateBy { it.id }

internal fun statRows(
    day: DayEntity?,
    window: List<DayEntity>,
    recoveryBaseline: Baseline?,
    order: List<String>,
    hidden: Set<String>,
    isToday: Boolean,
    editing: Boolean,
): List<StatRowUi> {
    val ids = fullOrder(order).filter { editing || it !in hidden }
    return ids.map { id ->
        val spec = ROWS.getValue(id)
        // HRV and RHR use the same baseline as Recovery (and so the insight text); everything else, and the
        // fewer-than-7-nights case, uses the plain mean of the available days.
        val base = when {
            id == "hrv" && recoveryBaseline != null -> exp(recoveryBaseline.lnHrvMean)
            id == "rhr" && recoveryBaseline != null -> recoveryBaseline.rhrMean
            else -> window.mapNotNull(spec.pick).filter(spec.inBaseline).takeIf { it.isNotEmpty() }?.average()
        }?.takeIf { it.isFinite() }
        val value = day?.let(spec.pick)
        val unitSuffix = if (spec.unit.isEmpty()) "" else " ${spec.unit}"
        val move = if (id == "steps" && isToday && value != null) Move("so far", null, null) else moveOf(spec, value, base)
        StatRowUi(
            id, spec.label, value?.let(spec.value) ?: "—", spec.unit,
            base?.let { spec.value(spec.shown(it)) + unitSuffix }, move?.text, move?.better, move?.verdict, hidden = id in hidden,
        )
    }
}

private class Move(val text: String, val better: Boolean?, val verdict: String?)

/**
 * Arrow plus magnitude, and the verdict; "same" (no arrow) when it rounds to nothing. The delta comes from the exact value and
 * baseline and is rounded once, exactly as Recovery's contributor delta is, so the row and the insight text agree.
 */
private fun moveOf(spec: RowSpec, value: Double?, base: Double?): Move? {
    if (value == null || base == null) return null
    if (spec.relative && base == 0.0) return null
    val raw = if (spec.relative) (value / base - 1) * 100 else value - base
    // Round the magnitude, then apply the sign: Recovery's phrasing rounds |delta|, and roundToInt() rounds -2.5 to -2.
    val magnitude = spec.roundDelta(abs(raw))
    if (magnitude == 0.0) return Move("same", null, null)
    val up = raw > 0
    val better = when (spec.good) {
        Good.HIGHER -> up
        Good.LOWER -> !up
        Good.ONLY_LOWER -> if (up) false else null
    }
    val verdict = when {
        better == true -> "better"
        better == false -> "worse"
        spec.good == Good.ONLY_LOWER -> "normal"
        else -> null
    }
    return Move("${if (up) "▲" else "▼"} ${spec.delta(magnitude)}", better, verdict)
}
