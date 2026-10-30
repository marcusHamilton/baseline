package dev.baseline.ui.calendar

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
class CalendarScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.of(2026, 10, 24)
    private val d = today.minusDays(1)

    private fun act(id: Long) = ActivityEntity(
        id,
        "Run",
        "running",
        SportFamily.RUN,
        LocalDateTime.of(d, LocalTime.NOON),
        d,
        Instant.EPOCH,
        1.0,
        distanceM = null,
        avgHr = null,
        maxHr = null,
        zone1Sec = null,
        zone2Sec = null,
        zone3Sec = null,
        zone4Sec = null,
        zone5Sec = null,
        garminLoad = null,
        elevationGainM = null,
        avgPower = null,
        normPower = null,
        fastestSplitsJson = null,
        fitPath = null,
        hrCoverage = null,
        poolLengthM = null,
    )

    private val state =
        buildCalendarState(
            YearMonth.of(2026, 10),
            DayOfWeek.MONDAY,
            d,
            today,
            emptyList(),
            listOf(ScoreEntity(d, 77, false, 0.9, 100.0, 11.4, null, null, 1)),
            listOf(act(1), act(2)),
        )

    @Test
    fun cellsAreDescribedTappableAndFutureDaysAreNot() {
        val picked = mutableListOf<LocalDate>()
        val opened = mutableListOf<LocalDate>()
        rule.setContent { BaselineTheme { CalendarContent(state, {}, {}, {}, { picked += it }, { opened += it }) } }
        rule
            .onNodeWithContentDescription(
                "October 23, recovery 77%, 2 activities",
            ).assertHasClickAction()
            .assertWidthIsAtLeast(40.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("October 25, no data yet").assertHasNoClickAction()
        rule.onNodeWithContentDescription("October 24, no data").performClick()
        assertEquals(listOf(today), picked)
        rule
            .onNodeWithContentDescription("Next month")
            .assertIsNotEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Run · Run").performScrollTo()
        rule
            .onNodeWithText("Open day →")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf(d), opened)
    }

    private fun sundayHighlightEndsWithinTheCards() {
        val sunday = LocalDate.of(2026, 10, 18)
        val s = buildCalendarState(YearMonth.of(2026, 10), DayOfWeek.MONDAY, sunday, today, emptyList(), emptyList(), emptyList())
        rule.setContent { BaselineTheme { CalendarContent(s, {}, {}, {}, {}, {}) } }
        val desc = hasContentDescription("October 18, no data")
        rule.onNode(desc).assertWidthIsAtLeast(48.dp)
        val cell = rule.onNode(hasTestTag("day-highlight") and hasAnyAncestor(desc), useUnmergedTree = true).getBoundsInRoot()
        rule.onNodeWithTag("day-card").performScrollTo()
        val card = rule.onNodeWithTag("day-card").getBoundsInRoot()
        assertTrue("cell ${cell.right} card ${card.right}", cell.right <= card.right)
    }

    @Test
    @Config(qualifiers = "w360dp")
    fun selectedSundayStaysInsideTheCardsAt360() = sundayHighlightEndsWithinTheCards()

    @Test
    @Config(qualifiers = "w411dp")
    fun selectedSundayStaysInsideTheCardsAt411() = sundayHighlightEndsWithinTheCards()

    // Robolectric's stand-in font is a pixel or so per glyph, so real label widths can't overflow a phone-sized screen here.
    // A narrow box gives the same squeeze a 320 dp screen at large font scale puts on the real font.
    @Test
    fun legendWrapsInsteadOfClippingWhenItIsSqueezed() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                BaselineTheme { Box(Modifier.width(110.dp)) { Legend() } }
            }
        }
        // The legend hides its semantics from TalkBack, so read it from the unmerged tree.
        val labels = listOf("Recovered", "Maintaining", "Low", "Activity").map { rule.onNodeWithText(it, useUnmergedTree = true).getBoundsInRoot() }
        labels.forEach { assertTrue("$it outside the 110 dp box", it.left >= 0.dp && it.right <= 110.dp) }
        // Wrapped onto a second row rather than squeezed into one.
        assertTrue("labels $labels", labels.last().top > labels.first().top)
    }

    @Test
    fun monthTitleIsAHeading() {
        rule.setContent { BaselineTheme { CalendarContent(state, {}, {}, {}, {}, {}) } }
        rule.onNode(isHeading() and hasText("OCTOBER 2026")).assertExists()
    }
}
