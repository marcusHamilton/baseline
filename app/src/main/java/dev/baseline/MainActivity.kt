package dev.baseline

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.baseline.ui.nav.BaselineNavHost
import dev.baseline.ui.theme.BaselineTheme
import dev.baseline.widget.WidgetRoutes
import dev.baseline.widget.WidgetTarget
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val deepLink = MutableStateFlow<WidgetTarget?>(null)

    override fun onResume() {
        super.onResume()
        (application as BaselineApp).container.dateTicker.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            deepLink.value = WidgetRoutes.parse(intent)
        }
        setContent {
            BaselineTheme {
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
