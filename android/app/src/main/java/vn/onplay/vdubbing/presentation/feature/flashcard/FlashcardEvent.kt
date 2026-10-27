package vn.onplay.vdubbing.presentation.feature.flashcard

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry

sealed interface FlashcardEvent {
    data object Back : FlashcardEvent
    data class ReadAloud(val text: String, val language: Language) : FlashcardEvent
    data class Bookmark(val entry: TranslationHistoryEntry) : FlashcardEvent
}
