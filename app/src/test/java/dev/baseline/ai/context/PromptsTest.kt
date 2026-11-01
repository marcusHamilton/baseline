package dev.baseline.ai.context

import dev.baseline.ai.engine.EngineKind
import dev.baseline.data.SportFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PromptsTest {
    private val date = LocalDate.of(2026, 11, 1)

    @Test
    fun needsHistoryInstructionIsNanoOnly() {
        assertTrue("reply with exactly NEEDS_HISTORY" in Prompts.chat(EngineKind.NANO))
        assertFalse("reply with exactly NEEDS_HISTORY" in Prompts.chat(EngineKind.CLOUD))
        assertFalse("NEEDS_HISTORY" in Prompts.chat(EngineKind.CLOUD))
    }

    @Test
    fun nanoPromptPointsAtTheDataBelowIt() {
        val p = Prompts.chat(EngineKind.NANO)
        assertTrue("reply with exactly NEEDS_HISTORY" in p)
        assertFalse("above" in p)
        assertTrue("below" in p)
    }

    @Test
    fun bothChatPromptsCarryTheRules() {
        for (kind in EngineKind.entries) {
            val p = Prompts.chat(kind)
            assertTrue(kind.name, "You are the assistant inside Baseline, a personal health dashboard built on the user's Garmin data." in p)
            assertTrue(kind.name, "Not medical advice" in p)
            assertTrue(kind.name, "chest pain" in p && "fainting" in p)
            assertTrue(kind.name, "tables" in p)
            assertTrue(kind.name, "missing" in p)
        }
    }

    @Test
    fun insightPromptNamesItsSubject() {
        assertTrue("this night" in Prompts.insight(AskScope.Sleep(date)))
        assertTrue("this day" in Prompts.insight(AskScope.Today(date)))
        assertTrue("this activity" in Prompts.insight(AskScope.Activity(1, SportFamily.RUN)))
        assertTrue("this day" in Prompts.insight(AskScope.General))
        assertFalse("NEEDS_HISTORY" in Prompts.insight(AskScope.Today(date)))
    }

    @Test
    fun insightPromptAsksForNumbersAndBansTheOpener() {
        val p = Prompts.insight(AskScope.Today(date))
        assertTrue("numbers" in p)
        assertTrue("The most useful insight" in p)
        assertTrue("2 short sentences" in p)
    }

    @Test
    fun activityInsightIsReadAgainstRecordsNotABaseline() {
        val p = Prompts.insight(AskScope.Activity(1, SportFamily.RUN))
        assertTrue("record" in p)
        assertTrue("previous sessions" in p)
        assertFalse("baseline" in p)
    }
}
