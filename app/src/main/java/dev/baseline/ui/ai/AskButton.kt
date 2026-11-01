package dev.baseline.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.baseline.R
import dev.baseline.ui.theme.Bg
import dev.baseline.ui.theme.SleepPurple

private val CircleSize = 36.dp
private val GlyphSize = 16.dp

/**
 * The centre slot of the tab bar (spec §3.4): a [SleepPurple] circle with a dark sparkle. It is an action, not a tab, so it is never
 * selected, has no label, and [onClick] opens the Ask sheet rather than navigating. The item keeps `NavigationBarItem`'s full-height
 * touch area (well over 48dp), and its press indicator is off so the circle alone is the visual.
 */
@Composable
fun RowScope.AskButton(onClick: () -> Unit) {
    NavigationBarItem(
        selected = false,
        onClick = onClick,
        icon = {
            Box(Modifier.size(CircleSize).background(SleepPurple, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, Modifier.size(GlyphSize), tint = Bg)
            }
        },
        modifier = Modifier.semantics {
            contentDescription = "Ask about this page"
            role = Role.Button
        },
        alwaysShowLabel = false,
        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
    )
}
