package vn.onplay.vdubbing.framework.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.data.audio.SpeechRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import vn.onplay.vdubbing.data.audio.AudioTrace
import vn.onplay.vdubbing.data.audio.traceAudio
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import vn.onplay.vdubbing.data.speech.VietnameseSpeechRepositoryImpl
import vn.onplay.vdubbing.domain.model.Language

internal class AudioTranslationSpeaker(context: Context) {
    private val ready = CompletableDeferred<Int>()
    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var korva: VietnameseSpeechRepositoryImpl? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    private var activeTrace: AudioTrace? = null
    @Volatile private var pending: Pair<String, CompletableDeferred<Unit>>? = null

    suspend fun initialize(language: Language) {
        check(!closed)
        if (language == Language.VIETNAMESE) {
            val voice = VietnameseSpeechRepositoryImpl(appContext, steps = 8).also { korva = it }
            voice.prepare()
            check(!closed)
            return
        }
        val engine = TextToSpeech(appContext) { ready.complete(it) }.also { engine = it }
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
            override fun onStart(utteranceId: String?) {
                if (pending?.first == utteranceId) activeTrace?.log("playback_started", "engine=android_tts")
            }
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
        check(!closed)
        korva?.let { voice ->
            val speaking = scope.async(currentCoroutineContext()[AudioTrace] ?: EmptyCoroutineContext) { voice.speak(text, {}, {}) }
            try { speaking.await() } finally { speaking.cancel() }
            return
        }
        traceAudio("system_tts_requested", "chars=${text.length}")
        val engine = checkNotNull(engine)
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        activeTrace = currentCoroutineContext()[AudioTrace]
        pending = id to completion
        try {
            check(engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS)
            withTimeout(45000) { completion.await() }
            traceAudio("system_tts_completed")
        } finally {
            activeTrace = null
            pending = null
        }
    }

    suspend fun speakRequests(requests: Flow<SpeechRequest>) {
        check(!closed)
        val voice = korva
        if (voice != null) {
            val speaking = scope.async { voice.speakRequests(requests) }
            try { speaking.await() } finally { speaking.cancel() }
        } else {
            requests.collect { request ->
                withContext(request.trace) {
                    speak(request.text)
                    request.onCompleted()
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        pending?.second?.cancel()
        pending = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        scope.launch {
            try { korva?.close() } finally { scope.cancel() }
        }
    }
}
