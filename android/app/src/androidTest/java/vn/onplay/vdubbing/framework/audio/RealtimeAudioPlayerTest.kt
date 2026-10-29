package vn.onplay.vdubbing.framework.audio

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Build
import org.junit.Assume.assumeTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeAudioPlayerTest {
    @Test fun startsPlayingWithOne20msPacketWithoutWaitingForMoreAudio() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val active = createRealtimeAudioTrack()
        val played = CompletableDeferred<Unit>()
        try {
            active.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) { played.complete(Unit) }
                override fun onPeriodicNotification(track: AudioTrack) = Unit
            }, Handler(Looper.getMainLooper()))
            check(active.setNotificationMarkerPosition(480) == AudioTrack.SUCCESS)
            active.play()
            check(active.write(ByteArray(960), 0, 960) == 960)
            withTimeout(3000) { played.await() }
        } finally {
            active.stop()
            active.release()
        }
    }

    @Test fun plays24kPcmAndReleasesAfterCancellation() = runBlocking {
        repeat(2) {
            val player = RealtimeAudioPlayer()
            val playing = CompletableDeferred<Unit>()
            val job = launch {
                player.play(flow { emit(ByteArray(4800)); awaitCancellation() }) { playing.complete(Unit) }
            }
            try {
                withTimeout(5000) { playing.await() }
            } finally {
                job.cancel()
                player.stop()
                withTimeout(5000) { job.join() }
            }
        }
    }
}
