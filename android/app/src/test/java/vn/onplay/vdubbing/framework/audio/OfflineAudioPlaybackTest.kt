package vn.onplay.vdubbing.framework.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.AudioTranslation

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfflineAudioPlaybackTest {
    @Test fun `slow recognition finishes and speaks before processing the latest waiting clip`() = runTest {
        val clips = Channel<ByteArray>(Channel.UNLIMITED)
        val recognition = CompletableDeferred<Unit>()
        val speech = CompletableDeferred<Unit>()
        val translated = mutableListOf<Int>()
        val spoken = mutableListOf<String>()
        val stages = mutableListOf<AudioStage>()
        val job = launch {
            playOfflineAudio(clips.receiveAsFlow(), translate = { clip ->
                val id = clip[0].toInt()
                translated += id
                if (id == 1) recognition.await()
                AudioTranslation("source $id", "translated $id")
            }, speak = {
                spoken += it
                if (it == "translated 1") speech.await()
            }, onStage = stages::add)
        }
        clips.send(byteArrayOf(1))
        runCurrent()
        repeat(20) { clips.send(byteArrayOf((it + 2).toByte())) }
        runCurrent()
        advanceTimeBy(45000)
        recognition.complete(Unit)
        runCurrent()
        assertEquals(listOf("translated 1"), spoken)
        assertEquals(listOf(1), translated)
        repeat(20) { clips.send(byteArrayOf((it + 22).toByte())) }
        runCurrent()
        speech.complete(Unit)
        clips.close()
        job.join()
        assertEquals(listOf(1, 41), translated)
        assertEquals(listOf("translated 1", "translated 41"), spoken)
        assertEquals(AudioStage.LISTENING, stages.last())
    }

    @Test fun `stopping during recognition cancels capture and never speaks stale audio`() = runTest {
        var captureStopped = false
        var spoken = false
        val job = launch {
            playOfflineAudio(flow {
                try {
                    emit(byteArrayOf(1))
                    awaitCancellation()
                } finally { captureStopped = true }
            }, translate = { awaitCancellation() }, speak = { spoken = true }, onStage = {})
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(captureStopped)
        assertFalse(spoken)
    }
}
