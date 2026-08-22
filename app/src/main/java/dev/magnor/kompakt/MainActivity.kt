package dev.magnor.kompakt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.magnor.kompakt.ui.KompaktApp
import dev.magnor.kompakt.ui.theme.KompaktTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KompaktApplication).container
        setContent {
            KompaktTheme {
                KompaktApp(container)
            }
        }
    }
}
