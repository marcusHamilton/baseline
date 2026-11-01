package dev.baseline.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiRouterTest {
    private val overflowHybrid = Route.Note("You're offline, and this needs more than on-device AI can hold.")
    private val overflowOnDevice =
        Route.Note("This needs more room than on-device AI allows. Try a shorter question, or add an API key in Settings.")

    private fun input(
        mode: AiMode,
        tag: QuestionTag = QuestionTag.PAGE,
        first: Boolean = true,
        usedCloud: Boolean = false,
        nano: Boolean = true,
        fits: Boolean = true,
        key: Boolean = true,
        online: Boolean = true,
    ) = RouteInput(mode, tag, first, usedCloud, nano, fits, key, online)

    private data class Row(val input: RouteInput, val expected: Route, val why: String)

    @Test
    fun routingTable() {
        val rows =
            listOf(
                Row(input(AiMode.ON_DEVICE, key = false), Route.Nano, "on-device, fits"),
                Row(input(AiMode.ON_DEVICE, first = false, fits = false, key = false), overflowOnDevice, "on-device overflow"),
                Row(input(AiMode.CLOUD), Route.Cloud, "cloud, online"),
                Row(input(AiMode.CLOUD, online = false), Route.Error("You're offline."), "cloud, offline"),
                Row(input(AiMode.HYBRID), Route.Nano, "hybrid first page question"),
                Row(input(AiMode.HYBRID, tag = QuestionTag.TRAINING), Route.Cloud, "training goes to cloud"),
                Row(input(AiMode.HYBRID, tag = QuestionTag.PATTERN), Route.Cloud, "pattern goes to cloud"),
                Row(input(AiMode.HYBRID, first = false), Route.Cloud, "follow-up goes to cloud"),
                Row(input(AiMode.HYBRID, usedCloud = true), Route.Cloud, "a chat that used the cloud stays"),
                Row(input(AiMode.HYBRID, fits = false), Route.Cloud, "does not fit goes to cloud"),
                Row(input(AiMode.HYBRID, nano = false), Route.Cloud, "nano unusable goes to cloud"),
                Row(input(AiMode.HYBRID, first = false, usedCloud = true, online = false), Route.Nano, "offline follow-up falls to nano"),
                Row(input(AiMode.HYBRID, tag = QuestionTag.TRAINING, online = false), Route.Nano, "offline training question on nano"),
                Row(input(AiMode.HYBRID, key = false), Route.Nano, "no key, fits"),
                Row(input(AiMode.HYBRID, fits = false, online = false), overflowHybrid, "offline overflow"),
                Row(
                    input(AiMode.HYBRID, tag = QuestionTag.PATTERN, nano = false, fits = false, online = false),
                    Route.Error("AI isn't available right now."),
                    "nothing usable",
                ),
            )
        for (row in rows) assertEquals(row.why, row.expected, AiRouter.route(row.input))
    }

    @Test
    fun cloudUsableNeedsKeyAndConnection() {
        assertEquals(true, input(AiMode.HYBRID).cloudUsable)
        assertEquals(false, input(AiMode.HYBRID, key = false).cloudUsable)
        assertEquals(false, input(AiMode.HYBRID, online = false).cloudUsable)
    }

    @Test
    fun needsHistoryEscalatesInHybridWhenCloudUsable() {
        assertEquals(Route.Cloud, AiRouter.afterNeedsHistory(input(AiMode.HYBRID)))
    }

    @Test
    fun needsHistoryNoteWhenHybridIsOffline() {
        assertEquals(Route.Note("You're offline; this needs your history"), AiRouter.afterNeedsHistory(input(AiMode.HYBRID, online = false)))
        assertEquals(Route.Note("You're offline; this needs your history"), AiRouter.afterNeedsHistory(input(AiMode.HYBRID, key = false)))
    }

    @Test
    fun needsHistoryNoteWhenOnDevice() {
        assertEquals(
            Route.Note("On-device AI can only see this page. Add an API key in Settings for questions about your history"),
            AiRouter.afterNeedsHistory(input(AiMode.ON_DEVICE, key = false)),
        )
    }
}
