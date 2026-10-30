package dev.baseline.widget

import androidx.work.WorkInfo
import dev.baseline.AppContainer
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.garmin.KeyStatus
import dev.baseline.scores.Baselines
import dev.baseline.scores.Recovery
import dev.baseline.scores.RecoveryZone
import dev.baseline.scores.SleepPerformance
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.today.prettyType
import dev.baseline.ui.today.statRows
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

sealed interface SyncPillUi {
    data object Syncing : SyncPillUi

    data object KeyError : SyncPillUi

    data class Ready(val label: String) : SyncPillUi
}

data class ReadinessModel(
    val headerDate: String,
    val recoveryPct: Int?,
    val recoveryZone: RecoveryZone?,
    val recoveryPartial: Boolean,
    val recoveryReason: String?,
    val strain: Double?,
    val strainText: String,
    val strainTargetText: String?,
    val sleepPct: Int?,
    val bodyBattery: Int?,
    val unreadable: Boolean,
)

data class VitalTileModel(val id: String, val shortLabel: String, val valueText: String, val unit: String, val deltaText: String?, val better: Boolean?)

data class VitalsModel(val statusSummary: String?, val statusWarn: Boolean, val tiles: List<VitalTileModel>, val allHidden: Boolean, val unreadable: Boolean)

data class BodyBatteryModel(
    val current: Int?,
    val metaLine: String,
    val curve: List<Pair<Instant, Int>>,
    val axisLabels: List<String>,
    val unreadable: Boolean,
)

data class SleepWidgetModel(
    val asleepText: String,
    val needText: String?,
    val sleepPctText: String?,
    val garminScoreText: String?,
    val deepSec: Int?,
    val remSec: Int?,
    val lightSec: Int?,
    val awakeSec: Int?,
    val tonightNeedText: String,
    val bedtimeText: String?,
    val unreadable: Boolean,
)

data class WeekDayCell(val letter: String, val recoveryZone: RecoveryZone?, val hasActivity: Boolean)

data class LatestActivityUi(val id: Long, val title: String, val family: SportFamily, val metaLine: String, val strainText: String)

data class WeeklyActivityModel(val headerTitle: String, val weekTotalsText: String, val latest: LatestActivityUi?, val days: List<WeekDayCell>)

data class WidgetSnapshot(
    val today: LocalDate,
    val zone: ZoneId,
    val keyMissing: Boolean,
    val syncPill: SyncPillUi,
    val readiness: ReadinessModel,
    val vitals: VitalsModel,
    val bodyBattery: BodyBatteryModel,
    val sleep: SleepWidgetModel,
    val weekly: WeeklyActivityModel,
)

private const val MISSING = "—"
private const val HISTORY_DAYS = 30L
private val HEADER_DATE_FMT = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val SYNC_OLD_DAY_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

private val SHORT_LABELS =
    mapOf(
        "hrv" to "HRV",
        "rhr" to "Resting HR",
        "sleep" to "Sleep",
        "resp" to "Respiration",
        "bb" to "Wake BB",
        "stress" to "Avg stress",
        "steps" to "Steps",
    )

@Suppress("LongParameterList")
fun buildWidgetSnapshot(
    today: LocalDate,
    zone: ZoneId,
    is24h: Boolean,
    keyStatus: KeyStatus,
    syncing: Boolean,
    lastSynced: Instant?,
    day: DayEntity?,
    score: ScoreEntity?,
    history: List<DayEntity>,
    unreadable: Boolean,
    bodyBatteryCurve: List<Pair<Instant, Int>>,
    order: List<String>,
    hidden: Set<String>,
    imperial: Boolean,
    firstDayOfWeek: DayOfWeek,
    recentActivities: List<ActivityEntity>,
    activityStrains: Map<Long, Double>,
    activeSets: Map<Long, Int>,
    weekScores: Map<LocalDate, ScoreEntity>,
): WidgetSnapshot {
    val syncPill =
        when {
            keyStatus is KeyStatus.Invalid || keyStatus is KeyStatus.Expired -> SyncPillUi.KeyError

            syncing -> SyncPillUi.Syncing

            lastSynced != null -> {
                val local = lastSynced.atZone(zone)
                val stamp =
                    if (local.toLocalDate() == today) {
                        Formatters.time(local.toLocalTime(), is24h)
                    } else {
                        local.format(SYNC_OLD_DAY_FMT)
                    }
                SyncPillUi.Ready("↻ $stamp")
            }

            else -> SyncPillUi.Ready("↻ Sync")
        }

    val baseline = Baselines.at(today, history)
    val sleepPerf = SleepPerformance.of(day?.sleepSeconds, day?.sleepNeedMinutes)
    val recovery = day?.let { Recovery.compute(it, baseline, sleepPerf) }
    val recoveryReason =
        when {
            recovery != null -> null
            baseline == null -> "Calibrating — need 7 nights"
            day?.sleepSeconds?.let { it > 0 } != true -> "No sleep recorded"
            else -> "Sleep need unavailable"
        }
    val perf = (score?.sleepPerformance ?: sleepPerf)?.takeIf { it.isFinite() && it >= 0.0 }
    val sleepPct = perf?.let { (it * 100).roundToInt() }
    val strain = score?.strain?.takeIf { it.isFinite() && it >= 0.0 }
    val targetLo = score?.strainTargetLow?.takeIf { it.isFinite() && it >= 0.0 }
    val targetHi = score?.strainTargetHigh?.takeIf { it.isFinite() && it >= 0.0 }
    val strainTargetText =
        if (targetLo != null && targetHi != null) {
            "${targetLo.roundToInt()}–${targetHi.roundToInt()}"
        } else {
            null
        }
    // Only samples inside the phone zone's day count, so the value, the empty-curve note and the sparkline agree.
    val dayStart = today.atStartOfDay(zone).toInstant()
    val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
    val bbCurve = bodyBatteryCurve.filter { it.first >= dayStart && it.first <= dayEnd }
    val latestBb = bbCurve.lastOrNull()?.second?.coerceIn(0, 100)

    val readiness =
        ReadinessModel(
            headerDate = "TODAY · " + today.format(HEADER_DATE_FMT).uppercase(Locale.US),
            recoveryPct = recovery?.score,
            recoveryZone = recovery?.zone,
            recoveryPartial = recovery?.partial == true,
            recoveryReason = recoveryReason,
            strain = strain,
            strainText = strain?.let { "%.1f".format(Locale.US, it) } ?: MISSING,
            strainTargetText = strainTargetText,
            sleepPct = sleepPct,
            bodyBattery = latestBb,
            unreadable = unreadable,
        )

    val window = history.filter { it.date in today.minusDays(HISTORY_DAYS)..today.minusDays(1) }
    val visibleRows = statRows(day, window, baseline, order, hidden, isToday = true, editing = false)
    val allHidden = visibleRows.isEmpty()
    val tiles =
        visibleRows.take(4).map { r ->
            VitalTileModel(
                id = r.id,
                shortLabel = SHORT_LABELS[r.id] ?: r.label,
                valueText = r.value,
                unit = r.unit,
                deltaText = r.delta,
                better = r.better,
            )
        }
    val measuredCount = tiles.count { it.valueText != MISSING }
    val worseCount = tiles.count { it.better == false }
    val vitals =
        VitalsModel(
            statusSummary =
            when {
                worseCount > 0 -> "$worseCount worse"
                measuredCount > 0 -> "$measuredCount in range"
                else -> null
            },
            statusWarn = worseCount > 0,
            tiles = tiles,
            allHidden = allHidden,
            unreadable = unreadable,
        )

    val bbMetaParts =
        listOfNotNull(
            day?.bodyBatteryWake?.let { "Wake $it" },
            day?.bodyBatteryCharged?.let { "Charged +$it" },
            day?.stressAvg?.let { "Stress $it" },
        )
    val bodyBattery =
        BodyBatteryModel(
            current = latestBb,
            metaLine = bbMetaParts.joinToString(" · ").ifEmpty { MISSING },
            curve = bbCurve,
            axisLabels = if (is24h) listOf("00", "06", "12", "18") else listOf("12a", "6a", "12p", "6p"),
            unreadable = unreadable,
        )

    val asleepText =
        day?.sleepSeconds?.takeIf { it > 0 }?.let { Formatters.hoursMinutes(Duration.ofSeconds(it.toLong())) } ?: MISSING
    val needText =
        day?.sleepNeedMinutes?.takeIf { it > 0 }?.let { "/ ${Formatters.hoursMinutes(Duration.ofMinutes(it.toLong()))} need" }
    val tonightMin = day?.let { it.nextSleepNeedActualMin ?: it.nextSleepNeedBaselineMin }?.takeIf { it > 0 }
    val tonightNeedText =
        "Tonight's need: " + (tonightMin?.let { Formatters.hoursMinutes(Duration.ofMinutes(it.toLong())) } ?: MISSING)
    val bedtimeText =
        day?.recommendedBedtimeMin?.let { min ->
            val time = LocalTime.ofSecondOfDay(Math.floorMod(min, 24 * 60) * 60L)
            "Bedtime ${Formatters.time(time, is24h)}"
        }
    val sleep =
        SleepWidgetModel(
            asleepText = asleepText,
            needText = needText,
            sleepPctText = sleepPct?.let { "$it%" },
            garminScoreText = day?.sleepScore?.takeIf { it > 0 }?.let { "Score $it" },
            deepSec = day?.deepSeconds?.takeIf { it >= 0 },
            remSec = day?.remSeconds?.takeIf { it >= 0 },
            lightSec = day?.lightSeconds?.takeIf { it >= 0 },
            awakeSec = day?.awakeSeconds?.takeIf { it >= 0 },
            tonightNeedText = tonightNeedText,
            bedtimeText = bedtimeText,
            unreadable = unreadable,
        )

    val fmt = Formatters(imperial)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    val weekEnd = weekStart.plusDays(6)
    val thisWeek = recentActivities.filter { it.day in weekStart..weekEnd }
    val weekDurationSec = thisWeek.sumOf { it.durationSec.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 }.toLong()
    val weekDistanceM = thisWeek.sumOf { it.distanceM?.takeIf { d -> d.isFinite() && d > 0 } ?: 0.0 }
    val weekDistFormatted =
        if (imperial) {
            "%.1f mi".format(Locale.US, weekDistanceM / Formatters.METERS_PER_MILE)
        } else {
            "%.1f km".format(Locale.US, weekDistanceM / 1000.0)
        }
    val weekDurFormatted =
        if (weekDurationSec > 0) Formatters.hoursMinutes(Duration.ofSeconds(weekDurationSec)) else "0m"
    val countWord = if (thisWeek.size == 1) "ACTIVITY" else "ACTIVITIES"
    val latestEntity = recentActivities.maxByOrNull { it.startTimeLocal }
    val latestUi =
        latestEntity?.let { a ->
            val whenPrefix =
                if (a.day == today) {
                    "Today ${Formatters.time(a.startTimeLocal.toLocalTime(), is24h)}"
                } else {
                    Formatters.dayHeader(a.day, today)
                }
            val detail = fmt.meta(a.sportFamily, a.distanceM, a.durationSec, a.avgPower, activeSets[a.id])
            LatestActivityUi(
                id = a.id,
                title = a.name?.takeIf { it.isNotBlank() } ?: prettyType(a.typeKey),
                family = a.sportFamily,
                metaLine = listOf(whenPrefix, detail.takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" · "),
                strainText =
                activityStrains[a.id]?.takeIf { it.isFinite() && it >= 0.0 }?.let { "%.1f".format(Locale.US, it) }
                    ?: MISSING,
            )
        }
    val activityDays = thisWeek.map { it.day }.toSet()
    val weekCells =
        (0L..6L).map { offset ->
            val d = weekStart.plusDays(offset)
            val recZone = weekScores[d]?.recovery?.let(RecoveryZone::of)
            WeekDayCell(
                letter = d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.US),
                recoveryZone = recZone,
                hasActivity = d in activityDays,
            )
        }
    val weekly =
        WeeklyActivityModel(
            headerTitle = "THIS WEEK · ${thisWeek.size} $countWord",
            weekTotalsText = "$weekDurFormatted · $weekDistFormatted",
            latest = latestUi,
            days = weekCells,
        )

    return WidgetSnapshot(
        today = today,
        zone = zone,
        keyMissing = keyStatus is KeyStatus.Missing,
        syncPill = syncPill,
        readiness = readiness,
        vitals = vitals,
        bodyBattery = bodyBattery,
        sleep = sleep,
        weekly = weekly,
    )
}

suspend fun loadWidgetSnapshot(container: AppContainer, is24h: Boolean, syncing: Boolean? = null): WidgetSnapshot {
    val today = container.today.value
    val zone = container.dateTicker.zone.value
    val repo = container.repository
    val prefs = container.userPrefs
    val keyStatus = container.apiKeyVault.status.first()
    // An explicit value wins: the worker is still RUNNING in WorkManager while it reports that it has finished.
    val isSyncing = syncing ?: container.scheduler.workInfos.first().any { it.state == WorkInfo.State.RUNNING }
    val lastSynced = prefs.lastSyncedAt.first()
    val day = repo.day(today).first()
    val score = repo.score(today).first()
    val history = repo.days(today.minusDays(HISTORY_DAYS), today.minusDays(1)).first()
    val unreadable = repo.dayUnreadable(today).first()
    val bbCurve = repo.bodyBatteryCurve(today).first()
    val order = prefs.baselineOrder.first()
    val hidden = prefs.hiddenRows.first()
    val imperial = repo.imperial.first()
    val firstDay = repo.firstDayOfWeek.first()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDay))
    val weekScores = repo.scores(weekStart, weekStart.plusDays(6)).first().associateBy { it.date }
    // This week's activities plus the newest one overall (which may be from an earlier week, e.g. on a Monday morning);
    // strains and set counts are read only for those ids, not the whole history.
    val weekActivities = repo.activitiesBetween(weekStart, weekStart.plusDays(6)).first()
    val latest = repo.latestActivity().first()
    val recent = if (latest == null || weekActivities.any { it.id == latest.id }) weekActivities else weekActivities + latest
    val ids = recent.map { it.id }
    val strains = repo.activityScoresFor(ids).first().associate { it.activityId to it.strain }
    val sets = repo.activeSetCountsFor(ids).first()
    return buildWidgetSnapshot(
        today = today,
        zone = zone,
        is24h = is24h,
        keyStatus = keyStatus,
        syncing = isSyncing,
        lastSynced = lastSynced,
        day = day,
        score = score,
        history = history,
        unreadable = unreadable,
        bodyBatteryCurve = bbCurve,
        order = order,
        hidden = hidden,
        imperial = imperial,
        firstDayOfWeek = firstDay,
        recentActivities = recent,
        activityStrains = strains,
        activeSets = sets,
        weekScores = weekScores,
    )
}
