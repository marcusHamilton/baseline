package dev.baseline.ai.context

import dev.baseline.ai.engine.EngineKind

/** The system prompts (spec §5.3). Bump [PROMPT_VERSION] on any wording change: it is part of the insight cache fingerprint. */
object Prompts {
    const val PROMPT_VERSION = 1

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
        Answer only from the data above. If data is missing, say so.
        Quote the numbers you use, in the user's units.
        Be concise, with no tables unless asked. Don't invent data or diagnoses.
        Not medical advice. For chest pain, fainting, a resting heart rate far above baseline, or similar, suggest seeing a doctor.
        If answering needs data that isn't shown above, reply with exactly $NEEDS_HISTORY.
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
        return "In 2–3 sentences, tell the user the most useful thing about $subject, against their baselines. " +
            "Lead with the conclusion. No greeting, no advice to see a doctor unless something is clearly abnormal."
    }
}
