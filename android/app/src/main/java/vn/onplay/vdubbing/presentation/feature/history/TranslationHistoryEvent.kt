package vn.onplay.vdubbing.presentation.feature.history

import vn.onplay.vdubbing.domain.model.Language

sealed interface TranslationHistoryEvent {
    data object Back : TranslationHistoryEvent
    data object Review : TranslationHistoryEvent
    data object Failure : TranslationHistoryEvent
    data class ReadAloud(val text: String, val language: Language) : TranslationHistoryEvent
}
