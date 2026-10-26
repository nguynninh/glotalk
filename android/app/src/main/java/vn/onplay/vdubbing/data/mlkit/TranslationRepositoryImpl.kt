package vn.onplay.vdubbing.data.mlkit

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
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

    override suspend fun detect(text: String): Language = withContext(Dispatchers.Default) {
        val code = runCatching { identifier.identifyLanguage(text).await() }.getOrDefault("und")
        Language.fromCode(code)
    }

    override suspend fun translate(
        lines: List<String>,
        source: Language,
        target: Language,
    ): List<String> {
        if (source == target || !source.isTranslatable || !target.isTranslatable) return lines

        val translator = translatorFor(source, target)
        return withContext(Dispatchers.Default) {
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (e: Exception) {
                throw TranslationException.TranslationFailed(
                    "Không tải được model dịch ${source.code}→${target.code} (cần mạng lần đầu).",
                    e,
                )
            }

            lines.map { line ->
                runCatching { translator.translate(line).await() }.getOrDefault(line)
            }
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
}
