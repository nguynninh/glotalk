package vn.onplay.vdubbing.presentation.feature.flashcard

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FlashcardViewModelTest {
    @Test fun playbackRevealsThenAdvancesAndStopsOnLastCard() = runTest {
        val model = FlashcardViewModel(SavedStateHandle())
        model.onCommand(FlashcardCommand.TogglePlayback)
        model.advancePlayback(2)
        assertTrue(model.revealed.value)
        assertEquals(0, model.index.value)
        model.advancePlayback(2)
        assertFalse(model.revealed.value)
        assertEquals(1, model.index.value)
        model.advancePlayback(2)
        model.advancePlayback(2)
        assertFalse(model.playing.value)
        assertEquals(1, model.index.value)
    }

    @Test fun previousDoesNotGoBelowZeroAndHidesTheAnswer() {
        val model = FlashcardViewModel(SavedStateHandle())
        model.onCommand(FlashcardCommand.Flip)
        model.onCommand(FlashcardCommand.Previous)
        assertEquals(0, model.index.value)
        assertFalse(model.revealed.value)
        model.onCommand(FlashcardCommand.Next(2))
        model.onCommand(FlashcardCommand.Flip)
        model.onCommand(FlashcardCommand.Previous)
        assertEquals(0, model.index.value)
        assertFalse(model.revealed.value)
    }

    @Test fun backAndSpeechCommandsAreObservedOnceAndDoNotAdvanceTheCard() = runTest {
        val model = FlashcardViewModel(SavedStateHandle())
        model.onCommand(FlashcardCommand.Back)
        assertEquals(FlashcardEvent.Back, model.events.first())
        model.onCommand(FlashcardCommand.ReadAloud("Hello", vn.onplay.vdubbing.domain.model.Language.ENGLISH))
        assertEquals(FlashcardEvent.ReadAloud("Hello", vn.onplay.vdubbing.domain.model.Language.ENGLISH), model.events.first())
        assertEquals(0, model.index.value)
    }

}
