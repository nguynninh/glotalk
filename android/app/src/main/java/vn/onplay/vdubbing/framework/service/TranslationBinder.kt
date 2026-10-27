package vn.onplay.vdubbing.framework.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.ITranslationCallback
import vn.onplay.vdubbing.ITranslationService
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationErrorCode
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.SettingsRepository

internal class TranslationBinder(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val requests: TranslationRequestRunner,
    private val lastResult: () -> String,
    private val accessibilityReady: () -> Boolean,
    private val hideOverlay: () -> Unit,
    private val setAutoTranslate: (Boolean) -> Unit,
) : ITranslationService.Stub() {
    override fun translateNow(sourceLang: String?, targetLang: String?, callback: ITranslationCallback?) {
        val source = Language.fromCode(sourceLang).takeIf { it in Language.SOURCES }
            ?: settings.sourceLanguage
        val target = Language.fromCode(targetLang).takeIf { it in Language.TARGETS }
            ?: settings.targetLanguage
        requests.translate(source, target) { result ->
            result.onSuccess { runCatching { callback?.onResult(it.toJson()) } }
                .onFailure { error ->
                    val code = (error as? TranslationException)?.code ?: TranslationErrorCode.TRANSLATION_FAILED
                    runCatching { callback?.onError(code.value, error.message.orEmpty()) }
                }
        }
    }

    override fun setAutoTranslate(enabled: Boolean, sourceLang: String?, targetLang: String?) {
        scope.launch {
            Language.fromCode(sourceLang).takeIf { it in Language.SOURCES }?.let { settings.sourceLanguage = it }
            Language.fromCode(targetLang).takeIf { it in Language.TARGETS }?.let { settings.targetLanguage = it }
            setAutoTranslate(enabled)
        }
    }

    override fun getLastResult(): String = lastResult()

    override fun isAccessibilityReady(): Boolean = accessibilityReady()

    override fun hideOverlay() {
        scope.launch { hideOverlay.invoke() }
    }
}
