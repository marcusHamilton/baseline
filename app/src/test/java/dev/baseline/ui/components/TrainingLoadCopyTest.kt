package dev.baseline.ui.components

import dev.baseline.scores.FormState
import dev.baseline.scores.TrainingLoadDay
import dev.baseline.scores.WeekBudget
import dev.baseline.ui.today.TrainingLoadUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TrainingLoadCopyTest {
    private val w = LocalDate.of(2026, 10, 26)

    private fun ui(state: FormState, budget: WeekBudget? = WeekBudget(w, 62, false, 2, 4)) =
        TrainingLoadUi(TrainingLoadDay(w.plusDays(2), 64.0, 76.0, -18, state, null), budget)

    @Test fun weekLineVariants() {
        assertEquals("This week: 62% of normal · ~2 hard days left", weekLine(WeekBudget(w, 62, false, 2, 4)))
        assertEquals("This week: 62% of normal · ~1 hard day left", weekLine(WeekBudget(w, 62, false, 1, 4)))
        assertEquals("This week: 62% of normal · easy days from here", weekLine(WeekBudget(w, 62, false, 0, 4)))
        assertEquals("This week: 141% of normal", weekLine(WeekBudget(w, 141, true, 0, 4)))
        assertEquals("This week: 104% of normal", weekLine(WeekBudget(w, 104, false, 0, 0)))
        assertEquals("This week: 62% of normal", weekLine(WeekBudget(w, 62, false, null, 4)))
    }

    @Test fun formPercent() {
        assertEquals("\u221218%", formPctText(-18))
        assertEquals("+5%", formPctText(5))
        assertEquals("0%", formPctText(0))
    }

    @Test fun tags() {
        assertEquals("Productive week", weekTag(ui(FormState.PRODUCTIVE).day))
        assertEquals("Overreaching week", weekTag(ui(FormState.OVERREACHING).day))
        assertEquals("Calibrating", weekTag(ui(FormState.CALIBRATING).day))
        assertEquals("No recent training", weekTag(ui(FormState.NO_RECENT_TRAINING).day))
    }

    @Test fun calloutsOnlyWhenItMatters() {
        assertNull(weekCallout(ui(FormState.PRODUCTIVE)))
        assertNull(weekCallout(ui(FormState.MAINTAINING)))
        assertNull(weekCallout(ui(FormState.CALIBRATING, null)))
        assertNull(weekCallout(ui(FormState.NO_RECENT_TRAINING, null)))
        assertEquals("Fatigue is well above your fitness. Keep today easy.", weekCallout(ui(FormState.OVERREACHING)))
        assertEquals("You're rested. A good day for a hard session.", weekCallout(ui(FormState.FRESH)))
        assertEquals("You've trained well under your norm for a while; fitness is slipping.", weekCallout(ui(FormState.DETRAINING)))
        assertEquals(
            "You're past your safe range for the week. Keep the rest easy.",
            weekCallout(ui(FormState.PRODUCTIVE, WeekBudget(w, 141, true, 0, 4))),
        )
        // Overreaching wins over the safe range: it says the same thing, more strongly.
        assertEquals("Fatigue is well above your fitness. Keep today easy.", weekCallout(ui(FormState.OVERREACHING, WeekBudget(w, 141, true, 0, 4))))
    }

    @Test fun labels() {
        assertEquals(
            listOf("Calibrating", "No recent training", "Overreaching", "Productive", "Maintaining", "Fresh", "Detraining"),
            FormState.entries.map(::formStateLabel),
        )
    }
}
