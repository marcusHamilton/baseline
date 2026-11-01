package dev.baseline.ai.context

import dev.baseline.ai.engine.EngineKind

/** The system prompts (spec §5.3). Bump [PROMPT_VERSION] on any wording change: it is part of the insight cache fingerprint. */
object Prompts {
    const val PROMPT_VERSION = 2

    /** What Nano replies, and nothing else, when the question needs data beyond the page it was shown. */
    const val NEEDS_HISTORY = "NEEDS_HISTORY"

    private const val ROLE = "You are the assistant inside Baseline, a personal health dashboard built on the user's Garmin data."

    private val CLOUD_CHAT =
        """
        $ROLE
        Answer only from the data given and from tool results. If data is missing, say so.
        Quote the numbers you rely on, in the user's units.
        Be concise: short paragraphs, and no tables unless asked.
        Don't invent data or diagnoses.
        Not medical advice. For chest pain, fainting, a resting heart rate that stays far above baseline, or similar, suggest seeing a doctor.
        """.trimIndent()

    private val NANO_CHAT =
        """
        $ROLE
        Answer only from the data below. If data is missing, say so.
        Quote the numbers you use, in the user's units.
        Be concise, with no tables unless asked. Don't invent data or diagnoses.
        Not medical advice. For chest pain, fainting, a resting heart rate far higher than baseline, or similar, suggest seeing a doctor.
        If answering needs data that isn't shown below, reply with exactly $NEEDS_HISTORY.
        """.trimIndent()

    fun chat(engine: EngineKind): String = when (engine) {
        EngineKind.NANO -> NANO_CHAT
        EngineKind.CLOUD -> CLOUD_CHAT
    }

    /** The insight prompt for a page. Insights never use tools or NEEDS_HISTORY; General and Trends have no insight card and get the Today wording. */
    fun insight(scope: AskScope): String {
        val subject =
            when (scope) {
                is AskScope.Sleep -> "this night"
                is AskScope.Activity -> "this activity"
                is AskScope.Today, is AskScope.Trends, AskScope.General -> "this day"
            }
        return "In 2 short sentences, name the one or two signals that moved furthest from their baseline for $subject, " +
            "with their numbers and the baselines. Don't restate the headline score and don't give advice. " +
            "Start with the finding: no greeting, and no opener such as \"The most useful insight\". " +
            "Don't say \"significant\" or \"substantial\" without a figure. If nothing stands out, say it is a typical day. " +
            "Mention a doctor only if something is clearly abnormal."
    }
}
