package vn.onplay.vdubbing.presentation.feature.flashcard

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry

sealed interface FlashcardCommand {
    data object Back : FlashcardCommand
    data object Flip : FlashcardCommand
    data object Previous : FlashcardCommand
    data object TogglePlayback : FlashcardCommand
    data object Pause : FlashcardCommand
    data class Next(val count: Int) : FlashcardCommand
    data class ReadAloud(val text: String, val language: Language) : FlashcardCommand
    data class Bookmark(val entry: TranslationHistoryEntry) : FlashcardCommand
}
