package vn.onplay.vdubbing.framework.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.AudioTranslation

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfflineAudioPlaybackTest {
    @Test fun `recognition continues during playback and every sentence is spoken in order`() = runTest {
        val clips = Channel<ByteArray>(Channel.UNLIMITED)
        val speech = CompletableDeferred<Unit>()
        val translated = mutableListOf<Int>()
        val spoken = mutableListOf<String>()
        val stages = mutableListOf<AudioStage>()
        val job = launch {
            playOfflineAudio(clips.receiveAsFlow(), translate = {
                val id = it[0].toInt()
                translated += id
                AudioTranslation("source $id", "translated $id")
            }, speak = {
                spoken += it
                if (it == "translated 1") speech.await()
            }, onStage = stages::add)
        }
        clips.send(byteArrayOf(1))
        runCurrent()
        clips.send(byteArrayOf(2))
        clips.send(byteArrayOf(3))
        runCurrent()
        assertEquals(listOf(1, 2, 3), translated)
        assertEquals(listOf("translated 1"), spoken)
        assertEquals(AudioStage.SPEAKING, stages.last())
        speech.complete(Unit)
        clips.close()
        job.join()
        assertEquals(listOf("translated 1", "translated 2", "translated 3"), spoken)
        assertEquals(AudioStage.LISTENING, stages.last())
    }

    @Test fun `waiting sentences are preserved through model preparation`() = runTest {
        var now = 0L
        val clips = Channel<ByteArray>(Channel.UNLIMITED)
        val finish = CompletableDeferred<Unit>()
        val translated = mutableListOf<Int>()
        val job = launch {
            playOfflineAudio(clips.receiveAsFlow(), translate = {
                translated += it[0].toInt()
                if (it[0].toInt() == 1) finish.await()
                AudioTranslation("source", "translated")
            }, speak = {}, onStage = {}, nowMillis = { now })
        }
        clips.send(byteArrayOf(1))
        runCurrent()
        clips.send(byteArrayOf(2))
        runCurrent()
        now = 20000
        finish.complete(Unit)
        clips.close()
        job.join()
        assertEquals(listOf(1, 2), translated)
    }

    @Test fun `overload stops explicitly instead of dropping dialogue`() = runTest {
        var captureStopped = false
        try {
            playOfflineAudio(flow {
                try { repeat(10) { emit(byteArrayOf(it.toByte())) } }
                finally { captureStopped = true }
            }, translate = { awaitCancellation() }, speak = {}, onStage = {})
            fail("must report overload")
        } catch (_: OfflineAudioBacklogException) { }
        assertTrue(captureStopped)
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
