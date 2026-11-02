package vn.onplay.vdubbing.data.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal class KorvaTtsEngine(directory: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
    private val resources = mutableListOf<AutoCloseable>()
    private val indexer: IntArray
    private val duration: OrtSession
    private val encoder: OrtSession
    private val estimator: OrtSession
    private val vocoder: OrtSession
    private val styleTtl: OnnxTensor
    private val styleDp: OnnxTensor
    val sampleRate: Int
    private val chunkSize: Int
    private val latentDim: Int

    init {
        try {
            val config = JSONObject(File(directory, "onnx/tts.json").readText())
            sampleRate = config.getJSONObject("ae").getInt("sample_rate")
            val compress = config.getJSONObject("ttl").getInt("chunk_compress_factor")
            chunkSize = config.getJSONObject("ae").getInt("base_chunk_size") * compress
            latentDim = config.getJSONObject("ttl").getInt("latent_dim") * compress
            val table = JSONArray(File(directory, "onnx/unicode_indexer.json").readText())
            indexer = IntArray(table.length()) { table.getInt(it) }
            val style = JSONObject(File(directory, "voice_styles/khanh_vy.json").readText())
            styleTtl = styleTensor(style.getJSONObject("style_ttl")).also { resources += it }
            styleDp = styleTensor(style.getJSONObject("style_dp")).also { resources += it }
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                options.setInterOpNumThreads(1)
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(false)
                fun session(name: String) = environment.createSession(
                    File(directory, "onnx/$name.onnx").absolutePath, options,
                ).also { resources += it }
                duration = session("duration_predictor")
                encoder = session("text_encoder")
                estimator = session("vector_estimator")
                vocoder = session("vocoder")
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    suspend fun synthesize(text: String, steps: Int = 16, onStep: (Int, Int) -> Unit = { _, _ -> }): FloatArray {
        require(steps in 1..32)
        currentCoroutineContext().ensureActive()
        val ids = KorvaText.normalize(text).codePoints().toArray()
            .filter { it < indexer.size && indexer[it] >= 0 }.map { indexer[it].toLong() }.toLongArray()
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { textIds ->
            tensor(FloatArray(ids.size) { 1f }, 1, 1, ids.size.toLong()).use { textMask ->
                val seconds = duration.run(mapOf("text_ids" to textIds, "style_dp" to styleDp, "text_mask" to textMask)).use {
                    floats(it[0] as OnnxTensor)[0] / 1.05f
                }
                check(seconds.isFinite() && seconds > 0 && seconds <= 30) { "Invalid Korva duration: $seconds" }
                val samples = (seconds * sampleRate).toInt()
                val length = (samples + chunkSize - 1) / chunkSize
                val random = Random()
                var latent = FloatArray(latentDim * length) { random.nextGaussian().toFloat() }
                encoder.run(mapOf("text_ids" to textIds, "style_ttl" to styleTtl, "text_mask" to textMask)).use { encoded ->
                    tensor(FloatArray(length) { 1f }, 1, 1, length.toLong()).use { latentMask ->
                        tensor(floatArrayOf(steps.toFloat()), 1).use { total ->
                            repeat(steps) { step ->
                                currentCoroutineContext().ensureActive()
                                tensor(latent, 1, latentDim.toLong(), length.toLong()).use { noisy ->
                                    tensor(floatArrayOf(step.toFloat()), 1).use { current ->
                                        estimator.run(mapOf("noisy_latent" to noisy, "text_emb" to (encoded[0] as OnnxTensor),
                                            "style_ttl" to styleTtl, "text_mask" to textMask,
                                            "latent_mask" to latentMask, "current_step" to current,
                                            "total_step" to total)).use { latent = floats(it[0] as OnnxTensor) }
                                    }
                                }
                                onStep(step + 1, steps)
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                return tensor(latent, 1, latentDim.toLong(), length.toLong()).use { finalLatent ->
                    vocoder.run(mapOf("latent" to finalLatent)).use {
                        val wave = floats(it[0] as OnnxTensor)
                        check(wave.all { sample -> sample.isFinite() })
                        KorvaAudio.cleanTail(wave.copyOf(minOf(samples, wave.size)), sampleRate)
                    }
                }
            }
        }
    }

    private fun tensor(data: FloatArray, vararg shape: Long) =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape)

    private fun floats(tensor: OnnxTensor): FloatArray = tensor.floatBuffer.let { buffer ->
        FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun styleTensor(entry: JSONObject): OnnxTensor {
        val dims = entry.getJSONArray("dims")
        val values = mutableListOf<Float>()
        fun flatten(array: JSONArray) {
            for (i in 0 until array.length()) {
                val value = array.get(i)
                if (value is JSONArray) flatten(value) else values += (value as Number).toFloat()
            }
        }
        flatten(entry.getJSONArray("data"))
        return tensor(values.toFloatArray(), *LongArray(dims.length()) { dims.getLong(it) })
    }

    override fun close() {
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }
}
