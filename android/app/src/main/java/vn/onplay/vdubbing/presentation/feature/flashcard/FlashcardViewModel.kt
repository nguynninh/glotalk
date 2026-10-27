package vn.onplay.vdubbing.presentation.feature.flashcard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow

@HiltViewModel
class FlashcardViewModel @Inject constructor(private val savedState: SavedStateHandle) : ViewModel() {
    val index = savedState.getStateFlow("index", 0)
    val revealed = savedState.getStateFlow("revealed", false)
    val playing = savedState.getStateFlow("playing", false)

    private val _events = Channel<FlashcardEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onCommand(command: FlashcardCommand) {
        when (command) {
            FlashcardCommand.Back -> _events.trySend(FlashcardEvent.Back)
            FlashcardCommand.Flip -> flip()
            FlashcardCommand.Previous -> previous()
            FlashcardCommand.TogglePlayback -> togglePlayback()
            FlashcardCommand.Pause -> pause()
            is FlashcardCommand.Next -> next(command.count)
            is FlashcardCommand.ReadAloud -> _events.trySend(FlashcardEvent.ReadAloud(command.text, command.language))
            is FlashcardCommand.Bookmark -> _events.trySend(FlashcardEvent.Bookmark(command.entry))
        }
    }

    private fun flip() { savedState["revealed"] = !revealed.value }
    private fun togglePlayback() { savedState["playing"] = !playing.value }
    private fun pause() { savedState["playing"] = false }

    private fun previous() {
        savedState["index"] = (index.value - 1).coerceAtLeast(0)
        savedState["revealed"] = false
    }

    private fun next(count: Int) {
        savedState["revealed"] = false
        if (index.value < count - 1) savedState["index"] = index.value + 1 else pause()
    }

    suspend fun advancePlayback(count: Int) {
        if (!playing.value) return
        delay(3000)
        if (!revealed.value) flip() else next(count)
    }
}
