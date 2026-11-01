package dev.baseline.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import dev.baseline.ai.context.AskScope

/**
 * The Ask scope each open page has reported to the nav host (spec §3.5), keyed by back stack entry id. A page that reports nothing,
 * or an entry that has gone, reads as [AskScope.General].
 */
class AskScopeHolder {
    private val scopes = HashMap<String, AskScope>()

    fun report(entryId: String, scope: AskScope) {
        scopes[entryId] = scope
    }

    fun remove(entryId: String) {
        scopes.remove(entryId)
    }

    fun current(entryId: String?): AskScope = entryId?.let(scopes::get) ?: AskScope.General
}

/** The nav host provides the real holder; screens drawn without one (previews, tests) report into a throwaway. */
val LocalAskScopeHolder = staticCompositionLocalOf { AskScopeHolder() }

/**
 * Called from a destination's `composable` block: tells the nav host what the Ask button should be about while this entry is on screen.
 * Reports again whenever [scope] changes (Today's shown day, Trends' range), and forgets the entry when it leaves the composition.
 */
@Composable
fun ReportAskScope(scope: AskScope) {
    val holder = LocalAskScopeHolder.current
    // NavHost provides the back stack entry as the ViewModelStoreOwner of its destination's content.
    val entryId = (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.id ?: return
    LaunchedEffect(holder, entryId, scope) { holder.report(entryId, scope) }
    DisposableEffect(holder, entryId) { onDispose { holder.remove(entryId) } }
}
