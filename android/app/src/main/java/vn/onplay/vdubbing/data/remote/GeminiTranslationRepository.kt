package vn.onplay.vdubbing.data.remote

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.onplay.vdubbing.BuildConfig
import vn.onplay.vdubbing.data.capture.BackgroundSampler
import vn.onplay.vdubbing.data.capture.BitmapCaptureStore
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.CapturedScreen
import vn.onplay.vdubbing.domain.repository.CloudTranslationRepository
import vn.onplay.vdubbing.domain.repository.SettingsRepository

/**
 * Cloud fallback: one Gemini request does OCR *and* translation and returns
 * bounding boxes, so the on-device pipeline is skipped entirely.
 *
 * The API key comes from settings at call time and never leaves this class —
 * it is not logged, not persisted anywhere else, and not part of any result.
 */
class GeminiTranslationRepository(
    private val store: BitmapCaptureStore,
    private val settings: SettingsRepository,
    private val http: OkHttpClient = defaultClient(),
) : CloudTranslationRepository {

    override fun isConfigured(): Boolean =
        settings.cloudFallbackEnabled && settings.geminiKey.isNotBlank()

    override suspend fun translate(
        screen: CapturedScreen,
        source: Language,
        target: Language,
    ): Translation = withContext(Dispatchers.IO) {
        val frame = store.frame(screen)
            ?: throw TranslationException.CaptureFailed(IllegalStateException("capture expired"))

        val request = Request.Builder()
            .url(BuildConfig.GEMINI_ENDPOINT)
            .header(HEADER_KEY, settings.geminiKey)
            .post(buildBody(frame.bitmap, source, target).toRequestBody(JSON))
            .build()

        val (status, body) = runCatching {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        }.getOrElse { throw TranslationException.Offline(it) }

        when (status) {
            HTTP_OK -> Unit
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                throw TranslationException.InvalidApiKey()
            HTTP_TOO_MANY -> throw TranslationException.QuotaExceeded()
            else -> throw TranslationException.TranslationFailed("Gemini trả HTTP $status.")
        }

        val blocks = parseBlocks(body).map { raw ->
            val block = restoreScreenBounds(raw, frame.width, frame.height)
            block.copy(backgroundColor = BackgroundSampler.sample(
                block, frame.width, frame.height, frame.bitmap::getPixel,
            ))
        }
        Translation(
            blocks = blocks,
            engine = TranslationEngine.CLOUD,
            source = source,
            target = target,
        )
    }

    /** Cheap call behind the "check key" button. */
    override suspend fun verifyKey(key: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", BuildConfig.GEMINI_MODEL)
            .put("input", "ping")
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url(BuildConfig.GEMINI_ENDPOINT)
            .header(HEADER_KEY, key)
            .post(body)
            .build()

        val status = runCatching {
            http.newCall(request).execute().use { it.code }
        }.getOrElse { throw TranslationException.Offline(it) }

        when (status) {
            HTTP_OK -> Unit
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                throw TranslationException.InvalidApiKey()
            HTTP_TOO_MANY -> throw TranslationException.QuotaExceeded()
            else -> throw TranslationException.TranslationFailed("HTTP $status")
        }
    }

    private fun buildBody(bitmap: Bitmap, source: Language, target: Language): String {
        val prompt = buildString {
            append("OCR every piece of text visible in this screenshot and translate it to ")
            append(target.code)
            if (source != Language.AUTO) {
                append(" (the on-screen text is ")
                append(source.code)
                append(")")
            }
            append(
                ". Return one entry per visual text line, in reading order, with its " +
                    "bounding box in pixels of THIS image: x, y is the top-left corner, " +
                    "w and h are width and height. Skip text you cannot read; never guess.",
            )
        }

        val image = JSONObject()
            .put("type", "image")
            .put("data", Base64.encodeToString(toJpeg(bitmap), Base64.NO_WRAP))
            .put("mime_type", "image/jpeg")

        return JSONObject()
            .put("model", BuildConfig.GEMINI_MODEL)
            .put(
                "input",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(image),
            )
            .put("response_format", responseFormat())
            .toString()
    }

    private fun responseFormat(): JSONObject {
        val item = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("original", JSONObject().put("type", "string"))
                    .put("translated", JSONObject().put("type", "string"))
                    .put("lang", JSONObject().put("type", "string"))
                    .put("x", JSONObject().put("type", "integer"))
                    .put("y", JSONObject().put("type", "integer"))
                    .put("w", JSONObject().put("type", "integer"))
                    .put("h", JSONObject().put("type", "integer")),
            )
            .put(
                "required",
                JSONArray().apply {
                    REQUIRED_FIELDS.forEach { put(it) }
                },
            )

        return JSONObject()
            .put("type", "text")
            .put("mime_type", "application/json")
            .put(
                "schema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "blocks",
                            JSONObject().put("type", "array").put("items", item),
                        ),
                    )
                    .put("required", JSONArray().put("blocks")),
            )
    }

    private fun parseBlocks(body: String): List<TextBlock> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw TranslationException.TranslationFailed("Phản hồi Gemini không phải JSON.")

        // Structured output arrives as a JSON string in output_text.
        val payload = root.optString("output_text").ifBlank {
            root.optJSONObject("output_text")?.toString().orEmpty()
        }
        if (payload.isBlank()) throw TranslationException.NoTextFound()

        val array = runCatching { JSONObject(payload).optJSONArray("blocks") }.getOrNull()
            ?: throw TranslationException.NoTextFound()

        val blocks = (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val translated = entry.optString("translated").trim()
            if (translated.isEmpty()) return@mapNotNull null
            val box = Rect(
                entry.optInt("x"),
                entry.optInt("y"),
                0,
                0,
            ).apply {
                right = left + entry.optInt("w")
                bottom = top + entry.optInt("h")
            }
            if (box.width() <= 0 || box.height() <= 0) return@mapNotNull null
            TextBlock(
                original = entry.optString("original"),
                translated = translated,
                language = Language.fromCode(entry.optString("lang")),
                left = box.left,
                top = box.top,
                width = box.width(),
                height = box.height(),
            )
        }

        if (blocks.isEmpty()) throw TranslationException.NoTextFound()
        return blocks
    }

    /** Downscale to a 1600px long edge and re-encode, keeping the request small. */
    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge <= MAX_IMAGE_EDGE) {
            bitmap
        } else {
            val ratio = MAX_IMAGE_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val HEADER_KEY = "x-goog-api-key"
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY = 429
        const val JPEG_QUALITY = 80
        val JSON = "application/json; charset=utf-8".toMediaType()
        val REQUIRED_FIELDS = listOf("original", "translated", "x", "y", "w", "h")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private const val MAX_IMAGE_EDGE = 1600

internal fun restoreScreenBounds(block: TextBlock, width: Int, height: Int): TextBlock {
    val ratio = (MAX_IMAGE_EDGE.toFloat() / maxOf(width, height)).coerceAtMost(1f)
    val scaleX = width.toFloat() / (width * ratio).toInt().coerceAtLeast(1)
    val scaleY = height.toFloat() / (height * ratio).toInt().coerceAtLeast(1)
    return block.copy(
        left = (block.left * scaleX).toInt(),
        top = (block.top * scaleY).toInt(),
        width = (block.width * scaleX).toInt().coerceAtLeast(1),
        height = (block.height * scaleY).toInt().coerceAtLeast(1),
    )
}
