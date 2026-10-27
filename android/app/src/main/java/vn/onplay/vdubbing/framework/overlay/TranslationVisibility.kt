package vn.onplay.vdubbing.framework.overlay

import vn.onplay.vdubbing.domain.model.TextBlock

private val address = Regex("(?:https?://|www\\.|[\\p{L}\\d-]+\\.[a-z]{2,}/)", RegexOption.IGNORE_CASE)

internal fun shouldDrawTranslation(block: TextBlock): Boolean {
    val original = block.original.trim()
    val translated = block.text.trim()
    if (translated.isEmpty() || translated == original) return false
    if (original.isNotEmpty() && original.none { it.isLetter() }) return false
    if (address.containsMatchIn(original)) return false
    // Tiny, uncertain Latin fragments are often toolbar or calendar icons misread as letters.
    if (original.length <= 2 && original.all { it in 'A'..'Z' } && (block.confidence ?: 1.0) < 0.7) return false
    return true
}
