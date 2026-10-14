package io.github.wendevlin.hausfunk.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import io.github.wendevlin.hausfunk.HausfunkApplication
import io.github.wendevlin.hausfunk.ui.theme.HausfunkTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as HausfunkApplication
        setContent {
            HausfunkTheme {
                HausfunkNavHost(app = app, windowSizeClass = calculateWindowSizeClass(this))
            }
        }
    }
}
