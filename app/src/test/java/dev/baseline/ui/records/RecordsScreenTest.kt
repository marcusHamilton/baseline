package dev.baseline.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.baseline.data.SportFamily
import dev.baseline.data.db.BestEffortEntity
import dev.baseline.ui.activities.TODAY
import dev.baseline.ui.activities.act
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val efforts =
        listOf(
            BestEffortEntity(SportFamily.RUN, "5000m", 1476.757, 1, TODAY.minusDays(3)),
            BestEffortEntity(SportFamily.RIDE, "power_300s", 318.0, 2, TODAY.minusDays(90)),
        )
    private val activities = listOf(act(1, name = "Parkrun"), act(2, "virtual_ride", name = "Zwift"))

    private fun state(tab: SportFamily) = buildRecordsState(tab, efforts, activities, TODAY, Formatters(false))

    @Test fun rowOpensItsActivityAndTabsAndBackAreBigEnough() {
        val opened = mutableListOf<Long>()
        var back = 0
        rule.setContent { BaselineTheme { RecordsContent(state(SportFamily.RUN), {}, { back++ }, { opened += it }) } }
        rule.onNodeWithText("24:37").assertExists()
        rule.onNodeWithText("NEW").assertExists()
        rule.onNodeWithText("4:55 /km · Parkrun · Oct 21, 2026").performClick()
        assertEquals(listOf(1L), opened)
        rule.onNodeWithText("1 km").assertHasNoClickAction() // no record yet
        listOf("Run", "Ride", "Swim").forEach { rule.onNodeWithText(it).assertHasClickAction().assertHeightIsAtLeast(48.dp) }
        rule.onNodeWithContentDescription("Back to Activities").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, back)
    }

    @Test fun rideTabShowsIndoorPowerCard() {
        assertNull(state(SportFamily.RUN).power)
        rule.setContent { BaselineTheme { RecordsContent(state(SportFamily.RIDE), {}, {}, {}) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("BEST POWER · INDOOR")) // below the fold in a lazy list
        rule.onNodeWithContentDescription("5 min: 318 watts").assertExists()
        rule.onNodeWithContentDescription("5 s: no record").assertExists()
    }

    @Test fun titleAndSectionTitlesAreHeadings() {
        rule.setContent { BaselineTheme { RecordsContent(state(SportFamily.RIDE), {}, {}, {}) } }
        rule.onNode(isHeading() and hasText("ALL-TIME RECORDS")).assertExists()
        rule.onNode(isHeading() and hasText("FASTEST TIMES")).assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("BEST POWER · INDOOR"))
        rule.onNode(isHeading() and hasText("BEST POWER · INDOOR")).assertExists()
    }
}
