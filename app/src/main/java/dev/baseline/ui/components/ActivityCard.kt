package dev.baseline.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.R
import dev.baseline.data.SportFamily
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.StrainBlueLight
import dev.baseline.ui.theme.TextMuted
import java.util.Locale

/** [meta] is the second line, e.g. "7:05 AM · 10.21 km · 52:14". [strain] is null when the activity has no score. */
data class ActivityCardUi(
    val id: Long,
    val name: String,
    val family: SportFamily,
    val typeKey: String,
    val meta: String,
    val strain: Double?,
)

@DrawableRes
fun sportIcon(family: SportFamily): Int = when (family) {
    SportFamily.RUN -> R.drawable.ic_sport_run
    SportFamily.RIDE -> R.drawable.ic_sport_ride
    SportFamily.SWIM -> R.drawable.ic_sport_swim
    SportFamily.STRENGTH -> R.drawable.ic_sport_strength
    SportFamily.OTHER -> R.drawable.ic_sport_other
}

@Composable
fun ActivityCard(ui: ActivityCardUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val strain = ui.strain?.takeIf { it.isFinite() }?.let { "%.1f".format(Locale.US, it) } ?: "—"
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).background(Card)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E2A3A)), Alignment.Center) {
            Icon(painterResource(sportIcon(ui.family)), contentDescription = null, Modifier.size(22.dp), tint = StrainBlueLight)
        }
        Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
            Text(ui.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.meta.isNotEmpty()) Text(ui.meta, fontSize = 13.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(strain, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = StrainBlueLight)
            Text("strain", fontSize = 11.sp, color = TextMuted)
        }
    }
}
