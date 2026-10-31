package dev.baseline.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Bg
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.StrainBlue
import dev.baseline.ui.theme.StrainBlueLight
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextPrimary
import dev.baseline.update.ReleaseNotes

/** The colour that marks each house section (docs/release-notes.md); anything else gets the neutral blue. */
private fun sectionColor(title: String): Color = when (title) {
    "New" -> RecoveryGreen
    "Fixed" -> RecoveryAmber
    else -> StrainBlue
}

/** Shown once, on the first launch after an in-app update: the release's summary and its New / Improved / Fixed bullets. */
@Composable
fun WhatsNewDialog(version: String, notes: ReleaseNotes, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(shape).background(Card).border(BorderStroke(1.dp, Outline), shape).padding(20.dp),
        ) {
            Text("WHAT'S NEW", color = StrainBlueLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Text(
                "Baseline $version",
                Modifier.padding(top = 2.dp).semantics { heading() },
                color = TextPrimary,
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp), Arrangement.spacedBy(16.dp)) {
                if (notes.summary.isNotBlank()) Text(notes.summary, color = TextBody, fontSize = 15.sp, lineHeight = 22.sp)
                notes.sections.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.size(8.dp).clip(CircleShape).background(sectionColor(section.title)).clearAndSetSemantics { }) {}
                            Text(
                                section.title.uppercase(),
                                Modifier.semantics { heading() },
                                color = sectionColor(section.title),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp,
                            )
                        }
                        section.items.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("•", color = TextBody, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.clearAndSetSemantics { })
                                Text(item, Modifier.weight(1f), color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = Bg),
            ) { Text("Got it", fontWeight = FontWeight.SemiBold) }
        }
    }
}
