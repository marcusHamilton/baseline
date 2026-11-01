package dev.baseline.ai

import dev.baseline.ai.engine.NanoEngine
import dev.baseline.ai.engine.NanoStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The one-time prompt on Today for a phone whose Gemini Nano is still to download (spec: AI assistant §3.1). Shown only in
 * On-device only with Nano [NanoStatus.Downloadable]; [applyAiDefaults] marks it done in every other state, so a phone that
 * doesn't support Nano never sees it. The download runs on the app scope so it survives leaving Today;
 * the prompt counts as answered once it finishes, so a failed or interrupted download is offered again.
 */
class AiFirstRun(private val nano: NanoEngine, private val prefs: AiPrefs, private val scope: CoroutineScope) {
    val show: Flow<Boolean> =
        combine(prefs.promptDone, prefs.mode, nano.status) { done, mode, status ->
            !done && mode == AiMode.ON_DEVICE && status is NanoStatus.Downloadable
        }.distinctUntilChanged()

    fun download() {
        scope.launch {
            try {
                nano.download().collect { }
                prefs.setPromptDone()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed download leaves the prompt pending: the status is back to Downloadable, so it asks again.
            }
        }
    }

    fun turnOff() {
        scope.launch {
            prefs.setMode(AiMode.OFF)
            prefs.setPromptDone()
        }
    }
}
