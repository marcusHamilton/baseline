package dev.baseline.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.ui.settings.LinkColor
import dev.baseline.ui.settings.chipBorder
import dev.baseline.ui.settings.chipColors
import dev.baseline.ui.settings.primaryButtonColors
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.TextBody

private const val KEY_URL = "https://connect.garmin.com/app/settings/personalApiToken"
private val Lifetimes = listOf(30, 90, 180, 365)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(vm: OnboardingViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableIntStateOf(365) }
    val uri = LocalUriHandler.current
    val testing = state == OnboardingState.Testing

    Column(
        // imePadding sits above the scroll so the viewport shrinks and the key field and Save button scroll clear of the keyboard.
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Connect Garmin",
            Modifier.semantics { heading() },
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            letterSpacing = 1.12.sp,
        )
        Text(
            "Baseline reads your data straight from Garmin with a personal API key. It stays on this phone, encrypted.",
            fontSize = 14.sp,
            color = TextBody,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Personal API key") },
            singleLine = true,
            enabled = !testing,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("How long does your key last?", fontSize = 13.sp, color = TextBody)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Lifetimes.forEach { n ->
                FilterChip(
                    selected = days == n,
                    onClick = { days = n },
                    enabled = !testing,
                    label = { Text("$n days") },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = chipColors(),
                    border = chipBorder(days == n, !testing),
                )
            }
        }
        TextButton(onClick = { uri.openUri(KEY_URL) }, Modifier.heightIn(min = 48.dp)) {
            Text("Create a key in Garmin Connect", color = LinkColor)
        }
        Button(
            onClick = { vm.submit(key, days) },
            enabled = !testing && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = primaryButtonColors(),
        ) {
            if (testing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Text("Testing…")
                }
            } else {
                Text("Save and test")
            }
        }
        (state as? OnboardingState.Error)?.let {
            Text(
                it.message,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = RecoveryRed,
                fontSize = 14.sp,
            )
        }
    }
}
