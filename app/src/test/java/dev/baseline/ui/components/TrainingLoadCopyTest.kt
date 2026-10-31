package dev.baseline.ui.components

import dev.baseline.scores.FormState
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.today.TrainingLoadUi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TrainingLoadCopyTest {
    private val w = LocalDate.of(2026, 10, 26)
    private val productive = TrainingLoadDay(w.plusDays(2), 64.0, 76.0, -18, FormState.PRODUCTIVE, null)

    @Test fun budgetLineVariants() {
        assertEquals("62% of a normal week · room for ~2 hard days", budgetLine(WeekBudget(w, 62, false, 2, 4)))
        assertEquals("62% of a normal week · room for ~1 hard day", budgetLine(WeekBudget(w, 62, false, 1, 4)))
        assertEquals("62% of a normal week · easy days from here", budgetLine(WeekBudget(w, 62, false, 0, 4)))
        assertEquals("141% of a normal week · past your safe range", budgetLine(WeekBudget(w, 141, true, 0, 4)))
        assertEquals("104% of a normal week", budgetLine(WeekBudget(w, 104, false, 0, 0)))
        assertEquals("62% of a normal week", budgetLine(WeekBudget(w, 62, false, null, 4)))
    }

    @Test fun formPercent() {
        assertEquals("−18%", formPctText(-18))
        assertEquals("+5%", formPctText(5))
        assertEquals("0%", formPctText(0))
    }

    @Test fun labelsAndMeanings() {
        assertEquals(
            listOf("Calibrating", "No recent training", "Overreaching", "Productive", "Maintaining", "Fresh", "Detraining"),
            FormState.entries.map(::formStateLabel),
        )
        assertEquals("You're building fitness at a pace you can absorb.", formStateMeaning(FormState.PRODUCTIVE))
        assertEquals(null, formStateMeaning(FormState.CALIBRATING))
        assertEquals(null, formStateMeaning(FormState.NO_RECENT_TRAINING))
    }

    @Test fun description() {
        assertEquals(
            "Training load: Productive, form minus 18 percent. 62 percent of a normal week, room for about 2 hard days.",
            trainingLoadDescription(TrainingLoadUi(productive, WeekBudget(w, 62, false, 2, 4), false)),
        )
        assertEquals(
            "Training load: Productive, form minus 18 percent. 141 percent of a normal week, past your safe range.",
            trainingLoadDescription(TrainingLoadUi(productive, WeekBudget(w, 141, true, 0, 4), false)),
        )
        val cal = TrainingLoadDay(w, 0.0, 0.0, null, FormState.CALIBRATING, 12)
        assertEquals("Training load: Calibrating, 12 days to go.", trainingLoadDescription(TrainingLoadUi(cal, null, false)))
        val rest = TrainingLoadDay(w, 0.0, 0.0, null, FormState.NO_RECENT_TRAINING, null)
        assertEquals("Training load: No recent training.", trainingLoadDescription(TrainingLoadUi(rest, null, false)))
    }
}
