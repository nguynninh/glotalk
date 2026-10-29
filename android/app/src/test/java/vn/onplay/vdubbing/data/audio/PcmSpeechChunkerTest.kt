package vn.onplay.vdubbing.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmSpeechChunkerTest {
    private val silence = ByteArray(PcmSpeechChunker.FRAME_BYTES)
    private val speech = ByteBuffer.allocate(PcmSpeechChunker.FRAME_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).apply { repeat(capacity() / 2) { putShort(1000) } }.array()

    @Test fun `silence and short noise do not produce uploads`() {
        val chunker = PcmSpeechChunker()
        repeat(1000) { assertNull(chunker.accept(silence)) }
        repeat(3) { assertNull(chunker.accept(speech)) }
        repeat(100) { assertNull(chunker.accept(silence)) }
    }

    @Test fun `a pause completes a valid PCM16 WAV with pre roll and all speech`() {
        val chunker = PcmSpeechChunker()
        repeat(20) { chunker.accept(silence) }
        repeat(100) { assertNull(chunker.accept(speech)) }
        repeat(39) { assertNull(chunker.accept(silence)) }
        val wav = chunker.accept(silence)!!
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals(16000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(150 * speech.size, header.getInt(40))
        assertArrayEquals(speech, wav.copyOfRange(44 + 10 * speech.size, 44 + 11 * speech.size))
        repeat(100) { assertNull(chunker.accept(silence)) }
    }

    @Test fun `continuous audio is bounded and preserves samples at forced boundaries`() {
        val chunker = PcmSpeechChunker()
        val chunks = (0 until 1500).mapNotNull { chunker.accept(speech) }
        assertEquals(6, chunks.size)
        chunks.forEach {
            assertEquals(44 + 250 * speech.size, it.size)
            assertArrayEquals(speech, it.takeLast(speech.size).toByteArray())
        }
    }

    @Test fun `negative samples are decoded as signed PCM16`() {
        val negative = ByteBuffer.allocate(speech.size).order(ByteOrder.LITTLE_ENDIAN)
            .apply { repeat(capacity() / 2) { putShort(-1000) } }.array()
        assertTrue(PcmSpeechChunker.isAudible(negative))
        assertFalse(PcmSpeechChunker.isAudible(silence))
    }
}
