package dev.baseline.ui.today

import dev.baseline.scores.Contributor
import dev.baseline.scores.RecoveryZone
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

fun zoneTitle(zone: RecoveryZone) = when (zone) {
    RecoveryZone.GREEN -> "Recovered"
    RecoveryZone.AMBER -> "Maintaining"
    RecoveryZone.RED -> "Under-recovered"
}

/** One clause per contributor; the sign of [Contributor.delta] picks the direction word and the unit is the metric's own. */
fun contributorPhrase(c: Contributor): String {
    val whole = abs(c.delta).roundToInt()
    val tenths = "%.1f".format(Locale.US, abs(c.delta))
    val up = c.delta > 0
    return when (c.metric) {
        "hrv" -> if (whole == 0) "HRV is at your 30-day baseline" else "HRV is $whole% ${if (up) "above" else "below"} your 30-day baseline"
        "rhr" -> if (whole == 0) "resting HR is unchanged" else "resting HR is $whole bpm ${if (up) "above" else "below"}"
        "sleep" -> if (whole == 0) "sleep was on target" else "sleep was $whole points ${if (up) "above" else "below"} target"
        "resp" -> if (tenths == "0.0") "breathing rate is unchanged" else "breathing rate is $tenths brpm ${if (up) "higher" else "lower"}"
        else -> ""
    }
}

/** "HRV is 6% above your 30-day baseline and resting HR is 2 bpm below." from the top contributors; empty when there are none. */
fun insightSentence(top: List<Contributor>): String = top
    .map(::contributorPhrase)
    .filter { it.isNotEmpty() }
    .joinToString(" and ")
    .let { if (it.isEmpty()) it else it.replaceFirstChar(Char::uppercase) + "." }
