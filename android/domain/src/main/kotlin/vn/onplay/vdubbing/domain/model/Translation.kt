package vn.onplay.vdubbing.domain.model

data class Translation(
    val blocks: List<TextBlock>,
    val engine: TranslationEngine,
    val source: Language,
    val target: Language,
) {
    val plainText: String get() = blocks.joinToString("\n") { it.text }
}
