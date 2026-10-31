package dev.baseline

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.ui.components.WhatsNewDialog
import dev.baseline.ui.nav.BaselineNavHost
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.update.parseNotes
import dev.baseline.widget.WidgetRoutes
import dev.baseline.widget.WidgetTarget
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val deepLink = MutableStateFlow<WidgetTarget?>(null)

    override fun onResume() {
        super.onResume()
        (application as BaselineApp).container.dateTicker.refresh()
        (application as BaselineApp).container.updater.onForeground(true)
    }

    override fun onPause() {
        (application as BaselineApp).container.updater.onForeground(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Same edge-to-edge dark system bars on API 29-34 as the platform enforces on 35+. dark(), not auto(): the app is always dark,
        // so the status and navigation icons must stay light even when the phone is set to a light theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A relaunch from Recents re-delivers the original widget intent; it must not navigate again.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            deepLink.value = WidgetRoutes.parse(intent)
        }
        setContent {
            BaselineTheme {
                // The system's "Update this app?" screen, when PackageInstaller asks for it (spec: in-app updates §6).
                val installPrompt by (application as BaselineApp).container.updater.userAction.collectAsStateWithLifecycle()
                LaunchedEffect(installPrompt) {
                    installPrompt?.let {
                        (application as BaselineApp).container.updater.userActionLaunched()
                        // A missing installer screen must not crash the app; the update stays INSTALLING and a restart offers Retry.
                        runCatching { startActivity(it) }.onFailure { e -> Log.w("MainActivity", "install prompt: ${e.javaClass.simpleName}") }
                    }
                }
                // Once, on the first launch after an in-app update (Updater.onAppStart keeps the notes for it).
                val updater = (application as BaselineApp).container.updater
                val updateUi by updater.ui.collectAsStateWithLifecycle()
                updateUi.whatsNew?.let { WhatsNewDialog(it.version, parseNotes(it.markdown), updater::dismissWhatsNew) }
                BaselineNavHost(
                    container = (application as BaselineApp).container,
                    deepLink = deepLink,
                    onDeepLinkConsumed = { deepLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WidgetRoutes.parse(intent)?.let { deepLink.value = it }
    }
}
