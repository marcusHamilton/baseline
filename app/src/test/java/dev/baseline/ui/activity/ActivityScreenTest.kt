package dev.baseline.ui.activity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.garmin.fit.ExerciseCategory
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.LoadSource
import dev.baseline.data.db.StrengthSetEntity
import dev.baseline.fit.FitReader
import dev.baseline.fit.fitFixture
import dev.baseline.ui.activities.act
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActivityScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val runStream = ActivityStreamEntity(1, FitReader.read(fitFixture("run")).streams.pack())
    private val mapped = mutableListOf<List<Pair<Double, Double>>>()

    /** Robolectric cannot run MapLibre's native code: the map is a tagged box that records what it was asked to draw. */
    private val stubMap: @Composable (List<Pair<Double, Double>>) -> Unit = {
        mapped += it
        Box(Modifier.fillMaxSize().testTag("map-stub"))
    }

    private fun show(state: ActivityUiState, onBack: () -> Unit = {}) = rule.setContent { BaselineTheme { ActivityContent(state, onBack, stubMap) } }

    private fun top(tag: String) = rule
        .onNodeWithTag(tag)
        .fetchSemanticsNode()
        .positionInRoot.y

    private fun runState(
        a: ActivityEntity =
            act(1).copy(
                avgHr = 152.0,
                maxHr = 171.0,
                elevationGainM = 86.0,
                garminLoad = 112.0,
                avgPower = 268.0,
                zone1Sec = 192.0,
                zone2Sec = 880.0,
                zone3Sec = 1325.0,
                zone4Sec = 647.0,
                zone5Sec = 90.0,
            ),
    ) = buildActivityState(a, ActivityScoreEntity(1, 112.0, LoadSource.HR, 9.8), runStream, emptyList(), emptyList(), null, Formatters(false))

    @Test
    fun runSectionsRenderInOrder() {
        show(runState())
        val order = listOf("section-map", "summary", "section-hr", "section-zones", "section-splits", "section-running")
        order.forEach { rule.onNodeWithTag(it).assertExists() }
        val ys = order.map(::top)
        assertEquals("sections should run top to bottom in order: $ys", ys.sorted(), ys)
        rule.onNodeWithTag("map-stub").assertExists()
        assertEquals(1, mapped.size)
        assertTrue(mapped.single().size > 100)
        rule.onNodeWithText("ACTIVITY 1").assertExists()
        rule.onNodeWithText("Sat, Oct 24 · 7:05 AM").assertExists()
        rule.onNodeWithText("Garmin load 112").assertExists()
        rule.onNodeWithText("Z3 Aerobic").assertExists()
        rule.onNodeWithText("22:05").assertExists()
        rule.onNodeWithText("avg 152 · max 171").assertExists()
        rule.onNodeWithText("Detailed data downloading…").assertDoesNotExist()
    }

    @Test
    fun downloadingNoteInsteadOfStreamSections() {
        show(buildActivityState(act(1), null, null, emptyList(), emptyList(), null, Formatters(false)))
        rule.onNodeWithText("Detailed data downloading…").assertExists()
        rule.onNodeWithTag("section-map").assertDoesNotExist()
        rule.onNodeWithTag("section-hr").assertDoesNotExist()
        rule.onNodeWithTag("summary").assertExists()
        assertTrue(mapped.isEmpty())
    }

    @Test
    fun strengthShowsChipsAndBackIsAtLeast48dp() {
        var back = 0

        fun set(i: Int, cat: Int, reps: Int, kg: Double?) = StrengthSetEntity(
            activityId = 1,
            setIndex = i,
            startOffsetSec = i * 60.0,
            exerciseName = null,
            setType = "ACTIVE",
            category = cat,
            reps = reps,
            weightKg = kg,
            durationSec = 40.0,
        )
        val sets = listOf(set(0, ExerciseCategory.BENCH_PRESS, 6, 100.0), set(1, ExerciseCategory.PULL_UP, 8, null))
        show(buildActivityState(act(1, "strength_training", distanceM = null), null, null, emptyList(), sets, null, Formatters(false)), { back++ })
        rule.onNodeWithText("Bench Press").assertExists()
        rule.onNodeWithText("6 × 100").assertExists()
        rule.onNodeWithText("8").assertExists()
        rule
            .onNodeWithContentDescription("Back")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, back)
    }

    @Test
    fun missingActivityShowsNotFound() {
        show(buildActivityState(null, null, null, emptyList(), emptyList(), null, Formatters(false)))
        rule.onNodeWithText("Activity not found").assertExists()
    }
}
