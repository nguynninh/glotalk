package vn.onplay.vdubbing.data.mlkit

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.TranslationRepository


/**
 * ML Kit language identification and on-device translation.
 *
 * Translators are per language pair and need a one-off model download, so they
 * are cached. Calls are sequential: ML Kit does not document a single Translator
 * as safe for concurrent use, and there is no batch API.
 */
class TranslationRepositoryImpl : TranslationRepository {

    private val identifier by lazy { LanguageIdentification.getClient() }
    private val translators = HashMap<String, Translator>()
    private val translationMutex = Mutex()

    override suspend fun detect(text: String): Language = withContext(Dispatchers.Default) {
        val code = try {
            identifier.identifyLanguage(text).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "language detection failed", failure)
            "und"
        }
        Language.fromCode(code).also { Log.i(TAG, "detected $code -> $it") }
    }

    override suspend fun translate(
        lines: List<String>,
        source: Language,
        target: Language,
    ): List<String> = translationMutex.withLock {
        if (source == target || !source.isTranslatable || !target.isTranslatable) return@withLock lines

        val translator = translatorFor(source, target)
        withContext(Dispatchers.Default) {
            try {
                // A cancelled caller must not release the mutex while ML Kit is still using the client.
                withContext(NonCancellable) {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                }
                ensureActive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw TranslationException.TranslationFailed(
                    "Không tải được model dịch ${source.code}→${target.code} (cần mạng lần đầu).",
                    e,
                )
            }

            var failed = 0
            val translated = lines.map { line ->
                ensureActive()
                try {
                    withContext(NonCancellable) { translator.translate(line).await() }
                        .also { ensureActive() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    line
                }
            }
            // Every failed line falls back to its own text, which the overlay
            // then skips — so a model that never loaded looks exactly like a
            // screen with nothing to translate. Say so.
            if (failed > 0) Log.w(TAG, "$failed/${lines.size} lines fell back to the original")
            if (failed == lines.size) {
                throw TranslationException.TranslationFailed(
                    "Không dịch được dòng nào ${source.code}→${target.code}.",
                )
            }
            translated
        }
    }

    private fun translatorFor(source: Language, target: Language): Translator =
        translators.getOrPut("${source.code}>${target.code}") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source.code)
                    .setTargetLanguage(target.code)
                    .build(),
            )
        }

    fun close() {
        runCatching { identifier.close() }
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    private companion object {
        const val TAG = "VDubbingTranslate"
    }
}
