package dev.baseline.ai.context

import dev.baseline.ai.QuestionTag
import dev.baseline.data.SportFamily

data class Suggestion(val text: String, val tag: QuestionTag)

/** Template questions for an Ask sheet (spec §3.7): instant, free, and able to quote the page's numbers. */
object Suggestions {
    private class Set(val pages: List<String?>, val training: String, val pattern: String, val spares: List<String?>)

    /**
     * At most four, in the order page, page, training, pattern. Without [cloudSlots] the two spare page questions take
     * the training and pattern slots. A template whose fact is missing is dropped and the next spare page question
     * takes its place.
     */
    fun forScope(scope: AskScope, facts: PackFacts, cloudSlots: Boolean): List<Suggestion> {
        val set = set(scope, facts)
        val spares = set.spares.filterNotNull().toMutableList()
        val pages = set.pages.filterNotNull().toMutableList()
        while (pages.size < 2 && spares.isNotEmpty()) pages += spares.removeAt(0)
        val out = pages.map { Suggestion(it, QuestionTag.PAGE) }.toMutableList()
        if (cloudSlots) {
            out += Suggestion(set.training, QuestionTag.TRAINING)
            out += Suggestion(set.pattern, QuestionTag.PATTERN)
        } else {
            spares.take(2).forEach { out += Suggestion(it, QuestionTag.PAGE) }
        }
        return out.take(4)
    }

    private fun set(scope: AskScope, facts: PackFacts): Set = when (scope) {
        is AskScope.Today ->
            Set(
                pages = listOf(facts.recovery?.let { "Why is my recovery $it%?" }, "What does today's strain target mean for me?"),
                training = "Can I train hard today?",
                pattern = "What's been affecting my HRV lately?",
                spares = listOf("How does today compare to my baseline?", "What should I focus on today?"),
            )

        is AskScope.Sleep ->
            Set(
                pages =
                listOf(
                    "How did I sleep compared to usual?",
                    facts.stageFurthest?.let { (stage, above) -> "Why was $stage sleep ${if (above) "up" else "down"}?" },
                ),
                training = "Is my sleep keeping up with my training?",
                pattern = "Do late workouts affect my sleep?",
                spares = listOf(facts.sleepScore?.let { "Why was my sleep score $it?" }, "How can I close my sleep gap?"),
            )

        is AskScope.Activity -> activity(scope.sport)

        is AskScope.Trends ->
            Set(
                pages = listOf("What's changed over this ${range(scope.days)}?", "What stands out in my HRV?"),
                training = "Am I overreaching?",
                pattern = "What helps my HRV?",
                spares = listOf("How has my sleep trended?", "How has my recovery trended?"),
            )

        AskScope.General ->
            Set(
                pages = listOf("How am I doing today?", "How did I sleep last night?"),
                training = "How's my training week going?",
                pattern = "What's changed this month?",
                spares = listOf("What does my recovery mean today?", "How was my last activity?"),
            )
    }

    private fun activity(sport: SportFamily?): Set = when (sport) {
        SportFamily.RUN ->
            Set(
                pages = listOf("How was my pacing?", "How hard was this compared to my recent runs?"),
                training = RECOVER,
                pattern = "Am I getting faster?",
                spares = listOf(ZONES, "How even were my splits?"),
            )

        SportFamily.RIDE ->
            Set(
                pages = listOf("How did my power hold up?", "How hard was this compared to my recent rides?"),
                training = RECOVER,
                pattern = "Is my cycling fitness improving?",
                spares = listOf(ZONES, "How did my speed change over the ride?"),
            )

        SportFamily.SWIM ->
            Set(
                pages = listOf("How consistent were my lengths?", "How hard was this compared to my recent swims?"),
                training = RECOVER,
                pattern = "Is my swim pace improving?",
                spares = listOf("How did my stroke rate change?", ZONES),
            )

        SportFamily.STRENGTH, SportFamily.OTHER, null ->
            Set(
                pages = listOf("How hard was this session?", "How does this compare to my recent sessions?"),
                training = RECOVER,
                pattern = "How often do I train like this?",
                spares = listOf(ZONES, "Which part was hardest?"),
            )
    }

    private fun range(days: Int): String = when (days) {
        7 -> "week"
        30 -> "month"
        90 -> "3 months"
        else -> "range"
    }

    private const val RECOVER = "How long should I recover?"
    private const val ZONES = "What do my heart rate zones say?"
}
