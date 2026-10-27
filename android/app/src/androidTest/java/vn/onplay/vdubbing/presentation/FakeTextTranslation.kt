package vn.onplay.vdubbing.presentation

import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.repository.TranslationRepository

class FakeTextTranslation : TranslationRepository {
    var translateText: suspend (String, Language, Language) -> String = { text, _, _ -> text }
    override suspend fun detect(text: String) = Language.AUTO
    override suspend fun translate(lines: List<String>, source: Language, target: Language): List<String> =
        lines.map { translateText(it, source, target) }
}
