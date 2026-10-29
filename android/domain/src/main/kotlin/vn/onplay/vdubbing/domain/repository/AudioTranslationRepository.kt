package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.AudioTranslation
import vn.onplay.vdubbing.domain.model.Language

interface AudioTranslationRepository {
    suspend fun translate(
        wav: ByteArray,
        source: Language,
        target: Language,
        context: List<AudioTranslation>,
    ): AudioTranslation?
}
