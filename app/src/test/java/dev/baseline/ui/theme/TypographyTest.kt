package dev.baseline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TypographyTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyStyleUsesBarlowOrInter() {
        rule.setContent {
            BaselineTheme {
                val t = MaterialTheme.typography
                val barlow =
                    listOf(
                        t.displayLarge,
                        t.displayMedium,
                        t.displaySmall,
                        t.headlineLarge,
                        t.headlineMedium,
                        t.headlineSmall,
                        t.titleLarge,
                        t.titleMedium,
                        t.titleSmall,
                    )
                val inter =
                    listOf(
                        t.bodyLarge,
                        t.bodyMedium,
                        t.bodySmall,
                        t.labelLarge,
                        t.labelMedium,
                        t.labelSmall,
                    )
                barlow.forEach { assertEquals(BarlowCondensed, it.fontFamily) }
                inter.forEach { assertEquals(Inter, it.fontFamily) }
            }
        }
        rule.waitForIdle()
    }
}
