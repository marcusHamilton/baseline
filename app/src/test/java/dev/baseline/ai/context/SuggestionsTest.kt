package dev.baseline.ai.context

import dev.baseline.ai.QuestionTag
import dev.baseline.data.SportFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SuggestionsTest {
    private val date = LocalDate.of(2026, 11, 1)
    private val noFacts = PackFacts(null, null, null, null)

    private fun texts(scope: AskScope, facts: PackFacts, cloud: Boolean) = Suggestions.forScope(scope, facts, cloud).map { it.text }

    @Test
    fun todayWithCloud() {
        val out = Suggestions.forScope(AskScope.Today(date), noFacts.copy(recovery = 71), cloudSlots = true)
        assertEquals(
            listOf(
                "Why is my recovery 71%?",
                "What does today's strain target mean for me?",
                "Can I train hard today?",
                "What's been affecting my HRV lately?",
            ),
            out.map { it.text },
        )
        assertEquals(listOf(QuestionTag.PAGE, QuestionTag.PAGE, QuestionTag.TRAINING, QuestionTag.PATTERN), out.map { it.tag })
    }

    @Test
    fun todayOnDevice() {
        val out = Suggestions.forScope(AskScope.Today(date), noFacts.copy(recovery = 71), cloudSlots = false)
        assertEquals(
            listOf(
                "Why is my recovery 71%?",
                "What does today's strain target mean for me?",
                "How does today compare to my baseline?",
                "What should I focus on today?",
            ),
            out.map { it.text },
        )
        assertTrue(out.all { it.tag == QuestionTag.PAGE })
    }

    @Test
    fun missingFactDropsAndBackfills() {
        assertEquals(
            listOf(
                "What does today's strain target mean for me?",
                "How does today compare to my baseline?",
                "Can I train hard today?",
                "What's been affecting my HRV lately?",
            ),
            texts(AskScope.Today(date), noFacts, cloud = true),
        )
    }

    @Test
    fun sleepStageFilled() {
        assertTrue("Why was deep sleep up?" in texts(AskScope.Sleep(date), noFacts.copy(stageFurthest = "deep" to true), true))
        assertTrue("Why was REM sleep down?" in texts(AskScope.Sleep(date), noFacts.copy(stageFurthest = "REM" to false), true))
    }

    @Test
    fun sleepScoreSpareFilledOnDevice() {
        assertEquals(
            listOf("How did I sleep compared to usual?", "Why was my sleep score 82?", "How can I close my sleep gap?"),
            texts(AskScope.Sleep(date), noFacts.copy(sleepScore = 82), cloud = false),
        )
    }

    @Test
    fun activityBySport() {
        fun first(sport: SportFamily?) = texts(AskScope.Activity(1, sport), noFacts, true).first()
        assertEquals("How was my pacing?", first(SportFamily.RUN))
        assertEquals("How did my power hold up?", first(SportFamily.RIDE))
        assertEquals("How consistent were my lengths?", first(SportFamily.SWIM))
        assertEquals("How hard was this session?", first(SportFamily.STRENGTH))
        assertEquals("How hard was this session?", first(SportFamily.OTHER))
        assertEquals("How hard was this session?", first(null))
    }

    @Test
    fun trendsRange() {
        assertEquals("What's changed over this month?", texts(AskScope.Trends(30), noFacts, true).first())
        assertEquals("What's changed over this week?", texts(AskScope.Trends(7), noFacts, true).first())
        assertEquals("What's changed over this 3 months?", texts(AskScope.Trends(90), noFacts, true).first())
        assertEquals("What's changed over this range?", texts(AskScope.Trends(14), noFacts, true).first())
    }

    @Test
    fun generalHasNoPlaceholders() {
        assertEquals(
            listOf("How am I doing today?", "How did I sleep last night?", "How's my training week going?", "What's changed this month?"),
            texts(AskScope.General, noFacts, true),
        )
    }

    @Test
    fun neverMoreThanFour() {
        val scopes = listOf(AskScope.Today(date), AskScope.Sleep(date), AskScope.Activity(1, SportFamily.RUN), AskScope.Trends(7), AskScope.General)
        val facts = noFacts.copy(recovery = 50, sleepScore = 80, stageFurthest = "light" to false)
        for (scope in scopes) {
            for (cloud in listOf(true, false)) {
                for (f in listOf(noFacts, facts)) {
                    assertTrue("$scope $cloud", Suggestions.forScope(scope, f, cloud).size <= 4)
                }
            }
        }
    }
}
