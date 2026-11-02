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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnPlayAudioPipelineTest {
    @Test fun `next request runs during playback without buffering more responses`() = runTest {
        val clips = Channel<ByteArray>(Channel.UNLIMITED)
        val finishPlayback = CompletableDeferred<Unit>()
        val requests = mutableListOf<Int>()
        val played = mutableListOf<Int>()
        val stages = mutableListOf<AudioStage>()
        val job = launch {
            playOnPlayAudio(clips.receiveAsFlow(), translate = {
                requests += it[0].toInt()
                it
            }, play = { audio, ready ->
                ready()
                played += audio[0].toInt()
                if (audio[0].toInt() == 1) finishPlayback.await()
            }, onStage = stages::add)
        }
        clips.send(byteArrayOf(1))
        runCurrent()
        clips.send(byteArrayOf(2))
        clips.send(byteArrayOf(3))
        runCurrent()
        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(1), played)
        assertEquals(AudioStage.SPEAKING, stages.last())
        finishPlayback.complete(Unit)
        clips.close()
        job.join()
        assertEquals(listOf(1, 2, 3), requests)
        assertEquals(listOf(1, 2, 3), played)
        assertEquals(AudioStage.LISTENING, stages.last())
    }

    @Test fun `capture still stops on three waiting clips without dropping audio`() = runTest {
        var stopped = false
        try {
            playOnPlayAudio(flow {
                try { repeat(10) { emit(byteArrayOf(it.toByte())) } }
                finally { stopped = true }
            }, translate = { awaitCancellation() }, play = { _, _ -> fail("No response yet") }, onStage = {})
            fail("Expected overflow")
        } catch (_: OfflineAudioBacklogException) { }
        assertTrue(stopped)
    }

    @Test fun `empty response continues and cancellation stops both stages`() = runTest {
        val clips = Channel<ByteArray>(Channel.UNLIMITED)
        var requestCancelled = false
        var played = false
        val job = launch {
            playOnPlayAudio(clips.receiveAsFlow(), translate = {
                if (it[0].toInt() == 1) null else try { awaitCancellation() }
                finally { requestCancelled = true }
            }, play = { _, _ -> played = true }, onStage = {})
        }
        clips.send(byteArrayOf(1))
        clips.send(byteArrayOf(2))
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(requestCancelled)
        assertFalse(played)
    }

    @Test fun `playback failure cancels the next request`() = runTest {
        val playing = CompletableDeferred<Unit>()
        var cancelled = false
        try {
            playOnPlayAudio(flow {
                emit(byteArrayOf(1))
                emit(byteArrayOf(2))
            }, translate = {
                if (it[0].toInt() == 1) it else try {
                    playing.complete(Unit)
                    awaitCancellation()
                } finally { cancelled = true }
            }, play = { _, _ ->
                playing.await()
                throw IllegalStateException("player failed")
            }, onStage = {})
            fail("Expected player failure")
        } catch (error: IllegalStateException) { assertEquals("player failed", error.message) }
        assertTrue(cancelled)
    }
}
