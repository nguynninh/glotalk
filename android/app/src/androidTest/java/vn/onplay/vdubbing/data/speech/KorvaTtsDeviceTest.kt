package vn.onplay.vdubbing.data.speech

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KorvaTtsDeviceTest {
    @Test fun downloadsSynthesizesAndPlaysVietnameseOnDevice(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = SystemClock.elapsedRealtime()
        val store = KorvaModelStore(context)
        val directory = store.prepare()
        val prepared = SystemClock.elapsedRealtime()
        val metrics = StringBuilder("prepare_ms=${prepared - start}\n")
        val text = "Xin chào, đây là giọng đọc tiếng Việt của Glotalk. Hôm nay team mình thử tính năng offline."
        KorvaTtsEngine(directory).use { engine ->
            val loaded = SystemClock.elapsedRealtime()
            metrics.append("load_ms=${loaded - prepared}\n")
            repeat(2) { index ->
                val begun = SystemClock.elapsedRealtime()
                val samples = engine.synthesize(text)
                val elapsed = SystemClock.elapsedRealtime() - begun
                val seconds = samples.size.toDouble() / engine.sampleRate
                val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
                assertTrue(seconds in 1.0..30.0)
                assertTrue(rms > 0.001)
                metrics.append("run=$index synth_ms=$elapsed audio_seconds=$seconds rtf=${elapsed / 1000.0 / seconds} rms=$rms pss_kb=${Debug.getPss()}\n")
                if (index == 0) writeWav(File(context.filesDir, "korva-sample.wav"), samples, engine.sampleRate)
            }
        }
        File(context.filesDir, "korva-benchmark.txt").writeText(metrics.toString())
        Log.i("KorvaBenchmark", metrics.toString())
        assertTrue(store.prepare().isDirectory)
        val speaker = VietnameseSpeechRepositoryImpl(context)
        try {
            speaker.speak("Xin chào! Glotalk đang đọc tiếng Việt trực tiếp trên điện thoại.", {}, {})
        } finally { speaker.close() }
        File(context.filesDir, "korva-benchmark.txt").writeText(metrics.toString())
        Log.i("KorvaBenchmark", metrics.toString())
    }

    @Test fun comparesEightSteps(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = KorvaModelStore(context).prepare()
        KorvaTtsEngine(directory).use { engine ->
            val start = SystemClock.elapsedRealtime()
            val samples = engine.synthesize(
                "Xin chào, đây là giọng đọc tiếng Việt của Glotalk. Hôm nay team mình thử tính năng offline.",
                steps = 8,
            )
            val elapsed = SystemClock.elapsedRealtime() - start
            val seconds = samples.size.toDouble() / engine.sampleRate
            val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
            assertTrue(seconds in 1.0..30.0 && rms > 0.001)
            writeWav(File(context.filesDir, "korva-sample-8.wav"), samples, engine.sampleRate)
            val metrics = "steps=8 synth_ms=$elapsed audio_seconds=$seconds rtf=${elapsed / 1000.0 / seconds} pss_kb=${Debug.getPss()}\n"
            File(context.filesDir, "korva-benchmark-8.txt").writeText(metrics)
            Log.i("KorvaBenchmark", metrics)
        }
    }

    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        val buffer = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { buffer.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        file.writeBytes(buffer.array())
    }
}
