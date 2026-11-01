package dev.baseline.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baseline.R
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiMode
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.garmin.KeyStatus
import dev.baseline.ui.components.CircleButton
import dev.baseline.ui.components.KeyBanner
import dev.baseline.ui.components.updateHeadline
import dev.baseline.ui.format.Formatters
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.Card
import dev.baseline.ui.theme.Outline
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary
import dev.baseline.update.UpdateState
import dev.baseline.update.UpdateUi
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** The sub-pages of Settings, one route each. Not a `settings/{page}` route: that would also match `settings/ai-key`. */
enum class SettingsPage(val route: String, val title: String) {
    GARMIN("settings/garmin", "GARMIN CONNECTION"),
    SYNC("settings/sync", "SYNC"),
    SCORES("settings/scores", "SCORES & UNITS"),
    AI("settings/ai", "AI ASSISTANT"),
    UPDATES("settings/updates", "UPDATES"),
    PRIVACY("settings/privacy", "PRIVACY & DATA"),
}

/** The one-line state of the Garmin key shown on its row. */
internal fun garminSummary(status: KeyStatus?): String {
    val ui = keyCard(status)
    return when (status) {
        is KeyStatus.Ok, is KeyStatus.ExpiringSoon -> "${ui.status} · until ${ui.expiry}"
        is KeyStatus.Expired -> "Expired ${ui.expiry}"
        KeyStatus.Invalid -> "Key rejected"
        KeyStatus.Missing, null -> "No key"
    }
}

/** The AI row's summary: the mode, then what backs it. A rejected key replaces the backing, so the row says what needs fixing. */
internal fun aiSummary(state: AiSettingsState): String {
    val mode = state.mode ?: return "…"
    if (mode == AiMode.OFF) return "Off"
    val key = state.key as? AiKeyStatus.Set
    if (aiKeyRejected(state)) return "${modeLabel(mode)} · Key rejected"
    return listOfNotNull(
        modeLabel(mode),
        "Nano ready".takeIf { state.nano is NanoStatus.Ready },
        key?.let { shortModel(it.model) },
    ).joinToString(" · ")
}

/** A rejected key only matters while the mode can use it. */
private fun aiKeyRejected(state: AiSettingsState) = state.mode != null && state.mode != AiMode.OFF && (state.key as? AiKeyStatus.Set)?.invalid == true

private fun updateSummary(ui: UpdateUi): Pair<String, Color> = when {
    !ui.enabled -> "${ui.installed} · update checks off" to TextMuted
    ui.state is UpdateState.Offer -> updateHeadline(ui.state) to if (ui.state is UpdateState.Failed) RecoveryAmber else LinkColor
    else -> "${ui.installed} · Up to date" to TextMuted
}

/** The Settings tab's list (stateless): the key banner, then one row per [SettingsPage] with its current state as the summary. */
@Composable
internal fun SettingsHome(
    state: SettingsUiState,
    clock: Clock,
    zone: ZoneId,
    update: UpdateUi?,
    ai: AiSettingsState?,
    onOpen: (SettingsPage) -> Unit,
    onSyncNow: () -> Unit,
) {
    val key = keyCard(state.keyStatus)
    val lastSync = state.lastSynced?.let { "last ${Formatters.lastSync(it, LocalDate.now(clock.withZone(zone)), zone)}" } ?: "not synced yet"
    Column(
        Modifier.fillMaxSize().testTag("screen-settings").verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "SETTINGS",
            Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 0.dp).semantics { heading() },
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            letterSpacing = 1.12.sp,
        )
        KeyBanner(state.keyStatus, Modifier.padding(horizontal = 20.dp))
        Group("DATA") {
            SettingsRow(SettingsPage.GARMIN, "Garmin connection", garminSummary(state.keyStatus), key.color, onOpen, dot = key.color)
            RowDivider()
            val failed = state.failedCount
            val sync = when {
                failed == 1 -> "1 item couldn't sync · $lastSync" to RecoveryAmber
                failed > 1 -> "$failed items couldn't sync · $lastSync" to RecoveryAmber
                else -> "${intervalLabel(state.interval)} · $lastSync" to TextMuted
            }
            SettingsRow(SettingsPage.SYNC, "Sync", sync.first, sync.second, onOpen) {
                OutlinedButton(
                    onClick = onSyncNow,
                    Modifier.padding(end = 4.dp).heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, Outline),
                ) { Text("Sync now", fontWeight = FontWeight.SemiBold) }
            }
            RowDivider()
            SettingsRow(
                SettingsPage.SCORES,
                "Scores & units",
                "${banisterLabel(state.banister)} strain · ${if (state.imperial) "Imperial" else "Metric"}",
                TextMuted,
                onOpen,
            )
        }
        Group("ASSISTANT") {
            val rejected = ai != null && aiKeyRejected(ai)
            SettingsRow(SettingsPage.AI, "AI assistant", ai?.let(::aiSummary) ?: "…", if (rejected) RecoveryRed else TextMuted, onOpen)
        }
        Group("APP") {
            if (update != null) {
                val (text, color) = updateSummary(update)
                SettingsRow(SettingsPage.UPDATES, "Updates", text, color, onOpen)
                RowDivider()
            }
            SettingsRow(SettingsPage.PRIVACY, "Privacy & data", "What leaves this phone", TextMuted, onOpen)
        }
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
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.padding(horizontal = 24.dp).semantics { heading() },
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            letterSpacing = 1.28.sp,
            color = TextMuted,
        )
        Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)) { content() }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = Outline, thickness = 1.dp)

/** A whole-row button that opens [page]; [trailing] (the Sync row's "Sync now") sits before the chevron and takes its own taps. */
@Composable
private fun SettingsRow(
    page: SettingsPage,
    title: String,
    summary: String,
    summaryColor: Color,
    onOpen: (SettingsPage) -> Unit,
    dot: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("settings-row-${page.route}")
            .clickable(role = Role.Button) { onOpen(page) }
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        Arrangement.spacedBy(8.dp),
        Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, color = TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Text(summary, fontSize = 13.sp, color = summaryColor)
            }
        }
        trailing?.invoke()
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = TextMuted)
    }
}

/** The page shell: a back button and the title above the page's cards. */
@Composable
internal fun SettingsPageContent(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        // imePadding sits above the scroll so the viewport shrinks and the key field and Save button scroll clear of the keyboard.
        Modifier.fillMaxSize().testTag("screen-settings-page").imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp),
            Arrangement.spacedBy(12.dp),
            Alignment.CenterVertically,
        ) {
            CircleButton(R.drawable.ic_chevron_left, "Back", true, onBack)
            Text(
                title,
                Modifier.semantics { heading() },
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                letterSpacing = 0.88.sp,
            )
        }
        content()
    }
}

/** What leaves the phone, in one place; read-only. The AI line follows the mode, so it never claims a cloud request that can't happen. */
@Composable
internal fun PrivacyCard(ai: AiSettingsState) {
    val key = ai.key as? AiKeyStatus.Set
    val aiLine = when (ai.mode) {
        AiMode.OFF -> "Off. Nothing is sent."
        AiMode.ON_DEVICE -> "On-device only. Answers are worked out on this phone and nothing is sent."
        AiMode.CLOUD, AiMode.HYBRID, null -> if (ai.mode != null && key != null) cloudPrivacyLine(key.host) else "Nothing is sent until you add an API key."
    }
    SettingsCard {
        listOf(
            "Your health data" to "Synced data is stored only on this phone. Baseline has no server.",
            "Garmin key" to KEYSTORE_LINE,
            "Update checks" to GITHUB_LINE,
            "AI" to aiLine,
        ).forEach { (label, text) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, fontSize = 14.sp, color = TextBody)
                Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = TextMuted)
            }
        }
    }
}
