package vn.onplay.vdubbing.framework.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import vn.onplay.vdubbing.data.audio.traceAudio

internal suspend fun playOnPlayWav(context: Context, wav: ByteArray, onReady: () -> Unit) {
    val file = File.createTempFile("onplay-", ".wav", context.cacheDir)
    try {
        withContext(Dispatchers.IO) { file.writeBytes(wav) }
        withContext(Dispatchers.Main) {
            val player = MediaPlayer()
            try {
                val ready = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<Unit>()
                player.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
                player.setOnPreparedListener { ready.complete(Unit) }
                player.setOnCompletionListener { completed.complete(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    val failure = IllegalStateException("ONPlay playback error $what/$extra")
                    ready.completeExceptionally(failure)
                    completed.completeExceptionally(failure)
                    true
                }
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
                withTimeout(10000) { ready.await() }
                onReady()
                player.start()
                traceAudio("playback_started", "at_ms=${SystemClock.elapsedRealtime()} duration_ms=${player.duration}")
                withTimeout(player.duration.toLong() + 10000) { completed.await() }
            } finally {
                player.release()
            }
        }
    } finally {
        file.delete()
    }
}
