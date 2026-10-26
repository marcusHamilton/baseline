package dev.baseline.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class OfflineBarTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-24T12:00:00Z")

    @Test
    fun textUsesRelativeTimeOrSaysNothingSynced() {
        assertEquals("Last synced 3 h ago", offlineText(now.minusSeconds(3 * 3600), now))
        assertEquals("Offline. Nothing synced yet.", offlineText(null, now))
    }

    @Test
    fun onlyShownWhileOffline() {
        var online by mutableStateOf(false)
        rule.setContent { BaselineTheme { OfflineBar(online, now.minusSeconds(600), now) } }
        rule.onNodeWithText("Last synced 10 min ago").assertIsDisplayed()
        online = true
        rule.onNodeWithTag("offline-bar").assertDoesNotExist()
    }
}
