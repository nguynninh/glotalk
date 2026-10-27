package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Translation

/** Cloud does OCR and translation in one call, so it returns finished blocks. */
interface CloudTranslationRepository {
    /** False when the user has not enabled cloud or has no key. */
    fun isConfigured(): Boolean

    suspend fun translate(
        screen: CapturedScreen,
        source: Language,
        target: Language,
    ): Translation

    suspend fun verifyKey(key: String)
}
