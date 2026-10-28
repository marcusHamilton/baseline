package dev.baseline.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.baseline.scores.RecoveryZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGraphicsTest {
    @Test
    fun dialHandlesValidNullAndNaNFractions() {
        val full = WidgetGraphics.renderDialBitmap(0.75f, "74", "%", "Partial", WidgetGraphics.RECOVERY_GREEN, 160, 14f)
        assertEquals(160, full.width)
        assertEquals(160, full.height)
        assertTrue((0 until 160).any { x -> (0 until 160).any { y -> full.getPixel(x, y) != 0 } })

        val nullFrac = WidgetGraphics.renderDialBitmap(null, "—", "", null, WidgetGraphics.RECOVERY_GREEN, 160, 14f)
        assertEquals(160, nullFrac.width)

        val nanFrac = WidgetGraphics.renderDialBitmap(Float.NaN, "—", "", "14–18", WidgetGraphics.STRAIN_BLUE, 160, 14f)
        assertEquals(160, nanFrac.width)
    }

    @Test
    fun sparklineUsesActualZoneDayBoundsAcrossDstSpringForward() {
        val zone = ZoneId.of("America/Los_Angeles")
        val dstDay = LocalDate.of(2026, 3, 8) // 23-hour day (spring forward)
        val pts =
            listOf(
                ZonedDateTime.of(dstDay, LocalTime.of(6, 0), zone).toInstant() to 85,
                ZonedDateTime.of(dstDay, LocalTime.of(23, 30), zone).toInstant() to 40,
            )
        val bmp = WidgetGraphics.renderBodyBatterySparkline(pts, WidgetGraphics.RECOVERY_GREEN, dstDay, zone, 300, 80)
        assertEquals(300, bmp.width)
        assertEquals(80, bmp.height)
        // 23:30 on a 23h DST spring-forward day is 22.5h / 23h = 0.978 * 300 = ~293px; on a naive 86400s axis it would stop at ~281px.
        assertTrue(
            (286 until 300).any { x ->
                (0 until 80).any { y -> bmp.getPixel(x, y) == WidgetGraphics.RECOVERY_GREEN }
            },
        )

        val empty = WidgetGraphics.renderBodyBatterySparkline(emptyList(), WidgetGraphics.RECOVERY_GREEN, dstDay, zone, 300, 80)
        assertEquals(300, empty.width)
    }

    @Test
    fun stageBarWeekStripAndDimensionClamping() {
        val stage = WidgetGraphics.renderSleepStageBar(3_600, 3_600, 7_200, 0, 200, 16)
        assertEquals(WidgetGraphics.STAGE_DEEP, stage.getPixel(20, 8))
        assertEquals(WidgetGraphics.STAGE_REM, stage.getPixel(75, 8))
        assertEquals(WidgetGraphics.STAGE_LIGHT, stage.getPixel(150, 8))

        val emptyStage = WidgetGraphics.renderSleepStageBar(null, null, null, null, 200, 16)
        assertEquals(WidgetGraphics.SURFACE_2, emptyStage.getPixel(100, 8))

        val clampedZero = WidgetGraphics.renderHorizontalFillBar(0.5f, WidgetGraphics.RECOVERY_GREEN, 0, -5)
        assertTrue(clampedZero.width >= 1 && clampedZero.height >= 1)

        val clampedHuge =
            WidgetGraphics.renderWeekStripBitmap(
                List(7) { WeekDayCell("M", RecoveryZone.GREEN, true) },
                10_000,
                5_000,
            )
        assertEquals(WidgetGraphics.MAX_BITMAP_PX, clampedHuge.width)
        assertEquals(WidgetGraphics.MAX_BITMAP_PX, clampedHuge.height)

        assertEquals(1, WidgetGraphics.dpToClampedPx(-10f, 3f))
        assertEquals(WidgetGraphics.MAX_BITMAP_PX, WidgetGraphics.dpToClampedPx(5_000f, 3f))

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(WidgetGraphics.loadCondensedBold(ctx))
        assertEquals(WidgetGraphics.RECOVERY_GREEN, WidgetGraphics.recoveryArgb(RecoveryZone.GREEN))
        assertEquals(WidgetGraphics.RECOVERY_AMBER, WidgetGraphics.recoveryArgb(RecoveryZone.AMBER))
        assertEquals(WidgetGraphics.RECOVERY_RED, WidgetGraphics.recoveryArgb(RecoveryZone.RED))
        assertEquals(WidgetGraphics.RECOVERY_GREEN, WidgetGraphics.bodyBatteryArgb(80))
        assertEquals(WidgetGraphics.RECOVERY_AMBER, WidgetGraphics.bodyBatteryArgb(40))
        assertEquals(WidgetGraphics.RECOVERY_RED, WidgetGraphics.bodyBatteryArgb(15))
    }
}
