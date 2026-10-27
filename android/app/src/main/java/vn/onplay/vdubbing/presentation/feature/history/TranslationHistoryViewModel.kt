package vn.onplay.vdubbing.presentation.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository

data class TranslationHistoryUiState(
    val entries: List<TranslationHistoryEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
)

@HiltViewModel
class TranslationHistoryViewModel @Inject constructor(private val history: TranslationHistoryRepository) : ViewModel() {
    private val _state = MutableStateFlow(TranslationHistoryUiState())
    val state = _state.asStateFlow()

    private val _events = Channel<TranslationHistoryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onCommand(command: TranslationHistoryCommand) {
        when (command) {
            TranslationHistoryCommand.Init, TranslationHistoryCommand.Retry -> reload()
            TranslationHistoryCommand.Back -> _events.trySend(TranslationHistoryEvent.Back)
            TranslationHistoryCommand.Review -> {
                if (_state.value.entries.isNotEmpty() && !_state.value.busy) {
                    _events.trySend(TranslationHistoryEvent.Review)
                }
            }
            is TranslationHistoryCommand.ReadAloud ->
                _events.trySend(TranslationHistoryEvent.ReadAloud(command.text, command.language))
            is TranslationHistoryCommand.Bookmark -> runAction { history.setBookmarked(command.id, command.bookmarked) }
            is TranslationHistoryCommand.Delete -> runAction { history.delete(command.ids) }
        }
    }

    private fun reload() {
        if (_state.value.busy) return
        _state.update { it.copy(loading = true, busy = true) }
        viewModelScope.launch {
            try {
                readEntries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(failed = true) }
            } finally {
                _state.update { it.copy(loading = false, busy = false) }
            }
        }
    }

    private suspend fun readEntries() {
        val entries = history.entries()
        _state.update { it.copy(entries = entries, failed = false) }
    }

    private fun runAction(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                action()
                readEntries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _events.send(TranslationHistoryEvent.Failure)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
