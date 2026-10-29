package dev.baseline.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.SystemZoneClock
import dev.baseline.garmin.KeyStatus
import dev.baseline.prefs.BanisterSet
import dev.baseline.prefs.SyncInterval
import dev.baseline.ui.components.KeyBanner
import dev.baseline.ui.components.UpdateProgress
import dev.baseline.ui.components.updateButton
import dev.baseline.ui.components.updateHeadline
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.ChipActive
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.Surface2
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.update.UpdateState
import dev.baseline.update.UpdateUi
import dev.baseline.update.Updater
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val KEY_URL = "https://connect.garmin.com/app/settings/personalApiToken"
private val Lifetimes = listOf(30, 90, 180, 365)
private val ExpiryFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val SinceFormat = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

/** What the key card prints for a [KeyStatus]. [fill] is 0..1 of the key's lifetime still left. */
internal data class KeyCardUi(val status: String, val color: Color, val expiry: String, val detail: String, val fill: Float)

internal fun keyCard(status: KeyStatus?): KeyCardUi = when (status) {
    is KeyStatus.Ok -> KeyCardUi(
        "Connected",
        RecoveryGreen,
        status.expires.format(ExpiryFormat),
        "${days(status.daysLeft)} left · Garmin emails you a week before",
        fillFor(status.daysLeft),
    )

    is KeyStatus.ExpiringSoon -> KeyCardUi(
        "Expires soon",
        RecoveryAmber,
        status.expires.format(ExpiryFormat),
        "${days(status.daysLeft)} left",
        fillFor(status.daysLeft),
    )

    is KeyStatus.Expired -> KeyCardUi("Expired", RecoveryRed, status.expires.format(ExpiryFormat), "Sync paused. Cached data is still available.", 0f)

    KeyStatus.Invalid -> KeyCardUi("Key rejected", RecoveryRed, "—", "Garmin didn't accept the saved key. Cached data is still available.", 0f)

    KeyStatus.Missing, null -> KeyCardUi("No key", TextMuted, "—", "Paste a key below to start syncing.", 0f)
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

/** Garmin only offers four lifetimes and the app doesn't store which was picked, so the bar is against the smallest one that fits. */
private fun fillFor(daysLeft: Int): Float {
    val lifetime = Lifetimes.firstOrNull { it >= daysLeft } ?: Lifetimes.last()
    return (daysLeft.toFloat() / lifetime).coerceIn(0f, 1f)
}

internal fun coverageText(c: HistoryCoverage): String {
    val activities = c.activitiesSince?.let { "Activities since ${it.format(SinceFormat)}" } ?: "No activities yet"
    val health = c.healthSince?.let { "Health since ${it.format(SinceFormat)}" } ?: "No health data yet"
    return "$activities · $health"
}

private fun intervalLabel(i: SyncInterval) = when (i) {
    SyncInterval.HOURLY -> "Every hour"
    SyncInterval.THREE_HOURS -> "Every 3 hours"
    SyncInterval.MANUAL -> "Manual only"
}

private fun banisterLabel(b: BanisterSet) = when (b) {
    BanisterSet.MALE_FITTED -> "Male-fitted"
    BanisterSet.FEMALE_FITTED -> "Female-fitted"
}

@Composable
fun SettingsScreen(vm: SettingsViewModel, zone: StateFlow<ZoneId>, updater: Updater, clock: Clock = SystemZoneClock) {
    val state by vm.state.collectAsStateWithLifecycle()
    val entry by vm.entry.collectAsStateWithLifecycle()
    val zoneNow by zone.collectAsStateWithLifecycle()
    val update by updater.ui.collectAsStateWithLifecycle()
    SettingsContent(
        state, entry, clock, zoneNow, vm::replaceKey, vm::clearEntry, vm::setInterval, vm::syncNow, vm::setBanister, vm::recalculateHistory, vm::retryFailed,
        update, updater::primary, updater::checkNow, updater::setAutoCheck,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    entry: KeyEntryState,
    clock: Clock,
    zone: ZoneId,
    onReplaceKey: (String, Int) -> Unit,
    onEditKey: () -> Unit,
    onInterval: (SyncInterval) -> Unit,
    onSyncNow: () -> Unit,
    onBanister: (BanisterSet) -> Unit,
    onRecalculate: () -> Unit,
    onRetryFailed: () -> Unit = {},
    update: UpdateUi? = null,
    onUpdatePrimary: () -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    onAutoCheck: (Boolean) -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().testTag("screen-settings").verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "SETTINGS",
            Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 0.dp),
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            letterSpacing = 1.12.sp,
        )
        KeyBanner(state.keyStatus, Modifier.padding(horizontal = 20.dp))
        KeyCard(state.keyStatus, entry, onReplaceKey, onEditKey)
        SyncCard(state, clock, zone, onInterval, onSyncNow, onRetryFailed)
        ScoresCard(state, onBanister, onRecalculate)
        update?.let { AppCard(it, clock, onUpdatePrimary, onCheckUpdates, onAutoCheck) }
        Text(
            "Data from Garmin Connect Personal API. Not affiliated with Garmin.",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            fontSize = 12.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 1.28.sp, color = TextMuted)
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) {
        Text(label, Modifier.padding(end = 12.dp), fontSize = 14.sp, color = TextBody)
        Text(value, fontSize = 14.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun KeyCard(status: KeyStatus?, entry: KeyEntryState, onReplace: (String, Int) -> Unit, onEdit: () -> Unit) {
    val ui = keyCard(status)
    var key by rememberSaveable { mutableStateOf("") }
    var lifetime by rememberSaveable { mutableIntStateOf(365) }
    val testing = entry == KeyEntryState.Testing
    val uri = LocalUriHandler.current
    // The saved key is no longer needed on screen once it works.
    LaunchedEffect(entry) { if (entry == KeyEntryState.Saved) key = "" }
    SettingsCard("GARMIN PERSONAL API") {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Key status", fontSize = 14.sp, color = TextBody)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ui.color))
                Text(ui.status, Modifier.testTag("key-status"), fontSize = 13.sp, color = ui.color)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow("Key expires", ui.expiry)
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Surface2)
                    .clearAndSetSemantics { contentDescription = ui.detail },
            ) {
                Box(Modifier.fillMaxWidth(ui.fill).height(6.dp).clip(RoundedCornerShape(3.dp)).background(ui.color))
            }
            Text(ui.detail, fontSize = 12.sp, color = TextMuted)
        }
        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                onEdit()
            },
            label = { Text("Replace API key") },
            placeholder = { Text("Paste key from Garmin Connect") },
            singleLine = true,
            enabled = !testing,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("How long does the new key last?", fontSize = 13.sp, color = TextBody)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Lifetimes.forEach { n ->
                FilterChip(
                    selected = lifetime == n,
                    onClick = { lifetime = n },
                    enabled = !testing,
                    label = { Text("$n days") },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = chipColors(),
                    border = chipBorder(lifetime == n, !testing),
                )
            }
        }
        Button(
            onClick = { onReplace(key, lifetime) },
            enabled = !testing && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F4F7), contentColor = Color(0xFF0E1013)),
        ) {
            if (testing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Text("Testing…")
                }
            } else {
                Text("Save and test connection")
            }
        }
        when (entry) {
            is KeyEntryState.Error -> Text(entry.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = RecoveryRed, fontSize = 14.sp)

            KeyEntryState.Saved -> Text(
                "Key saved. Syncing now.",
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                color = RecoveryGreen,
                fontSize = 14.sp,
            )

            else -> {}
        }
        TextButton(onClick = { uri.openUri(KEY_URL) }, Modifier.heightIn(min = 48.dp)) {
            Text("Create a key in Garmin Connect ↗", color = Color(0xFF93C5FD))
        }
        Text(
            "Stored encrypted on this phone (Android Keystore). Never leaves the device except to Garmin.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = TextMuted,
        )
    }
}

/** Selected chip = the Trends range chip's fill with the accent text, so it never reads as bare green text. */
@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = ChipActive,
    selectedLabelColor = RecoveryGreen,
    labelColor = TextBody,
    disabledSelectedContainerColor = ChipActive,
    disabledLabelColor = TextMuted,
)

@Composable
private fun chipBorder(selected: Boolean, enabled: Boolean) =
    FilterChipDefaults.filterChipBorder(enabled, selected, borderColor = Outline, selectedBorderColor = Outline)

@Composable
private fun SyncCard(state: SettingsUiState, clock: Clock, zone: ZoneId, onInterval: (SyncInterval) -> Unit, onSyncNow: () -> Unit, onRetryFailed: () -> Unit) {
    SettingsCard("SYNC") {
        InfoRow("Last sync", state.lastSynced?.let { Formatters.lastSync(it, LocalDate.now(clock.withZone(zone)), zone) } ?: "Not yet")
        Text("Background sync", fontSize = 14.sp, color = TextBody)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncInterval.entries.forEach { i ->
                FilterChip(
                    selected = state.interval == i,
                    onClick = { onInterval(i) },
                    label = { Text(intervalLabel(i)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = chipColors(),
                    border = chipBorder(state.interval == i, true),
                )
            }
        }
        InfoRow("History on phone", coverageText(state.coverage))
        OutlinedButton(
            onClick = onSyncNow,
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
        ) { Text("Sync now", fontWeight = FontWeight.SemiBold) }
        if (state.failedCount > 0) {
            InfoRow("Sync problems", if (state.failedCount == 1) "1 item couldn't sync" else "${state.failedCount} items couldn't sync")
            OutlinedButton(
                onClick = onRetryFailed,
                enabled = !state.retrying,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Retry failed sync items" },
                border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
            ) { Text("Retry failed", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun ScoresCard(state: SettingsUiState, onBanister: (BanisterSet) -> Unit, onRecalculate: () -> Unit) {
    SettingsCard("SCORES & UNITS") {
        InfoRow("Recovery baseline window", "30 days")
        InfoRow("Heart rate zones", "From Garmin")
        Text("Strain coefficients", fontSize = 14.sp, color = TextBody)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BanisterSet.entries.forEach { b ->
                FilterChip(
                    selected = state.banister == b,
                    onClick = { onBanister(b) },
                    enabled = !state.recalculating,
                    label = { Text(banisterLabel(b)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = chipColors(),
                    border = chipBorder(state.banister == b, !state.recalculating),
                )
            }
        }
        Text(
            "Banister's training-impulse weights. Changing the set re-scores your whole history.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = TextMuted,
        )
        OutlinedButton(
            onClick = onRecalculate,
            enabled = !state.recalculating,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
        ) {
            if (state.recalculating) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Text("Recalculating…", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("Recalculate history", fontWeight = FontWeight.SemiBold)
            }
        }
        InfoRow("Units", if (state.imperial) "Imperial (from your Garmin profile)" else "Metric")
    }
}

/** How many lines of release notes the card shows before "Release notes ↗". */
private const val NOTES_LINES = 6

/** Version, the update on offer (spec: in-app updates §3), "Check now" and the daily-check switch. */
@Composable
private fun AppCard(ui: UpdateUi, clock: Clock, onPrimary: () -> Unit, onCheckNow: () -> Unit, onAutoCheck: (Boolean) -> Unit) {
    val uri = LocalUriHandler.current
    SettingsCard("APP") {
        InfoRow("Version", ui.installed)
        if (!ui.enabled) {
            Text("Debug and test builds don't check for updates.", fontSize = 12.sp, color = TextMuted)
            return@SettingsCard
        }
        when (val state = ui.state) {
            UpdateState.UpToDate -> InfoRow(
                "Updates",
                ui.checkedAt?.let { "Up to date · checked ${Formatters.ago(it, clock.instant())}" } ?: "Not checked yet",
                Modifier.testTag("update-status"),
            )

            is UpdateState.Offer -> {
                Text(
                    updateHeadline(state),
                    Modifier.testTag("update-status").semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 14.sp,
                    color = if (state is UpdateState.Failed) RecoveryAmber else TextBody,
                )
                if (state is UpdateState.Downloading) UpdateProgress(state)
                if (state.release.notes.isNotBlank()) {
                    Text(state.release.notes.lines().take(NOTES_LINES).joinToString("\n"), fontSize = 12.sp, lineHeight = 18.sp, color = TextMuted)
                }
                TextButton(onClick = { uri.openUri(state.release.pageUrl) }, Modifier.heightIn(min = 48.dp)) {
                    Text("Release notes ↗", color = Color(0xFF93C5FD))
                }
                updateButton(state)?.let { label ->
                    Button(
                        onClick = onPrimary,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F4F7), contentColor = Color(0xFF0E1013)),
                    ) { Text(label, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        ui.checkError?.let { Text("Couldn't check for updates: $it", fontSize = 12.sp, color = TextMuted) }
        OutlinedButton(
            onClick = onCheckNow,
            enabled = !ui.checking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
        ) { Text(if (ui.checking) "Checking…" else "Check now", fontWeight = FontWeight.SemiBold) }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(ui.autoCheck, role = Role.Switch, onValueChange = onAutoCheck),
            Arrangement.SpaceBetween,
            Alignment.CenterVertically,
        ) {
            Text("Check for updates daily", Modifier.weight(1f), fontSize = 14.sp, color = TextBody)
            Switch(checked = ui.autoCheck, onCheckedChange = null)
        }
        Text(
            "Asks GitHub for the latest Baseline release. GitHub sees your IP address, as any website would; none of your data is sent.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = TextMuted,
        )
    }
}
