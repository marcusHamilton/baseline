package dev.baseline.ui.activity.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.ui.activity.Tile
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.StrainBlueLight
import dev.baseline.ui.theme.TextMuted

/** Three tiles to a row, the last row padded with empty cells so the tiles keep one width. */
@Composable
fun SummaryGrid(tiles: List<Tile>, modifier: Modifier = Modifier, valueSize: Int = 24) {
    Column(modifier.fillMaxWidth().testTag("summary"), Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), Arrangement.spacedBy(8.dp)) {
                row.forEach { StatTile(it, Modifier.weight(1f).fillMaxHeight(), valueSize) }
                repeat(3 - row.size) { Column(Modifier.weight(1f)) {} }
            }
        }
    }
}

/** One value over its label (and an optional muted line). TalkBack reads it as "value label". */
@Composable
fun StatTile(tile: Tile, modifier: Modifier = Modifier, valueSize: Int = 24) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(tile.value, tile.label, tile.sub).joinToString(", ") },
        Arrangement.spacedBy(2.dp),
    ) {
        Text(
            tile.value, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = valueSize.sp, lineHeight = valueSize.sp,
            color = if (tile.accent) StrainBlueLight else androidx.compose.ui.graphics.Color.Unspecified,
        )
        Text(tile.label, fontSize = 11.sp, color = TextMuted)
        tile.sub?.let { Text(it, fontSize = 11.sp, color = TextMuted) }
    }
}
