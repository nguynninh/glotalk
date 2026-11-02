package vn.onplay.vdubbing.data.audio

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.data.speech.VietnameseSpeechRepositoryImpl

@RunWith(AndroidJUnit4::class)
class OnPlayHttpsDeviceTest {
    @Test fun uploadsWavAndPlaysTranslatedAudio(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wav = instrumentation.context.assets.open("whisper-jfk.wav").use { it.readBytes() }
        val server = OnPlayAudioTranslation()
        val output = File(context.cacheDir, "onplay-translated.wav")
        val metrics = StringBuilder()
        val start = SystemClock.elapsedRealtime()
        try {
            val audio = server.translate(wav, vn.onplay.vdubbing.domain.model.Language.VIETNAMESE)
            assertNotNull(audio)
            output.writeBytes(audio!!)
            metrics.appendLine("download_ms=${SystemClock.elapsedRealtime() - start} bytes=${output.length()}")
            vn.onplay.vdubbing.framework.audio.playOnPlayWav(context, audio) {
                metrics.appendLine("playback_start_ms=${SystemClock.elapsedRealtime() - start}")
            }
            metrics.appendLine("playback_complete_ms=${SystemClock.elapsedRealtime() - start}")
        } finally {
            Log.i("OnPlayBenchmark", metrics.toString())
            File(context.filesDir, "onplay-audio-benchmark.txt").writeText(metrics.toString())
            output.delete()
            server.close()
        }
    }

    @Test fun transcribesTranslatesAndSpeaks(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val metrics = StringBuilder()
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).build()
        val uploadClient = client.newBuilder().callTimeout(120, TimeUnit.SECONDS).build()
        fun post(path: String, body: RequestBody, transport: OkHttpClient = client): JSONObject {
            val began = SystemClock.elapsedRealtime()
            val request = Request.Builder().url("https://stt.qiverse.top/api/$path")
                .header("User-Agent", "ONPlayMobile/1.0").post(body).build()
            return transport.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "$path HTTP ${response.code}" }
                check(response.header("Content-Type").orEmpty().contains("application/json", true))
                JSONObject(checkNotNull(response.body).string()).also {
                    val line = "$path elapsed_ms=${SystemClock.elapsedRealtime() - began} response=$it"
                    metrics.appendLine(line)
                    Log.i("OnPlayBenchmark", line)
                }
            }
        }
        val speaker = VietnameseSpeechRepositoryImpl(context)
        try {
            val wav = instrumentation.context.assets.open("whisper-jfk.wav").use { it.readBytes() }
            val segments = post("transcribe", wav.toRequestBody("audio/wav".toMediaType()), uploadClient)
                .getJSONArray("segments")
            assertTrue(segments.length() > 0)
            val translated = mutableListOf<String>()
            for (index in 0 until segments.length()) {
                val segment = segments.getJSONObject(index)
                assertTrue(segment.getDouble("end") >= segment.getDouble("start"))
                segment.getInt("speaker")
                val original = segment.getString("text")
                if (original.isBlank()) continue
                val payload = JSONObject().put("text", original).put("source", "en")
                    .put("target", "vi").put("mode", "standard")
                val result = post("translate", payload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()))
                assertEquals("vi", result.getString("target"))
                translated += result.getString("text").also { assertTrue(it.isNotBlank()) }
            }
            assertTrue(translated.isNotEmpty())
            val preparing = SystemClock.elapsedRealtime()
            speaker.prepare()
            metrics.appendLine("tts_prepare_ms=${SystemClock.elapsedRealtime() - preparing}")
            for (text in translated) {
                val began = SystemClock.elapsedRealtime()
                var firstReady = true
                speaker.speak(text, {}, {
                    if (firstReady) {
                        val line = "tts_first_pcm_ms=${SystemClock.elapsedRealtime() - began} chars=${text.length}"
                        metrics.appendLine(line)
                        Log.i("OnPlayBenchmark", line)
                        firstReady = false
                    }
                })
                assertFalse(firstReady)
                metrics.appendLine("tts_including_playback_ms=${SystemClock.elapsedRealtime() - began}")
            }
        } finally {
            File(context.filesDir, "onplay-https-benchmark.txt").writeText(metrics.toString())
            Log.i("OnPlayBenchmark", metrics.toString())
            speaker.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
