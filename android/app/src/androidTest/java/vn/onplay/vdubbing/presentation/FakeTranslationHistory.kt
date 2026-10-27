package vn.onplay.vdubbing.presentation

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository

class FakeTranslationHistory : TranslationHistoryRepository {
    val records = mutableListOf<TranslationHistoryEntry>()
    override suspend fun entries() = records.toList()
    override suspend fun add(original: String, translated: String, source: Language, target: Language) {
        records.add(0, TranslationHistoryEntry(System.nanoTime(), original, translated, source, target, System.currentTimeMillis(), false))
    }
    override suspend fun setBookmarked(id: Long, bookmarked: Boolean) {
        val index = records.indexOfFirst { it.id == id }
        records[index] = records[index].copy(bookmarked = bookmarked)
    }
    override suspend fun delete(ids: Set<Long>) { records.removeAll { it.id in ids } }
}
