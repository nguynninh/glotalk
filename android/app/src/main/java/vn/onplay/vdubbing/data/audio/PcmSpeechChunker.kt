package vn.onplay.vdubbing.data.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal class PcmSpeechChunker {
    private val audio = ByteArrayOutputStream()
    private val preRoll = ArrayDeque<ByteArray>()
    private var frames = 0
    private var quietFrames = 0
    private var audibleFrames = 0

    fun accept(frame: ByteArray): ByteArray? {
        require(frame.size == FRAME_BYTES)
        val audible = isAudible(frame)
        if (frames == 0 && !audible) {
            preRoll.addLast(frame.copyOf())
            if (preRoll.size > 10) preRoll.removeFirst()
            return null
        }
        if (frames == 0) {
            preRoll.forEach { audio.write(it) }
            preRoll.clear()
        }
        audio.write(frame)
        frames++
        if (audible) {
            audibleFrames++
            quietFrames = 0
        } else quietFrames++
        if (frames < MAX_FRAMES && (frames < 50 || quietFrames < 40)) return null
        val chunk = if (audibleFrames >= 10) wav(audio.toByteArray()) else null
        audio.reset()
        frames = 0
        quietFrames = 0
        audibleFrames = 0
        return chunk
    }

    companion object {
        const val MAX_FRAMES = 250
        const val SAMPLE_RATE = 16000
        const val FRAME_BYTES = 640 // 20 ms of mono PCM16 at 16 kHz.

        // shortcut: energy only finds sound boundaries; Whisper's VAD filters non-speech.
        fun isAudible(pcm: ByteArray): Boolean {
            var energy = 0.0
            for (index in 0 until pcm.size - 1 step 2) {
                val sample = ((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
                energy += sample.toDouble() * sample
            }
            return pcm.size >= 2 && sqrt(energy / (pcm.size / 2)) >= 100
        }

        private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
            .putShort(2).putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm).array()
    }
}
