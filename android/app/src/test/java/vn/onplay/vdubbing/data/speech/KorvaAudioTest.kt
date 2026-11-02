package vn.onplay.vdubbing.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KorvaAudioTest {
    @Test fun removesShortLoudBurstAfterPause() {
        val wave = FloatArray(1000) { if (it < 600) 0.2f else if (it < 800) 0f else 0.8f }
        val cleaned = KorvaAudio.cleanTail(wave, 1000)
        assertTrue(cleaned.size < 800)
        assertEquals(0f, cleaned.last(), 0f)
    }

    @Test fun preservesModerateSpeechAfterPauseAndFadesEnd() {
        val wave = FloatArray(1000) { if (it in 600..799) 0f else 0.2f }
        val cleaned = KorvaAudio.cleanTail(wave, 1000)
        assertEquals(1000, cleaned.size)
        assertEquals(0.2f, cleaned[850], 0f)
        assertEquals(0f, cleaned.last(), 0f)
    }
}
