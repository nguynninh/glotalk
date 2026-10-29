package vn.onplay.vdubbing.presentation.feature.texttranslation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository
import vn.onplay.vdubbing.domain.repository.TranslationRepository
import vn.onplay.vdubbing.domain.time.Countdown
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
    private val countdown: Countdown,
) : ViewModel() {
    private val _state = MutableStateFlow(TextTranslationUiState())
    val state = _state.asStateFlow()
    private class HistorySession {
        val mutex = Mutex()
        var entryId: Long? = null
    }
    private var historySession = HistorySession()

    suspend fun translate(text: String, composing: Boolean, source: Language, target: Language, onSaveFailure: suspend () -> Unit) {
        _state.value = TextTranslationUiState()
        if (text.isEmpty()) historySession = HistorySession()
        if (text.isBlank() || composing) return
        val session = historySession
        _state.value = TextTranslationUiState(translating = true)
        try {
            countdown.await(450)
            val translated = translation.translate(listOf(text), source, target).single()
            currentCoroutineContext().ensureActive()
            _state.value = TextTranslationUiState(result = translated)
            try {
                // Finish tracking the committed row even when typing cancels this translation.
                withContext(NonCancellable) {
                    session.mutex.withLock {
                        session.entryId = history.add(text, translated, source, target, session.entryId)
                    }
                }
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
