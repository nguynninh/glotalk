package vn.onplay.vdubbing.presentation.feature.history

import vn.onplay.vdubbing.domain.model.Language

sealed interface TranslationHistoryCommand {
    data object Init : TranslationHistoryCommand
    data object Back : TranslationHistoryCommand
    data object Review : TranslationHistoryCommand
    data object Retry : TranslationHistoryCommand
    data class ReadAloud(val text: String, val language: Language) : TranslationHistoryCommand
    data class Bookmark(val id: Long, val bookmarked: Boolean) : TranslationHistoryCommand
    data class Delete(val ids: Set<Long>) : TranslationHistoryCommand
}
