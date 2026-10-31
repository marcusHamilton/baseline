package dev.baseline.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.baseline.data.SportFamily
import dev.baseline.data.db.DayEntity
import dev.baseline.data.db.ScoreEntity
import dev.baseline.scores.Contributor
import dev.baseline.scores.RecoveryResult
import dev.baseline.scores.RecoveryZone
import dev.baseline.ui.components.ActivityCardUi
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.ui.theme.Bg
import dev.baseline.ui.today.BodyBatteryUi
import dev.baseline.ui.today.StatRowUi
import dev.baseline.ui.today.TodayContent
import dev.baseline.ui.today.TodayUiState
import dev.baseline.ui.today.buildTrainingLoadUi
import dev.baseline.ui.trends.TrendRange
import dev.baseline.ui.trends.TrendsContent
import dev.baseline.ui.trends.buildTrendsState
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.math.sin

/**
 * Renders screens to PNGs without a device, for reviewing layout and copy in a cloud session (docs/cloud-sessions.md,
 * "Screenshots without an emulator"). Skipped unless Gradle gets `-Pscreenshots=<dir>`:
 *
 *     ./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ui.ScreenshotCapture' -Pscreenshots=<dir>
 *
 * Add a test per screen or state: build the screen's UI state from synthetic data (the screens' own `build…State`
 * functions are easier than a database) and call [capture]. Not a device: no system bars, insets or animations, and
 * no MapLibre map.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the default mode fakes text measurement, so nothing renders like the app
@Config(qualifiers = "w400dp-h1500dp-xxhdpi") // tall enough for a whole scrolling screen; [capture] crops it
class ScreenshotCapture {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out: File? = System.getProperty("baseline.screenshots")?.let(::File)

    @Before fun onlyWhenAsked() = assumeTrue("pass -Pscreenshots=<dir> to render", out != null)

    /**
     * Renders [content] the way the app does and writes `<dir>/<name>.png`, cropped to [heightDp] (null keeps the whole
     * 1500 dp) and halved to keep the files small. The Surface supplies the content colour the app gets from its
     * Scaffold; without it, text that doesn't set a colour comes out black.
     */
    private fun capture(name: String, heightDp: Int? = null, content: @Composable () -> Unit) {
        rule.setContent { BaselineTheme { Surface(color = Bg, contentColor = MaterialTheme.colorScheme.onBackground) { content() } } }
        rule.waitForIdle()
        val full = rule.onRoot().captureToImage().asAndroidBitmap()
        val px = heightDp?.let { (it * rule.density.density).toInt().coerceAtMost(full.height) } ?: full.height
        val cropped = Bitmap.createBitmap(full, 0, 0, full.width, px)
        val small = Bitmap.createScaledBitmap(cropped, cropped.width / 2, cropped.height / 2, true)
        out!!.mkdirs()
        File(out, "$name.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // Synthetic history: a weekly pattern with a rest day, and a build over the last [window] days.
    private val today = LocalDate.of(2026, 10, 29) // a Thursday
    private val first = today.minusDays(400)

    private fun load(d: LocalDate, build: Double, window: Long): Double {
        val base = when (d.dayOfWeek) {
            DayOfWeek.MONDAY -> 0.0
            DayOfWeek.TUESDAY -> 150.0
            DayOfWeek.WEDNESDAY -> 90.0
            DayOfWeek.THURSDAY -> 160.0
            DayOfWeek.FRIDAY -> 60.0
            DayOfWeek.SATURDAY -> 210.0
            DayOfWeek.SUNDAY -> 110.0
        }
        val daysAgo = today.toEpochDay() - d.toEpochDay()
        return base * (1 + 0.15 * sin(d.toEpochDay() / 9.0)) * (if (daysAgo < window) build else 1.0)
    }

    private fun scores(build: Double = 1.7, window: Long = 35) = (0..400L).map {
        val d = first.plusDays(it)
        ScoreEntity(d, 45 + ((it * 37) % 50).toInt(), false, 0.9, load(d, build, window), 12.0, null, null, 1)
    }

    private fun days() = (0..400L).map {
        DayEntity(
            first.plusDays(it),
            restingHr = 47 + (it % 5).toInt(),
            hrvLastNight = 52.0 + (it * 7 % 13),
            hrvWeeklyAvg = 56.0,
            hrvBalancedLow = 48.0,
            hrvBalancedUpper = 66.0,
            sleepSeconds = 25_000 + (it * 911 % 4_000).toInt(),
            sleepNeedMinutes = 470,
        )
    }

    private fun todayState(scores: List<ScoreEntity>, expanded: Boolean = false, earliest: LocalDate = first) = TodayUiState(
        today, true, 86,
        RecoveryResult(72, false, RecoveryZone.GREEN, listOf(Contributor("hrv", 1.0, 6.2), Contributor("rhr", 1.0, -2.0))),
        null, 11.4, 14.0..18.0,
        listOf(ActivityCardUi(9, "Morning Run", SportFamily.RUN, "running", "7:05 AM · 10.21 km · 52:14", 9.8)),
        listOf(
            StatRowUi("hrv", "HRV (overnight)", "58", "ms", "55 ms", "▲ 6%", true, "better"),
            StatRowUi("rhr", "Resting heart rate", "49", "bpm", "47 bpm", "▲ 2", false, "worse"),
        ),
        BodyBatteryUi(81, (0..60).map { Instant.ofEpochSecond(1_792_738_800L + it * 600L) to (25 + it).coerceAtMost(81) }, 62, null, false),
        Instant.ofEpochSecond(1_792_738_800L),
        today,
        trainingLoad = buildTrainingLoadUi(today, scores, earliest, DayOfWeek.MONDAY, 100.0, expanded),
    )

    private fun today(name: String, state: TodayUiState) = capture(name, heightDp = 1040) { TodayContent(state, null, {}, {}, {}, {}, {}, {}) }

    @Test fun todayCollapsed() = today("today-collapsed", todayState(scores()))

    @Test fun todayExpanded() = today("today-expanded", todayState(scores(), expanded = true))

    @Test fun todayOverreaching() = today("today-overreaching", todayState(scores(build = 2.6, window = 10), expanded = true))

    @Test fun todayCalibrating() = today("today-calibrating", todayState(scores(), earliest = today.minusDays(30)))

    @Test fun trends90Days() = capture("trends-90d", heightDp = 1380) {
        TrendsContent(buildTrendsState(TrendRange.D90, today, days(), scores(), first, 100.0), {})
    }
}
