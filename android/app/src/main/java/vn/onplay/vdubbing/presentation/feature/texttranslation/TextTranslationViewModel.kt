package vn.onplay.vdubbing.presentation.feature.texttranslation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository
import vn.onplay.vdubbing.domain.repository.TranslationRepository
import vn.onplay.vdubbing.presentation.mapper.messageRes

data class TextTranslationUiState(
    val result: String? = null,
    val translating: Boolean = false,
    val error: Int? = null,
)

@HiltViewModel
class TextTranslationViewModel @Inject constructor(
    private val translation: TranslationRepository,
    private val history: TranslationHistoryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TextTranslationUiState())
    val state = _state.asStateFlow()

    suspend fun translate(text: String, composing: Boolean, source: Language, target: Language, onSaveFailure: suspend () -> Unit) {
        _state.value = TextTranslationUiState()
        if (text.isBlank() || composing) return
        _state.value = TextTranslationUiState(translating = true)
        try {
            delay(450)
            val translated = translation.translate(listOf(text), source, target).single()
            currentCoroutineContext().ensureActive()
            _state.value = TextTranslationUiState(result = translated)
            try {
                history.add(text, translated, source, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onSaveFailure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            _state.value = TextTranslationUiState(error = if (failure is TranslationException) failure.code.messageRes() else R.string.text_translation_error)
        }
    }
}
