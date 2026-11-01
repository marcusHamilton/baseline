package dev.baseline.ai.context

import dev.baseline.data.SportFamily
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an Ask sheet is about (spec §3.5). [Sleep] holds the WAKE date, the morning the night ended: the same date the
 * Sleep screen shows and the `DayEntity` row it reads ("last night" is `Sleep(today)`, and `Sleep(Sun 1 Nov)` is
 * titled "the night of 31 Oct" once it is no longer today).
 * [Trends] holds the range in days (7, 30 or 90), so this package stays free of UI types.
 */
sealed interface AskScope {
    data class Today(val date: LocalDate) : AskScope

    data class Sleep(val date: LocalDate) : AskScope

    data class Activity(val id: Long, val sport: SportFamily?) : AskScope

    data class Trends(val days: Int) : AskScope

    data object General : AskScope
}

private val WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.US)

/** "Ask about today", "Ask about the night of 27 Oct", "Ask about this run" …: the spec §3.5 table. */
fun AskScope.title(today: LocalDate): String = "Ask about ${subject(today)}"

/** The scope as it reads after "about" in titles, and in hints, links and placeholders. */
fun AskScope.subject(today: LocalDate): String = when (this) {
    is AskScope.Today -> if (date == today) "today" else date.format(WEEKDAY_DAY_MONTH)

    is AskScope.Sleep -> if (date == today) "last night" else "the night of ${date.minusDays(1).format(DAY_MONTH)}"

    is AskScope.Activity ->
        when (sport) {
            SportFamily.RUN -> "this run"
            SportFamily.RIDE -> "this ride"
            SportFamily.SWIM -> "this swim"
            SportFamily.STRENGTH -> "this session"
            SportFamily.OTHER, null -> "this activity"
        }

    is AskScope.Trends -> "your trends"

    AskScope.General -> "your health"
}

/** The key a cached insight is stored under; null for the scopes that have no insight card. */
fun AskScope.insightKey(): Pair<String, String>? = when (this) {
    is AskScope.Today -> "today" to date.toString()
    is AskScope.Sleep -> "sleep" to date.toString()
    is AskScope.Activity -> "activity" to id.toString()
    is AskScope.Trends, AskScope.General -> null
}
