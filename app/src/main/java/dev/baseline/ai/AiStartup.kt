package dev.baseline.ai

import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoStatus
import kotlinx.coroutines.flow.first

/**
 * Checks Nano, then settles the [AiMode] (spec: AI assistant §3.1): an undecided mode gets its default, and a mode that
 * needs Nano falls back when Nano isn't there. Runs on every app start.
 */
suspend fun applyAiDefaults(nano: NanoEngine, prefs: AiPrefs) {
    nano.refresh()
    val supported = nano.status.value !is NanoStatus.Unavailable
    val current = prefs.mode.first()
    when {
        current == null -> prefs.setMode(AiModes.initial(supported))
        !supported -> AiModes.afterNanoUnavailable(current).takeIf { it != current }?.let { prefs.setMode(it) }
    }
}
