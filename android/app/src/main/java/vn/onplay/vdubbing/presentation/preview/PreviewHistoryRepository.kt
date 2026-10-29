package vn.onplay.vdubbing.presentation.preview

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository

internal object PreviewHistoryRepository : TranslationHistoryRepository {
    val sampleEntries = listOf(
        TranslationHistoryEntry(1, "你好", "Xin chào", Language.CHINESE, Language.VIETNAMESE, 0, true),
        TranslationHistoryEntry(2, "Thank you", "Cảm ơn", Language.ENGLISH, Language.VIETNAMESE, 0, false),
    )

    override suspend fun entries() = sampleEntries
    override suspend fun add(original: String, translated: String, source: Language, target: Language, replaceId: Long?): Long? = null
    override suspend fun setBookmarked(id: Long, bookmarked: Boolean) = Unit
    override suspend fun delete(ids: Set<Long>) = Unit
}
