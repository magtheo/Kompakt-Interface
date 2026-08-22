package dev.magnor.kompakt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.ui.theme.KompaktTheme

/**
 * Phase 0 placeholder shell. Proves: Kotlin + Compose + MMD build and render.
 * Phase 1 replaces this with the real navigation structure
 * (Today / Chat / Agents / More).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KompaktTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TextMMD(text = "KOMPAKT")
                        HorizontalDividerMMD()
                        TextMMD(text = "Phase 0 — shell")
                        ButtonMMD(onClick = { /* placeholder */ }) {
                            TextMMD(text = "OK")
                        }
                    }
                }
            }
        }
    }
}
