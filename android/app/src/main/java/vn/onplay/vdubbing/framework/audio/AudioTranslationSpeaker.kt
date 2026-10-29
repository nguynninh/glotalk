package vn.onplay.vdubbing.framework.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import vn.onplay.vdubbing.domain.model.Language

internal class AudioTranslationSpeaker(context: Context) {
    private val ready = CompletableDeferred<Int>()
    private val engine = TextToSpeech(context) { ready.complete(it) }
    @Volatile private var pending: Pair<String, CompletableDeferred<Unit>>? = null

    suspend fun initialize(language: Language) {
        check(withTimeout(15000) { ready.await() } == TextToSpeech.SUCCESS)
        val locale = Locale.forLanguageTag(language.code)
        check(engine.setLanguage(locale) >= 0)
        val voice = engine.voices?.firstOrNull {
            it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
        check(voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS)
        engine.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build())
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                pending?.takeIf { it.first == utteranceId }?.second?.complete(Unit)
            }
            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) {
                pending?.takeIf { it.first == utteranceId }?.second
                    ?.completeExceptionally(IllegalStateException("TTS failed"))
            }
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
        })
    }

    suspend fun speak(text: String) {
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        pending = id to completion
        try {
            check(engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS)
            withTimeout(45000) { completion.await() }
        } finally {
            pending = null
        }
    }

    fun close() {
        pending?.second?.cancel()
        pending = null
        engine.stop()
        engine.shutdown()
    }
}
