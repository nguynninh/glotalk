package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry

interface TranslationHistoryRepository {
    suspend fun entries(): List<TranslationHistoryEntry>
    // Only a newly created entry can be replaced; existing history and bookmarks stay intact.
    suspend fun add(original: String, translated: String, source: Language, target: Language, replaceId: Long? = null): Long?
    suspend fun setBookmarked(id: Long, bookmarked: Boolean)
    suspend fun delete(ids: Set<Long>)
}
