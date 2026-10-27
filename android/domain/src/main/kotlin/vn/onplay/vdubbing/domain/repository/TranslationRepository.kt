package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.Language

interface TranslationRepository {
    suspend fun detect(text: String): Language
    suspend fun translate(lines: List<String>, source: Language, target: Language): List<String>
}
