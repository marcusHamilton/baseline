package dev.baseline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.baseline.ui.nav.BaselineNavHost
import dev.baseline.ui.theme.BaselineTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as BaselineApp).container.dateTicker.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BaselineTheme { BaselineNavHost((application as BaselineApp).container) } }
    }
}
