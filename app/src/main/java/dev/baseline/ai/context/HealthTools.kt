package dev.baseline.ai.context

import dev.baseline.ai.engine.ToolResult
import dev.baseline.ai.engine.ToolRunner
import dev.baseline.data.SportFamily
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.prefs.UserPrefs
import dev.baseline.records.BestEfforts
import dev.baseline.scores.TrainingLoad
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

private const val MAX_RANGE_DAYS = 400L
private const val MAX_CONTENT_CHARS = 32_000
private const val MAX_NAMED_FIELDS = 3
private const val TRUNCATED = "(truncated — ask for a shorter range)"
private const val LOOKING_UP = "Looking up …"
private const val UNKNOWN_TOOL = """{"error":"unknown tool"}"""
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.US)

private val SPORTS = mapOf(
    "run" to SportFamily.RUN,
    "ride" to SportFamily.RIDE,
    "swim" to SportFamily.SWIM,
    "strength" to SportFamily.STRENGTH,
    "other" to SportFamily.OTHER,
)

private fun SportFamily.key() = name.lowercase(Locale.US)

private fun SportFamily.plural() = when (this) {
    SportFamily.RUN -> "runs"
    SportFamily.RIDE -> "rides"
    SportFamily.SWIM -> "swims"
    SportFamily.STRENGTH -> "strength sessions"
    SportFamily.OTHER -> "other activities"
}

/** One `daily_metrics` field: its name, how the status line spells it, and where its value comes from. */
private class Metric(val key: String, val label: String, val pick: (DayEntity?, ScoreEntity?) -> JsonElement)

private fun num(v: Double?, digits: Int): JsonElement {
    if (v == null || !v.isFinite()) return JsonNull
    val scale = Math.pow(10.0, digits.toDouble())
    return JsonPrimitive(Math.round(v * scale) / scale)
}

private fun int(v: Int?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

private val METRICS = listOf(
    Metric("recovery", "recovery") { _, s -> int(s?.recovery) },
    Metric("hrv", "HRV") { d, _ -> num(d?.hrvLastNight?.takeIf { it > 0 }, 1) },
    Metric("rhr", "resting HR") { d, _ -> int(d?.restingHr?.takeIf { it > 0 }) },
    Metric("sleep_performance", "sleep") { _, s -> int(s?.sleepPerformance?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt() }) },
    Metric("sleep_hours", "sleep hours") { d, _ -> num(d?.sleepSeconds?.takeIf { it > 0 }?.let { it / 3600.0 }, 2) },
    Metric("respiration", "breathing rate") { d, _ -> num(d?.respirationAvg?.takeIf { it > 0 }, 1) },
    Metric("body_battery_wake", "Body Battery") { d, _ -> int(d?.bodyBatteryWake) },
    Metric("stress", "stress") { d, _ -> int(d?.stressAvg) },
    Metric("steps", "steps") { d, _ -> int(d?.steps) },
    Metric("strain", "strain") { _, s -> num(s?.strain, 1) },
)

/** An argument the model got wrong; [reason] goes back to it as the tool result. */
private class BadArguments(val reason: String) : Exception(reason)

private class DateRange(val from: LocalDate, val to: LocalDate) {
    val label: String get() = "${from.format(SHORT_DATE)} – ${to.format(SHORT_DATE)}"
}

/**
 * The read-only functions the cloud model may call (spec: AI assistant §5.2). Each validates its arguments, answers with compact
 * JSON rows (`null` for a missing value) and caps the result at [MAX_CONTENT_CHARS]. Nothing here writes, syncs or uses the network.
 * Distances, durations and heart rates are in SI units, named in the keys (`distance_km`, `duration_min`).
 */
class HealthTools(private val db: AppDatabase, private val packs: ContextPacks, private val userPrefs: UserPrefs, private val today: StateFlow<LocalDate>) :
    ToolRunner {
    override val definitions: JsonArray = buildJsonArray {
        add(
            tool(
                "daily_metrics",
                "Daily health metrics, one row per day, with null for a missing value. Units: hrv ms, rhr bpm, sleep_performance %, " +
                    "sleep_hours hours, respiration breaths/min, recovery %, strain 0-21. At most 400 days per call.",
                buildJsonObject {
                    put("from", dateProperty("First day, inclusive (yyyy-mm-dd)."))
                    put("to", dateProperty("Last day, inclusive (yyyy-mm-dd). At most 400 days after from."))
                    put(
                        "fields",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "Which metrics to return. Omit for all of them.")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray { METRICS.forEach { add(JsonPrimitive(it.key)) } })
                                },
                            )
                        },
                    )
                },
                listOf("from", "to"),
            ),
        )
        add(
            tool(
                "activities",
                "The user's activities in a date range, one row per activity: id, sport, start (local time), duration_min, " +
                    "distance_km, avg_hr and strain. Pass an id to activity_detail for more. At most 400 days per call.",
                buildJsonObject {
                    put("from", dateProperty("First day, inclusive (yyyy-mm-dd)."))
                    put("to", dateProperty("Last day, inclusive (yyyy-mm-dd). At most 400 days after from."))
                    put(
                        "sport",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Only this sport. Omit for all.")
                            put("enum", buildJsonArray { SPORTS.keys.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                },
                listOf("from", "to"),
            ),
        )
        add(
            tool(
                "activity_detail",
                "Everything known about one activity: heart-rate zones, laps or splits, strain, form and the previous sessions of the same sport.",
                buildJsonObject {
                    put(
                        "id",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "An id from the activities tool.")
                        },
                    )
                },
                listOf("id"),
            ),
        )
        add(
            tool(
                "sleep_night",
                "One night of sleep in detail: stages against the 30-day average, overnight HRV, lowest heart rate and the last 30 nights.",
                buildJsonObject { put("date", dateProperty("The morning the night ended, the wake date (yyyy-mm-dd).")) },
                listOf("date"),
            ),
        )
        add(
            tool(
                "training_load",
                "Training load per day, one row per day: fitness, fatigue, form_pct and state. At most 400 days per call.",
                buildJsonObject {
                    put("from", dateProperty("First day, inclusive (yyyy-mm-dd)."))
                    put("to", dateProperty("Last day, inclusive (yyyy-mm-dd). At most 400 days after from."))
                },
                listOf("from", "to"),
            ),
        )
        add(
            tool(
                "records",
                "All-time best efforts: distance and time per distance, or watts per duration for power, with the date and activity id.",
                buildJsonObject {
                    put(
                        "sport",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Only this sport. Omit for all.")
                            put("enum", buildJsonArray { SPORTS.keys.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                },
                emptyList(),
            ),
        )
    }

    override suspend fun call(name: String, argumentsJson: String): ToolResult {
        if (name !in TOOL_NAMES) return ToolResult(LOOKING_UP, UNKNOWN_TOOL)
        val args = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: return ToolResult(LOOKING_UP, error("arguments must be a JSON object"))
        return try {
            when (name) {
                "daily_metrics" -> dailyMetrics(args)
                "activities" -> activities(args)
                "activity_detail" -> activityDetail(args)
                "sleep_night" -> sleepNight(args)
                "training_load" -> trainingLoad(args)
                else -> records(args)
            }
        } catch (e: BadArguments) {
            ToolResult(LOOKING_UP, error(e.reason))
        }
    }

    // ---- daily_metrics ----

    private suspend fun dailyMetrics(args: JsonObject): ToolResult {
        val range = range(args)
        val metrics = fields(args)
        val days = db.dayDao().range(range.from, range.to).first().associateBy { it.date }
        val scores = db.scoreDao().range(range.from, range.to).first().associateBy { it.date }
        val rows = dates(range).map { date ->
            buildJsonObject {
                put("date", date.toString())
                for (m in metrics) put(m.key, m.pick(days[date], scores[date]))
            }.toString()
        }
        // Ten field names make a long line under the answer; name them only when a few were asked for.
        val what = if (metrics.size > MAX_NAMED_FIELDS) "daily metrics" else metrics.joinToString(", ") { it.label }
        return ToolResult("Looking up $what, ${range.label}", capRows(rows))
    }

    private fun fields(args: JsonObject): List<Metric> {
        val raw = args["fields"]
        if (raw == null || raw is JsonNull) return METRICS
        val names = (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (names == null || names.any { it == null }) throw BadArguments("fields must be an array of strings")
        val picked = names.filterNotNull().distinct().map { n -> METRICS.firstOrNull { it.key == n } ?: throw BadArguments("unknown field: $n") }
        return picked.ifEmpty { METRICS }
    }

    // ---- activities ----

    private suspend fun activities(args: JsonObject): ToolResult {
        val range = range(args)
        val sport = sport(args)
        val label = "Looking up ${sport?.plural() ?: "activities"}, ${range.label}"
        val strain = db.activityScoreDao().all().first().associate { it.activityId to it.strain }
        val rows = db.activityDao().between(range.from, range.to.coerceAtMost(today.value)).first()
            .filter { sport == null || it.sportFamily == sport }
            .map { a ->
                buildJsonObject {
                    put("id", a.id)
                    put("sport", a.sportFamily.key())
                    if (a.sportFamily == SportFamily.OTHER) put("type", a.typeKey)
                    put("start", a.startTimeLocal.toString())
                    put("duration_min", num(a.durationSec.takeIf { it > 0 }?.let { it / 60.0 }, 1))
                    put("distance_km", num(a.distanceM?.takeIf { it > 0 }?.let { it / 1000.0 }, 2))
                    put("avg_hr", int(a.avgHr?.takeIf { it.isFinite() }?.roundToInt()))
                    put("strain", num(strain[a.id], 1))
                }.toString()
            }
        return ToolResult(label, capRows(rows))
    }

    private suspend fun activityDetail(args: JsonObject): ToolResult {
        val id = (args["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: throw BadArguments("id must be an integer")
        val body = packs.activityBody(id, PackSize.FULL) ?: return ToolResult("Looking up an activity", error("no activity with id $id"))
        return ToolResult("Looking up an activity", capText(body))
    }

    // ---- sleep_night ----

    private suspend fun sleepNight(args: JsonObject): ToolResult {
        val date = date(args, "date")
        val label = "Looking up sleep, night of ${date.minusDays(1).format(SHORT_DATE)}"
        val body = packs.sleepBody(date, PackSize.FULL) ?: return ToolResult(label, error("no sleep recorded for the night of ${date.minusDays(1)}"))
        return ToolResult(label, capText(body))
    }

    // ---- training_load ----

    private suspend fun trainingLoad(args: JsonObject): ToolResult {
        val range = range(args)
        val label = "Looking up training load, ${range.label}"
        val to = range.to.coerceAtMost(today.value)
        if (range.from.isAfter(to)) return ToolResult(label, "[]")
        val earliest = db.dayDao().firstDate().first()
        val loads = db.scoreDao().range(range.from.minusDays(TrainingLoad.WARM_UP_DAYS), to).first().associate { it.date to it.dayLoad }
        val tau = userPrefs.strainCalibration.first()?.tau
        val rows = TrainingLoad.series(loads, range.from, to, earliest, tau).map { d ->
            // Before the first stored day there is nothing to read, as in the Trends pack.
            val known = earliest != null && !d.date.isBefore(earliest)
            buildJsonObject {
                put("date", d.date.toString())
                put("fitness", num(d.fitness.takeIf { known }, 1))
                put("fatigue", num(d.fatigue.takeIf { known }, 1))
                put("form_pct", int(d.formPct.takeIf { known }))
                put("state", if (known) JsonPrimitive(d.state.name.lowercase(Locale.US)) else JsonNull)
            }.toString()
        }
        return ToolResult(label, capRows(rows))
    }

    // ---- records ----

    private suspend fun records(args: JsonObject): ToolResult {
        val sport = sport(args)
        val rows = db.bestEffortDao().all().first()
            .filter { sport == null || it.sport == sport }
            .mapNotNull { e ->
                val power = BestEfforts.isPower(e.effort)
                val n = e.effort.removePrefix("power_").removeSuffix(if (power) "s" else "m").toIntOrNull() ?: return@mapNotNull null
                Triple(e, power, n)
            }
            .sortedWith(compareBy({ it.first.sport.ordinal }, { it.second }, { it.third }))
            .map { (e, power, n) ->
                buildJsonObject {
                    put("sport", e.sport.key())
                    if (power) {
                        put("power_duration_s", n)
                        put("watts", int(e.valueSec.roundToInt()))
                    } else {
                        put("distance_m", n)
                        put("time", clock(e.valueSec))
                        put("time_s", num(e.valueSec, 1))
                    }
                    put("date", e.achievedOn.toString())
                    put("activity_id", e.activityId)
                }.toString()
            }
        return ToolResult("Looking up records", capRows(rows))
    }

    // ---- arguments ----

    private fun date(args: JsonObject, key: String): LocalDate {
        val bad = BadArguments("$key must be an ISO date (yyyy-mm-dd)")
        val p = args[key] as? JsonPrimitive ?: throw bad
        if (p is JsonNull) throw bad
        return try {
            LocalDate.parse(p.content)
        } catch (_: java.time.format.DateTimeParseException) {
            throw bad
        }
    }

    private fun range(args: JsonObject): DateRange {
        val from = date(args, "from")
        val to = date(args, "to")
        if (to.isBefore(from)) throw BadArguments("to must not be before from")
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) throw BadArguments("range is longer than $MAX_RANGE_DAYS days")
        return DateRange(from, to)
    }

    /** Every day of [range] up to today; later days have nothing to report. */
    private fun dates(range: DateRange): List<LocalDate> {
        val last = range.to.coerceAtMost(today.value)
        val count = ChronoUnit.DAYS.between(range.from, last) + 1
        return List(count.coerceAtLeast(0).toInt()) { range.from.plusDays(it.toLong()) }
    }

    private fun sport(args: JsonObject): SportFamily? {
        val raw = args["sport"]
        if (raw == null || raw is JsonNull) return null
        val name = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
        return SPORTS[name?.lowercase(Locale.US)] ?: throw BadArguments("sport must be one of ${SPORTS.keys.joinToString(", ")}")
    }
}

private val TOOL_NAMES = setOf("daily_metrics", "activities", "activity_detail", "sleep_night", "training_load", "records")

private fun error(reason: String): String = buildJsonObject { put("error", reason) }.toString()

private fun dateProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("format", "date")
    put("description", description)
}

private fun tool(name: String, description: String, properties: JsonObject, required: List<String>) = buildJsonObject {
    put("type", "function")
    put(
        "function",
        buildJsonObject {
            put("name", name)
            put("description", description)
            put(
                "parameters",
                buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                },
            )
        },
    )
}

/** A JSON array of [rows], cut at a row boundary with a note when it would pass [MAX_CONTENT_CHARS]. */
private fun capRows(rows: List<String>): String {
    val whole = rows.joinToString(",", "[", "]")
    if (whole.length <= MAX_CONTENT_CHARS) return whole
    val room = MAX_CONTENT_CHARS - TRUNCATED.length - 1
    val kept = StringBuilder("[")
    for (row in rows) {
        val add = (if (kept.length > 1) 1 else 0) + row.length
        if (kept.length + add + 1 > room) break
        if (kept.length > 1) kept.append(',')
        kept.append(row)
    }
    return kept.append("]\n").append(TRUNCATED).toString()
}

/** Plain text cut at a line boundary, with the same note. */
private fun capText(text: String): String {
    if (text.length <= MAX_CONTENT_CHARS) return text
    val room = MAX_CONTENT_CHARS - TRUNCATED.length - 1
    val cut = text.lastIndexOf('\n', room).takeIf { it > 0 } ?: room
    return text.substring(0, cut) + "\n" + TRUNCATED
}

private fun clock(seconds: Double): String {
    val total = seconds.roundToInt()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
}
