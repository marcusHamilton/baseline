package dev.baseline.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.ai.AiKeyEntry
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiKeyVault
import dev.baseline.ai.AiMode
import dev.baseline.ai.AiModes
import dev.baseline.ai.AiPrefs
import dev.baseline.ai.ModeOption
import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoStatus
import dev.baseline.ai.insight.InsightService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the AI card shows (spec: AI assistant §3.2). [mode] is null until it has been decided. [nanoKnown] is false until the first Nano status check
 * has finished, so the card doesn't call a phone unsupported before it has asked.
 */
data class AiSettingsState(
    val mode: AiMode?,
    val options: List<ModeOption>,
    val nano: NanoStatus,
    val nanoKnown: Boolean,
    val key: AiKeyStatus,
    /** "Add an API key for better answers…": On-device only with Nano ready and no key yet, which is when a key would add something. */
    val showHint: Boolean,
)

internal fun buildAiSettingsState(mode: AiMode?, nano: NanoStatus, nanoKnown: Boolean, key: AiKeyStatus): AiSettingsState = AiSettingsState(
    mode = mode,
    options = AiModes.options(nanoSupported = nano !is NanoStatus.Unavailable, keySet = key is AiKeyStatus.Set),
    nano = nano,
    nanoKnown = nanoKnown,
    key = key,
    showHint = mode == AiMode.ON_DEVICE && nano is NanoStatus.Ready && key is AiKeyStatus.Missing,
)

/** The Settings AI card (spec: AI assistant §3.2). Adding a key and choosing a model is [AiKeyViewModel]. */
class AiSettingsViewModel(
    private val prefs: AiPrefs,
    vault: AiKeyVault,
    private val nano: NanoEngine,
    private val entry: AiKeyEntry,
    private val insights: InsightService,
) : ViewModel() {
    val state: StateFlow<AiSettingsState> =
        combine(prefs.mode, nano.status, nano.known, vault.status, ::buildAiSettingsState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                buildAiSettingsState(null, nano.status.value, nano.known.value, AiKeyStatus.Missing),
            )

    fun setMode(mode: AiMode) {
        viewModelScope.launch { prefs.setMode(mode) }
    }

    /** Runs the download while the screen is open; the engine's status carries the progress and the outcome, so errors need no handling here. */
    fun downloadNano() {
        viewModelScope.launch {
            try {
                nano.download().collect { }
            } catch (e: CancellationException) {
                // Leaving Settings stops the download; without a fresh check the status would stay on "Downloading".
                withContext(NonCancellable) { nano.refresh() }
                throw e
            } catch (_: Exception) {
                // NanoEngine.download() has already re-checked the status for a backend failure.
            }
        }
    }

    fun removeKey() {
        viewModelScope.launch { entry.remove() }
    }

    fun clearInsights() {
        viewModelScope.launch { insights.clearAll() }
    }
}
