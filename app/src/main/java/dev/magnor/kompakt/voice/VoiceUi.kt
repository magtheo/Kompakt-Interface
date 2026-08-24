package dev.magnor.kompakt.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.data.remote.HttpApi
import dev.magnor.kompakt.ui.LocalAppContainer
import java.io.File

/**
 * Composer wiring for Phase 11 voice input (T-021, dev plan §13):
 * mic appears inside a text field's trailing slot, tap-to-record /
 * tap-again-to-stop, transcript lands as plain editable text — inspect
 * and correct before submitting. Hidden entirely when the app is not
 * enrolled against a real server (fake mode has no /v1).
 */
@Composable
fun rememberVoiceInput(onTranscript: (String) -> Unit): VoiceInputController? {
    val api: HttpApi? = LocalAppContainer.current.remoteApi()
    if (api == null) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnTranscript by rememberUpdatedState(onTranscript)
    val controller = remember(api, context, scope) {
        VoiceInputController(
            scope = scope,
            clipFile = File(context.cacheDir, "voice_clip.mp4"),
            recorderFactory = ::MediaRecorderVoiceRecorder,
            transcriber = HttpVoiceTranscriber(api),
            onTranscript = { latestOnTranscript(it) },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    return controller
}

/**
 * The mic button. Handles the RECORD_AUDIO runtime permission itself:
 * first tap prompts, grant flows straight into recording. Icon and tint
 * encode the controller state (mic = record, square = stop, red =
 * last attempt failed — retry on tap).
 */
@Composable
fun MicButton(controller: VoiceInputController?, modifier: Modifier = Modifier) {
    if (controller == null) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) controller.onMicTap() else controller.permissionDenied()
    }
    when (val state = controller.state) {
        VoiceInputState.Uploading -> CircularProgressIndicator(
            modifier = modifier.size(28.dp).padding(4.dp),
            strokeWidth = 2.dp,
        )
        else -> IconButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) controller.onMicTap()
                else launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = modifier,
        ) {
            when (state) {
                is VoiceInputState.Recording -> Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop recording",
                    tint = MaterialTheme.colorScheme.error,
                )
                is VoiceInputState.Failed -> Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Retry voice input",
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Icon(Icons.Filled.Mic, contentDescription = "Record voice input")
            }
        }
    }
}

/** Inline failure feedback under a composer; renders nothing when healthy. */
@Composable
fun VoiceStatusText(controller: VoiceInputController?) {
    val failure = controller?.state as? VoiceInputState.Failed ?: return
    TextMMD(
        failure.reason,
        modifier = Modifier.padding(top = 4.dp),
    )
}
