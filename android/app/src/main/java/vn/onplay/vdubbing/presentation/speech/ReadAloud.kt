package vn.onplay.vdubbing.presentation.speech

import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun rememberReadAloud(stopWhenChanged: Any? = Unit, onUnavailable: () -> Unit): (String, Language) -> Unit {
    if (LocalInspectionMode.current) return { _, _ -> }
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val unavailable by rememberUpdatedState(onUnavailable)
    var ready by remember { mutableStateOf(false) }
    val speech = remember(context) { TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS } }
    val speechViewModel: ReadAloudViewModel = hiltViewModel()
    val korva = speechViewModel.vietnamese
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val progress = remember { MutableStateFlow(0f) }
    var request by remember { mutableStateOf<Any?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val stop: () -> Unit = {
        request = null
        job?.cancel()
        preparing = false
        speech.stop()
    }
    val latestStop by rememberUpdatedState(stop)
    LaunchedEffect(stopWhenChanged) { latestStop() }
    DisposableEffect(lifecycleOwner, speech, korva) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestStop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestStop()
            speech.shutdown()
            scope.launch {
                try { korva.close() } finally { scope.cancel() }
            }
        }
    }
    if (preparing) {
        val value by progress.collectAsState()
        KorvaPreparationDialog(value, stop)
    }
    return { text, language ->
        stop()
        if (text.isNotBlank()) {
            if (language == Language.VIETNAMESE) {
                val id = Any()
                request = id
                progress.value = 0f
                preparing = true
                job = scope.launch {
                    try {
                        korva.speak(text, { progress.value = it }, { if (request === id) preparing = false })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w("KorvaTTS", "Read aloud failed", failure)
                        Toast.makeText(context, R.string.korva_failed, Toast.LENGTH_LONG).show()
                    } finally {
                        if (request === id) preparing = false
                    }
                }
            } else if (!ready || speech.setLanguage(Locale.forLanguageTag(language.code)) < 0 ||
                speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation") == TextToSpeech.ERROR
            ) unavailable()
        }
    }
}

@Composable
private fun KorvaPreparationDialog(progress: Float, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(if (progress < 1f) R.string.korva_preparing else R.string.korva_synthesizing)) },
        text = {
            Column {
                Text(stringResource(if (progress < 1f) R.string.korva_download_notice else R.string.korva_synthesis_notice))
                Spacer(Modifier.height(16.dp))
                if (progress < 1f) {
                    LinearProgressIndicator(progress = { progress })
                    Text("${(progress * 100).toInt()}%")
                } else LinearProgressIndicator()
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun KorvaPreparationLightPreview() {
    VDubbingTheme(themeMode = ThemeMode.LIGHT) { KorvaPreparationDialog(0.35f) {} }
}

@Preview(showBackground = true)
@Composable
private fun KorvaPreparationDarkPreview() {
    VDubbingTheme(themeMode = ThemeMode.DARK) { KorvaPreparationDialog(1f) {} }
}
