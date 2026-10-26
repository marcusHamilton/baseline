package dev.baseline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import java.time.Instant

/** "Last synced 3 h ago"; before the first sync there is no time to show. */
fun offlineText(lastSynced: Instant?, now: Instant): String = lastSynced?.let { "Last synced ${Formatters.ago(it, now)}" } ?: "Offline. Nothing synced yet."

/** The line under the status bar while the phone is offline; draws nothing when online. Cached data stays readable below it. */
@Composable
fun OfflineBar(online: Boolean, lastSynced: Instant?, now: Instant, modifier: Modifier = Modifier) {
    if (online) return
    Text(
        offlineText(lastSynced, now),
        modifier.fillMaxWidth().testTag("offline-bar").background(Surface2).padding(horizontal = 20.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 12.sp,
        color = TextBody,
        textAlign = TextAlign.Center,
    )
}
