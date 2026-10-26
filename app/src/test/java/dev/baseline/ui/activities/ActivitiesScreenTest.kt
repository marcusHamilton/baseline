package dev.baseline.ui.activities

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.baseline.data.SportFamily
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class ActivitiesScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val activities = listOf(
        act(1, name = "Morning Run"),
        act(2, "strength_training", time = LocalTime.of(18, 0), durationSec = 2760.0, distanceM = null, name = "Evening Lift"),
        act(3, "road_biking", day = TODAY.minusDays(2), durationSec = 5660.0, distanceM = 42_600.0, name = "Lunch Ride"),
    )
    private fun state(filter: SportFamily?) = build(activities, filter, strains = mapOf(1L to 9.8), sets = mapOf(2L to 18))
    private fun selected(on: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, on)

    @Test
    fun chipFiltersTheListAndCardOpensTheActivity() {
        var ui by mutableStateOf(state(null))
        val opened = mutableListOf<Long>()
        var records = 0
        rule.setContent {
            BaselineTheme { ActivitiesContent(ui, { ui = state(it) }, { records++ }, { opened += it }) }
        }
        listOf("All", "Run", "Ride", "Strength", "Swim", "Other").forEach { rule.onNodeWithText(it).assertHasClickAction() } // M3 chips get a 48dp touch target from the pointer input, not from their bounds
        rule.onNodeWithText("Today · Sat, Oct 24").assertExists()
        rule.onNodeWithText("46 min · 18 sets").assertExists()
        rule.onNodeWithText("All").assert(selected(true))
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Thu, Oct 22")) // below the fold in a lazy list
        rule.onNodeWithText("Lunch Ride").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("ACTIVITIES"))

        rule.onNodeWithText("Run").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Run").assert(selected(true))
        rule.onNodeWithText("All").assert(selected(false))
        rule.onNodeWithText("Morning Run").assertExists()
        rule.onNodeWithText("Evening Lift").assertDoesNotExist()
        rule.onNodeWithText("Lunch Ride").assertDoesNotExist()

        rule.onNodeWithText("Morning Run").performClick()
        assertEquals(listOf(1L), opened)

        rule.onNodeWithContentDescription("Records").assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(1, records)
    }

    @Test
    fun weekSummaryShowsTotals() {
        rule.setContent { BaselineTheme { ActivitiesContent(state(null), {}, {}, {}) } }
        rule.onNodeWithContentDescription("This week: 3 activities, 3:13 hours, 52.8 km, average strain 9.8").assertExists()
    }

    @Test
    fun emptyWeekShowsDashes() {
        rule.setContent { BaselineTheme { ActivitiesContent(build(emptyList()), {}, {}, {}) } }
        rule.onNodeWithContentDescription("This week: 0 activities, — hours, — km, average strain —").assertExists()
    }
}
