package dev.baseline.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.SleepPurple
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextPrimary

/** Asks once whether to download Gemini Nano or turn the AI features off; it has no dismiss, so the user always answers (spec: AI assistant §3.1). */
@Composable
fun AiFirstRunDialog(onDownload: () -> Unit, onTurnOff: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    // One answer per dialog: the dialog stays up until the choice is stored, so a second tap must not start a second one.
    var answered by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(shape).background(Card).border(BorderStroke(1.dp, Outline), shape).padding(20.dp),
            Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Turn on AI insights?",
                Modifier.semantics { heading() },
                color = TextPrimary,
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
            )
            Text(
                "Baseline can answer questions and write short insights about your data with Gemini Nano. It runs on this phone, " +
                    "so nothing leaves it. It needs a one-time download over Wi-Fi.",
                color = TextBody,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            Button({
                answered = true
                onDownload()
            }, Modifier.fillMaxWidth().padding(top = 4.dp), enabled = !answered, colors = ButtonDefaults.buttonColors(containerColor = SleepPurple)) {
                Text("Download")
            }
            OutlinedButton({
                answered = true
                onTurnOff()
            }, Modifier.fillMaxWidth(), enabled = !answered) { Text("Turn off AI", color = TextPrimary) }
        }
    }
}
