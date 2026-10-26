package dev.baseline.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.baseline.garmin.KeyStatus
import dev.baseline.ui.theme.BaselineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class KeyBannerTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val expires = LocalDate.of(2026, 10, 31)
    private val amber = "Your Garmin key expires in 7 days. Create a new one in Garmin Connect and paste it in Settings."
    private val expiredText = "Your Garmin key expired, so new data can't sync. Paste a new key in Settings."
    private val invalidText = "Garmin rejected your key, so new data can't sync. Paste a new key in Settings."

    @Test
    fun amberAt7DaysRedWhenExpired() {
        assertEquals(amber, keyBannerText(KeyStatus.ExpiringSoon(expires, 7)))
        assertEquals(expiredText, keyBannerText(KeyStatus.Expired(expires)))
        assertEquals(invalidText, keyBannerText(KeyStatus.Invalid))
        assertEquals(KeyBannerTone.AMBER, keyBannerTone(KeyStatus.ExpiringSoon(expires, 7)))
        assertEquals(KeyBannerTone.RED, keyBannerTone(KeyStatus.Expired(expires)))

        var status: KeyStatus? by androidx.compose.runtime.mutableStateOf<KeyStatus?>(KeyStatus.ExpiringSoon(expires, 7))
        rule.setContent { BaselineTheme { KeyBanner(status) } }
        rule.onNodeWithText(amber).assertIsDisplayed()
        status = KeyStatus.Expired(expires)
        rule.onNodeWithText(expiredText).assertIsDisplayed()
        status = KeyStatus.Ok(expires, 40)
        rule.onAllNodesWithText("Garmin key", substring = true).assertCountEquals(0)
    }

    @Test
    fun singularDayAndNoBannerWhenHealthy() {
        assertEquals(
            "Your Garmin key expires in 1 day. Create a new one in Garmin Connect and paste it in Settings.",
            keyBannerText(KeyStatus.ExpiringSoon(expires, 1)),
        )
        assertEquals(
            "Your Garmin key expires today. Create a new one in Garmin Connect and paste it in Settings.",
            keyBannerText(KeyStatus.ExpiringSoon(expires, 0)),
        )
        assertNull(keyBannerText(KeyStatus.Ok(expires, 90)))
        assertNull(keyBannerText(KeyStatus.Missing))
        assertNull(keyBannerText(null))
    }
}
