package dev.baseline.ai

import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoStatus
import kotlinx.coroutines.flow.first

/**
 * Checks Nano, then settles the [AiMode] (spec: AI assistant §3.1): an undecided mode gets its default, and a mode that
 * needs Nano falls back when Nano isn't there. Runs on every app start. If the check itself failed (AICore hiccup), nothing is
 * changed: only a real "unavailable" answer may change the user's choice, and an undecided mode is decided on the next start.
 */
suspend fun applyAiDefaults(nano: NanoEngine, prefs: AiPrefs) {
    nano.refresh()
    if (nano.lastCheckFailed.value) return
    val supported = nano.status.value !is NanoStatus.Unavailable
    val current = prefs.mode.first()
    when {
        current == null -> prefs.setMode(AiModes.initial(supported))
        !supported -> AiModes.afterNanoUnavailable(current).takeIf { it != current }?.let { prefs.setMode(it) }
    }
}
