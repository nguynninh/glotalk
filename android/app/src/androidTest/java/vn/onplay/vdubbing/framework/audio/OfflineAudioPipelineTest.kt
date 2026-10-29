package vn.onplay.vdubbing.framework.audio

import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.app.UiAutomation
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import vn.onplay.vdubbing.framework.overlay.OverlayTestActivity
import vn.onplay.vdubbing.VDubbingApp
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.data.audio.LocalWhisper
import vn.onplay.vdubbing.data.audio.WhisperAudioTranslationRepository
import vn.onplay.vdubbing.data.audio.WhisperModelStore
import vn.onplay.vdubbing.data.mlkit.TranslationRepositoryImpl
import vn.onplay.vdubbing.domain.model.Language

@RunWith(AndroidJUnit4::class)
class OfflineAudioPipelineTest {
    @Test fun capturesAnotherAppAndCompletesVietnameseSpeech() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("playbackCapture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        val container = (context.applicationContext as VDubbingApp).container
        if (!AccessibilityBridge.isReady) {
            val attached = CompletableDeferred<Unit>()
            val previous = AccessibilityBridge.onAttached
            AccessibilityBridge.onAttached = { previous?.invoke(); attached.complete(Unit) }
            try {
                if (AccessibilityBridge.isReady) attached.complete(Unit)
                withTimeout(30000) { attached.await() }
            } finally {
                AccessibilityBridge.onAttached = previous
            }
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val translatedVolume = audioManager.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
        val playbackStarted = CompletableDeferred<Unit>()
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY }) {
                    playbackStarted.complete(Unit)
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        val wasGeminiLive = container.settings.geminiLiveAudioEnabled
        val source = Intent(testContext, AudioSourceTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            container.settings.geminiLiveAudioEnabled = false
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 6, 0)
            assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
            val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                .substringBefore('/')
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeAndWaitForEvent(
                    { testContext.startActivity(Intent(source).putExtra("keyboard", true)) },
                    { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && it.packageName?.toString() == keyboard },
                    15000,
                ).recycle()
            val selected = withContext(Dispatchers.Main) { AccessibilityBridge.service?.foregroundPackage() }
            assertEquals("Keyboard must not become the audio source", testContext.packageName, selected)
            val uid = context.packageManager.getPackageUid(testContext.packageName, 0)
            assertNotEquals(context.applicationInfo.uid, uid)
            withContext(Dispatchers.Main) {
                val session = container.audioSessions.begin(testContext.packageName, uid, Language.AUTO, Language.VIETNAMESE)
                context.startActivity(AudioCapturePermissionActivity.intent(context, session.id))
            }
            val began = SystemClock.elapsedRealtime()
            withTimeout(120000) {
                container.audioSessions.state.first { checkNotNull(it) { "Audio session stopped before speaking" }.stage == AudioStage.SPEAKING }
                withTimeout(10000) { playbackStarted.await() }
                Log.i("AudioPipelineTest", "captured_speech_started_ms=${SystemClock.elapsedRealtime() - began}")
                container.audioSessions.state.first { checkNotNull(it) { "Audio session stopped during speech" }.stage == AudioStage.LISTENING }
            }
            assertNotNull(container.audioSessions.state.value)
            Log.i("AudioPipelineTest", "captured_speech_completed_ms=${SystemClock.elapsedRealtime() - began}")
            Unit
        } finally {
            withContext(Dispatchers.Main) { AudioTranslationService.stop(context) }
            audioManager.unregisterAudioPlaybackCallback(callback)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, translatedVolume, 0)
            container.settings.geminiLiveAudioEnabled = wasGeminiLive
            testContext.startActivity(Intent(source).setAction("STOP"))
        }
    }

    @Test fun translatesSelectedPlayingApp() = runBlocking {
        val packageName = InstrumentationRegistry.getArguments().getString("audioSourcePackage")
        assumeTrue(packageName != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as VDubbingApp).container
        val uid = context.packageManager.getPackageUid(packageName!!, 0)
        val manager = context.getSystemService(AudioManager::class.java)
        val played = CompletableDeferred<Unit>()
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY }) {
                    played.complete(Unit)
                }
            }
        }
        manager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        try {
            withContext(Dispatchers.Main) {
                val session = container.audioSessions.begin(packageName, uid, Language.AUTO, Language.VIETNAMESE)
                context.startActivity(AudioCapturePermissionActivity.intent(context, session.id))
            }
            withTimeout(120000) { played.await() }
            Log.i("AudioPipelineTest", "Real source $packageName produced accessibility audio")
            repeat(3) {
                withTimeout(60000) {
                    container.audioSessions.state.first { checkNotNull(it).stage == AudioStage.LISTENING }
                    container.audioSessions.state.first { checkNotNull(it).stage == AudioStage.SPEAKING }
                }
            }
            Unit
        } finally {
            manager.unregisterAudioPlaybackCallback(callback)
            withContext(Dispatchers.Main) { AudioTranslationService.stop(context) }
        }
    }

    @Test fun recognizesTranslatesAndSpeaksEnglishInVietnamese() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val (model, vad) = WhisperModelStore(context).prepare()
        val whisper = LocalWhisper()
        val translation = TranslationRepositoryImpl()
        val activity = ActivityScenario.launch(OverlayTestActivity::class.java)
        val speaker = withContext(Dispatchers.Main) { AudioTranslationSpeaker(context) }
        try {
            whisper.initialize(model)
            withContext(Dispatchers.Main) { speaker.initialize(Language.VIETNAMESE) }
            translation.prepare(Language.ENGLISH, Language.VIETNAMESE)
            val repository = WhisperAudioTranslationRepository(
                transcribe = { wav, source, prompt ->
                    val at = SystemClock.elapsedRealtime()
                    whisper.transcribe(wav, source, prompt, vad).also {
                        Log.i("AudioPipelineTest", "recognition_ms=${SystemClock.elapsedRealtime() - at}")
                    }
                },
                translation = translation,
            )
            val wav = instrumentation.context.assets.open("whisper-jfk.wav").use { it.readBytes() }
            val at = SystemClock.elapsedRealtime()
            val result = withTimeout(120000) {
                repository.translate(wav, Language.AUTO, Language.VIETNAMESE, emptyList())
            }!!
            val readyMillis = SystemClock.elapsedRealtime() - at
            Log.i("AudioPipelineTest", "translation_ready_ms=$readyMillis")
            assertTrue("Translation missed the playback deadline: $readyMillis ms", readyMillis < 30000)
            assertTrue(result.original, result.original.contains("country", ignoreCase = true))
            assertNotEquals(result.original, result.translated)
            assertTrue(result.translated, result.translated.contains("bạn", ignoreCase = true))
            withContext(Dispatchers.Main) { speaker.speak(result.translated) }
            Log.i("AudioPipelineTest", "speech_completed_ms=${SystemClock.elapsedRealtime() - at}")
            Unit
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { speaker.close() }
            translation.close()
            activity.close()
            withContext(NonCancellable + Dispatchers.Default) { whisper.close() }
        }
    }
}
