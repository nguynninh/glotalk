package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry

interface TranslationHistoryRepository {
    suspend fun entries(): List<TranslationHistoryEntry>
    suspend fun add(original: String, translated: String, source: Language, target: Language)
    suspend fun setBookmarked(id: Long, bookmarked: Boolean)
    suspend fun delete(ids: Set<Long>)
}
