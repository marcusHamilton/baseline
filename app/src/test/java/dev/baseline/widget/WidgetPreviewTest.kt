package dev.baseline.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.baseline.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetPreviewTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun parseProviderLayouts(xmlRes: Int): Pair<Int, Int> {
        ctx.resources.getXml(xmlRes).use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "appwidget-provider") {
                    val androidNs = "http://schemas.android.com/apk/res/android"
                    val initial = parser.getAttributeResourceValue(androidNs, "initialLayout", 0)
                    val preview = parser.getAttributeResourceValue(androidNs, "previewLayout", 0)
                    return initial to preview
                }
                parser.next()
            }
        }
        error("Missing <appwidget-provider> in $xmlRes")
    }

    private fun collectVisibleTexts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val own =
            if (view is TextView && view.text.isNotBlank()) {
                listOf(view.text.toString())
            } else {
                emptyList()
            }
        val children =
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { collectVisibleTexts(view.getChildAt(it)) }
            } else {
                emptyList()
            }
        return own + children
    }

    private fun collectVisibleImages(view: View): List<ImageView> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val own = if (view is ImageView) listOf(view) else emptyList()
        val children =
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { collectVisibleImages(view.getChildAt(it)) }
            } else {
                emptyList()
            }
        return own + children
    }

    @Test
    fun allFiveWidgetPickerPreviewsInflateWithSampleValuesAndNonEmptyDrawables() {
        val specs =
            listOf(
                Triple("Readiness", R.xml.widget_readiness_info, "78%"),
                Triple("Vitals", R.xml.widget_vitals_info, "64 ms"),
                Triple("BodyBattery", R.xml.widget_body_battery_info, "74"),
                Triple("Sleep", R.xml.widget_sleep_info, "7h 45m"),
                Triple("WeeklyActivity", R.xml.widget_weekly_activity_info, "Morning Run"),
            )

        for ((name, xmlRes, expectedSample) in specs) {
            val (initialLayout, previewLayout) = parseProviderLayouts(xmlRes)
            assertTrue("$name initialLayout must be set", initialLayout != 0)
            assertTrue("$name previewLayout must be set", previewLayout != 0)
            assertNotEquals(
                "$name previewLayout must be distinct from initialLayout so sample values do not flash on drop",
                initialLayout,
                previewLayout,
            )

            val inflated = LayoutInflater.from(ctx).inflate(previewLayout, FrameLayout(ctx), false)
            val texts = collectVisibleTexts(inflated)
            assertTrue(
                "$name preview should contain sample metric '$expectedSample', got $texts",
                texts.any { it.contains(expectedSample) },
            )

            val images = collectVisibleImages(inflated)
            images.forEach { img ->
                assertNotNull("$name preview ImageView must have a static drawable", img.drawable)
            }
            if (name == "Readiness") {
                val dialLabels = texts.filter { it in setOf("SLEEP", "RECOVERY", "STRAIN") }
                assertEquals(
                    "Readiness preview dials must match TodayScreen order (SLEEP, RECOVERY, STRAIN)",
                    listOf("SLEEP", "RECOVERY", "STRAIN"),
                    dialLabels,
                )
            }
        }
    }
}
