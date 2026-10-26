package dev.baseline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.garmin.KeyStatus
import dev.baseline.ui.theme.BannerAmberBg
import dev.baseline.ui.theme.BannerAmberFg
import dev.baseline.ui.theme.BannerRedBg
import dev.baseline.ui.theme.BannerRedFg

enum class KeyBannerTone { AMBER, RED }

/** Amber from 7 days before expiry, red once expired or rejected; null (no banner) otherwise. */
fun keyBannerTone(status: KeyStatus?): KeyBannerTone? = when (status) {
    is KeyStatus.ExpiringSoon -> KeyBannerTone.AMBER
    is KeyStatus.Expired, KeyStatus.Invalid -> KeyBannerTone.RED
    is KeyStatus.Ok, KeyStatus.Missing, null -> null
}

fun keyBannerText(status: KeyStatus?): String? = when (status) {
    is KeyStatus.ExpiringSoon -> {
        val within = when (val n = status.daysLeft) { 0 -> "today"; 1 -> "in 1 day"; else -> "in $n days" }
        "Your Garmin key expires $within. Create a new one in Garmin Connect and paste it in Settings."
    }
    is KeyStatus.Expired -> "Your Garmin key expired, so new data can't sync. Paste a new key in Settings."
    KeyStatus.Invalid -> "Garmin rejected your key, so new data can't sync. Paste a new key in Settings."
    is KeyStatus.Ok, KeyStatus.Missing, null -> null
}

/** The key-expiry notice for Today and Settings. Draws nothing while the key is healthy. */
@Composable
fun KeyBanner(status: KeyStatus?, modifier: Modifier = Modifier) {
    val text = keyBannerText(status) ?: return
    val red = keyBannerTone(status) == KeyBannerTone.RED
    Text(
        text,
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (red) BannerRedBg else BannerAmberBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, // the mockup's role="status"
        color = if (red) BannerRedFg else BannerAmberFg, fontSize = 14.sp, lineHeight = 20.sp,
    )
}
