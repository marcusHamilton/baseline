package dev.baseline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.TextPrimary

/** A 48 dp round icon button on a card-coloured disc (the Today day arrows, the back and month buttons). */
@Composable
fun CircleButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(Card).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, Modifier.size(20.dp), tint = if (enabled) TextPrimary else Color(0xFF5B636D))
    }
}
