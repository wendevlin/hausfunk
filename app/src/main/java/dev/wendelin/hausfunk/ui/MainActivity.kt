package dev.wendelin.hausfunk.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.ui.theme.HausfunkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Otherwise the system draws a white or black scrim behind 3-button navigation. Every
        // screen paints its own background down to the bottom edge, so none is needed.
        window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        val app = application as HausfunkApplication
        setContent {
            HausfunkTheme {
                HausfunkNavHost(app = app)
            }
        }
    }
}
