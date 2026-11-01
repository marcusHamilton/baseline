package dev.baseline.ai.context

import dev.baseline.data.SportFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AskScopeTest {
    private val today = LocalDate.of(2026, 11, 1)

    @Test
    fun todayTitles() {
        assertEquals("Ask about today", AskScope.Today(today).title(today))
        assertEquals("Ask about Tue 27 Oct", AskScope.Today(LocalDate.of(2026, 10, 27)).title(today))
    }

    @Test
    fun sleepTitles() {
        assertEquals("Ask about last night", AskScope.Sleep(LocalDate.of(2026, 10, 31)).title(today))
        assertEquals("Ask about the night of 27 Oct", AskScope.Sleep(LocalDate.of(2026, 10, 27)).title(today))
    }

    @Test
    fun activityTitlesFollowTheSport() {
        val expected =
            mapOf(
                SportFamily.RUN to "this run",
                SportFamily.RIDE to "this ride",
                SportFamily.SWIM to "this swim",
                SportFamily.STRENGTH to "this session",
                SportFamily.OTHER to "this activity",
            )
        for ((sport, subject) in expected) {
            assertEquals("Ask about $subject", AskScope.Activity(1, sport).title(today))
            assertEquals(subject, AskScope.Activity(1, sport).subject(today))
        }
        assertEquals("Ask about this activity", AskScope.Activity(1, null).title(today))
    }

    @Test
    fun trendsAndGeneralTitles() {
        for (days in listOf(7, 30, 90)) assertEquals("Ask about your trends", AskScope.Trends(days).title(today))
        assertEquals("Ask about your health", AskScope.General.title(today))
        assertEquals("your trends", AskScope.Trends(30).subject(today))
        assertEquals("your health", AskScope.General.subject(today))
    }

    @Test
    fun subjectsReadAfterAbout() {
        assertEquals("today", AskScope.Today(today).subject(today))
        assertEquals("last night", AskScope.Sleep(today.minusDays(1)).subject(today))
    }

    @Test
    fun insightKeys() {
        assertEquals("today" to "2026-11-01", AskScope.Today(today).insightKey())
        assertEquals("sleep" to "2026-10-31", AskScope.Sleep(LocalDate.of(2026, 10, 31)).insightKey())
        assertEquals("activity" to "42", AskScope.Activity(42, SportFamily.RUN).insightKey())
        assertNull(AskScope.Trends(30).insightKey())
        assertNull(AskScope.General.insightKey())
    }
}
