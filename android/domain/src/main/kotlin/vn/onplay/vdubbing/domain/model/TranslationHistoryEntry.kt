package vn.onplay.vdubbing.domain.model

data class TranslationHistoryEntry(
    val id: Long,
    val original: String,
    val translated: String,
    val source: Language,
    val target: Language,
    val createdAt: Long,
    val bookmarked: Boolean,
)
