package vn.onplay.vdubbing.presentation.speech

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import java.util.Locale
import vn.onplay.vdubbing.domain.model.Language

@Composable
internal fun rememberReadAloud(stopWhenChanged: Any? = Unit, onUnavailable: () -> Unit): (String, Language) -> Unit {
    if (LocalInspectionMode.current) return { _, _ -> }
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val speech = remember(context) {
        TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS }
    }
    LaunchedEffect(stopWhenChanged) { speech.stop() }
    DisposableEffect(speech) {
        onDispose {
            speech.stop()
            speech.shutdown()
        }
    }
    return { text, language ->
        if (!ready || speech.setLanguage(Locale.forLanguageTag(language.code)) < 0 ||
            speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation") == TextToSpeech.ERROR
        ) onUnavailable()
    }
}
