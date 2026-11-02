package vn.onplay.vdubbing.presentation.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language

internal class VoiceInput(
    private val context: Context,
    private val onTranscript: (String) -> Unit,
    private val onResult: (String) -> Unit,
) : RecognitionListener {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf(R.string.voice_ready)
        private set
    var transcript by mutableStateOf("")
        private set
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun start(language: Language) {
        if (busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            message = R.string.voice_unavailable
            return
        }
        try {
            val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
                recognizer = it
            }
            transcript = ""
            active = true
            busy = true
            message = R.string.voice_starting
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (language.isTranslatable) putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguageTag(language))
            })
        } catch (_: SecurityException) {
            fail(R.string.voice_permission_required)
        } catch (_: RuntimeException) {
            fail(R.string.voice_unavailable)
        }
    }

    fun permissionDenied() = fail(R.string.voice_permission_required)

    fun cancel() {
        active = false
        busy = false
        recognizer?.cancel()
        message = R.string.voice_ready
    }

    fun destroy() {
        active = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun fail(error: Int) {
        active = false
        busy = false
        transcript = ""
        message = error
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (active) message = R.string.voice_listening
    }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        if (active) message = R.string.voice_processing
    }
    override fun onError(error: Int) {
        if (active) fail(voiceErrorMessage(error))
    }
    override fun onResults(results: Bundle?) {
        if (!active) return
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }
        if (text == null) {
            fail(R.string.voice_no_match)
        } else {
            active = false
            busy = false
            onResult(text)
        }
    }
    override fun onPartialResults(partialResults: Bundle?) {
        if (!active) return
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() } ?: return
        transcript = text
        onTranscript(text)
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

internal fun voiceErrorMessage(error: Int): Int = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_required
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_no_match
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_network_error
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_language_unavailable
    else -> R.string.voice_failed
}

internal fun speechLanguageTag(language: Language): String = when (language) {
    Language.VIETNAMESE -> "vi-VN"
    Language.ENGLISH -> "en-US"
    Language.JAPANESE -> "ja-JP"
    Language.KOREAN -> "ko-KR"
    Language.CHINESE -> "zh-CN"
    else -> java.util.Locale.getDefault().toLanguageTag()
}
