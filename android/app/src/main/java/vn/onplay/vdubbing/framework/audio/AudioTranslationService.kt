package vn.onplay.vdubbing.framework.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import vn.onplay.vdubbing.data.audio.GeminiLiveTranslation
import vn.onplay.vdubbing.data.audio.GeminiLiveAudioException
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.MainActivity
import vn.onplay.vdubbing.BuildConfig
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.VDubbingApp
import vn.onplay.vdubbing.data.audio.LocalWhisper
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.usecase.TranslateAudioSession
import vn.onplay.vdubbing.domain.repository.AudioTranslationRepository
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.locale.AppLocaleController

internal class AudioTranslationService : Service() {
    private val container get() = (application as VDubbingApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager get() = getSystemService(NotificationManager::class.java)
    private var session: AudioSession? = null
    private var projection: MediaProjection? = null
    private var speaker: AudioTranslationSpeaker? = null
    private var realtimePlayer: RealtimeAudioPlayer? = null
    private var pipeline: Job? = null
    private var whisper: LocalWhisper? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            container.audioSessions.state.collect { current ->
                val owned = session
                if (owned != null && current?.id != owned.id) stopSession()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(AudioCapturePermissionActivity.EXTRA_SESSION)
        val requested = container.audioSessions.state.value?.takeIf { it.id == id }
        if (requested == null || AccessibilityBridge.service == null) {
            if (requested != null) container.audioSessions.end(requested.id)
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }
        if (session?.id == id) return START_NOT_STICKY
        releaseSession()
        session = requested
        try {
            manager.cancel(ERROR_NOTIFICATION_ID)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, text(R.string.audio_title), NotificationManager.IMPORTANCE_LOW,
            ))
            startForeground(NOTIFICATION_ID, notification(R.string.audio_listening).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            val consent = IntentCompat.getParcelableExtra(intent!!, EXTRA_CONSENT, Intent::class.java)
                ?: error("missing projection consent")
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EXTRA_RESULT, 0), consent)
            val activeProjection = projection ?: error("missing projection")
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (session?.id == requested.id) stopSession()
                }
            }
            projectionCallback = callback
            activeProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
            pipeline = scope.launch {
                try {
                    if (container.settings.geminiLiveAudioEnabled) {
                        runGeminiLivePipeline(requested, activeProjection)
                    } else {
                        runLocalPipeline(requested, activeProjection)
                    }
                } catch (_: TimeoutCancellationException) {
                    fail(R.string.audio_network_failed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(when (error) {
                        is AudioFailure -> error.messageRes
                        is GeminiLiveAudioException -> when (error.reason) {
                            GeminiLiveAudioException.Reason.KEY -> R.string.gemini_live_key_required
                            GeminiLiveAudioException.Reason.LIMIT -> R.string.gemini_live_limit_failed
                            GeminiLiveAudioException.Reason.NETWORK -> R.string.audio_network_failed
                            GeminiLiveAudioException.Reason.TOO_SLOW -> R.string.audio_too_slow
                            GeminiLiveAudioException.Reason.API -> R.string.gemini_live_api_failed
                        }
                        is TranslationException.Offline -> R.string.audio_network_failed
                        is TranslationException -> R.string.audio_translation_failed
                        else -> R.string.audio_capture_failed
                    })
                }
            }
        } catch (_: Exception) {
            fail(R.string.audio_start_failed)
        }
        return START_NOT_STICKY
    }

    private suspend fun runGeminiLivePipeline(current: AudioSession, activeProjection: MediaProjection) {
        val key = container.settings.geminiLiveKey
        if (key.isBlank()) throw AudioFailure(R.string.gemini_live_key_required)
        update(AudioStage.CONNECTING)
        val player = RealtimeAudioPlayer().also { realtimePlayer = it }
        val capture = AppAudioCapture(activeProjection, current.uid)
        val input = channelFlow {
            scope.launch { if (session?.id == current.id) update(AudioStage.LISTENING) }
            capture.collectFrames(16000, 100, onSilence = {
                scope.launch { if (session?.id == current.id) update(AudioStage.NO_AUDIO) }
            }) { send(it.copyOf()) }
        }.buffer(1)
        player.play(GeminiLiveTranslation().audio(
            key, current.target, input,
        )) {
            scope.launch { if (session?.id == current.id) update(AudioStage.SPEAKING) }
        }
    }

    private suspend fun runLocalPipeline(current: AudioSession, activeProjection: MediaProjection) {
        val local = try { LocalWhisper().also { whisper = it } } catch (_: LinkageError) {
            throw AudioFailure(R.string.audio_model_failed)
        }
        try {
            update(AudioStage.PREPARING_MODEL)
            val (_, vad) = try {
                container.whisperModels.prepare().also { local.initialize(it.first) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw AudioFailure(R.string.audio_model_failed)
            }
            val voice = AudioTranslationSpeaker(applicationContext).also { speaker = it }
            try {
                voice.initialize(current.target)
            } catch (_: TimeoutCancellationException) {
                throw AudioFailure(R.string.audio_voice_failed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw AudioFailure(R.string.audio_voice_failed)
            }
            container.translation.prepare(
                current.source.takeIf { it.isTranslatable } ?: Language.ENGLISH, current.target,
            )
            runPipeline(current, activeProjection, voice, container.audioTranslation(local, vad))
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { local.close() }
        }
    }

    private suspend fun runPipeline(
        current: AudioSession,
        activeProjection: MediaProjection,
        voice: AudioTranslationSpeaker,
        repository: AudioTranslationRepository,
    ) = coroutineScope {
        val clips = Channel<CapturedClip>(2)
        val speech = Channel<SpokenTranslation>(2)
        val translator = TranslateAudioSession(repository, current.source, current.target)
        update(AudioStage.LISTENING)
        try {
            launch {
                AppAudioCapture(activeProjection, current.uid).collect(
                    onSilence = { scope.launch {
                        if (session?.id == current.id) update(AudioStage.NO_AUDIO)
                    } },
                    onClip = { wav ->
                        if (clips.trySend(CapturedClip(wav, SystemClock.elapsedRealtime())).isFailure) {
                            throw AudioFailure(R.string.audio_too_slow)
                        }
                    },
                )
            }
            launch {
                for (clip in clips) {
                    checkFresh(clip.at)
                    update(AudioStage.TRANSLATING)
                    val result = translator.translate(clip.wav)
                    checkFresh(clip.at)
                    if (result != null) speech.send(SpokenTranslation(result.translated, clip.at))
                    else update(AudioStage.LISTENING)
                }
            }
            for (utterance in speech) {
                checkFresh(utterance.at)
                update(AudioStage.SPEAKING)
                try {
                    voice.speak(utterance.text)
                } catch (_: TimeoutCancellationException) {
                    throw AudioFailure(R.string.audio_voice_failed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AudioFailure(R.string.audio_voice_failed)
                }
                update(AudioStage.LISTENING)
            }
        } finally {
            clips.cancel()
            speech.cancel()
        }
    }

    private fun checkFresh(at: Long) {
        if (SystemClock.elapsedRealtime() - at > 30000) throw AudioFailure(R.string.audio_too_slow)
    }

    private fun update(stage: AudioStage) {
        val active = session ?: return
        if (container.audioSessions.state.value?.id != active.id || active.stage == stage) return
        session = active.copy(stage = stage)
        container.audioSessions.update(active.id, stage)
        manager.notify(NOTIFICATION_ID, notification(stage.messageRes).build())
    }

    private fun fail(message: Int) {
        Log.w("AudioTranslation", "Session stopped: ${resources.getResourceEntryName(message)}")
        stopSession()
        manager.createNotificationChannel(NotificationChannel(
            ERROR_CHANNEL, text(R.string.audio_title), NotificationManager.IMPORTANCE_HIGH,
        ))
        Toast.makeText(this, text(message), Toast.LENGTH_LONG).show()
        manager.notify(ERROR_NOTIFICATION_ID, notification(message)
            .setChannelId(ERROR_CHANNEL)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text(message)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false).clearActions().setAutoCancel(true).build())
    }

    private fun releaseSession() {
        val ending = session
        session = null
        pipeline?.cancel()
        pipeline = null
        whisper?.cancel()
        whisper = null
        realtimePlayer?.stop()
        realtimePlayer = null
        speaker?.close()
        speaker = null
        projection?.let {
            projectionCallback?.let(it::unregisterCallback)
            runCatching { it.stop() }
        }
        projection = null
        projectionCallback = null
        ending?.let { container.audioSessions.end(it.id) }
    }

    private fun stopSession() {
        releaseSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun text(id: Int) = AppLocaleController.localized(this).getString(id)

    private fun notification(message: Int): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11,
            Intent(this, AudioTranslationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(text(R.string.audio_title)).setContentText(text(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, text(R.string.audio_stop), stop)
    }

    private data class CapturedClip(val wav: ByteArray, val at: Long)
    private data class SpokenTranslation(val text: String, val at: Long)
    private class AudioFailure(val messageRes: Int) : Exception()

    companion object {
        private const val CHANNEL = "vdubbing_audio"
        private const val ERROR_CHANNEL = "vdubbing_audio_errors"
        private const val NOTIFICATION_ID = 1002
        private const val ERROR_NOTIFICATION_ID = 1003
        private const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".audio.STOP"
        private const val EXTRA_CONSENT = "consent"
        private const val EXTRA_RESULT = "result"

        fun start(context: Context, sessionId: String, resultCode: Int, consent: Intent) {
            context.startForegroundService(Intent(context, AudioTranslationService::class.java)
                .putExtra(AudioCapturePermissionActivity.EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_RESULT, resultCode).putExtra(EXTRA_CONSENT, consent))
        }

        fun stop(context: Context) {
            val sessions = (context.applicationContext as VDubbingApp).container.audioSessions
            sessions.state.value?.let { sessions.end(it.id) }
            context.stopService(Intent(context, AudioTranslationService::class.java))
        }
    }
}

internal val AudioStage.messageRes: Int get() = when (this) {
    AudioStage.PERMISSION -> R.string.audio_requesting_permission
    AudioStage.CONNECTING -> R.string.gemini_live_connecting
    AudioStage.PREPARING_MODEL -> R.string.audio_preparing_model
    AudioStage.LISTENING -> R.string.audio_listening
    AudioStage.TRANSLATING -> R.string.audio_translating
    AudioStage.SPEAKING -> R.string.audio_speaking
    AudioStage.NO_AUDIO -> R.string.audio_no_sound
}
