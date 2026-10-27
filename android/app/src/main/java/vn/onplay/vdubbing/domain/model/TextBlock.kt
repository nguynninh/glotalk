package vn.onplay.vdubbing.domain.model

/**
 * A line or paragraph of text found on screen.
 *
 * [left]..[height] are pixel coordinates in the captured image. No android.graphics
 * types: the domain layer stays free of the platform so it can be unit-tested.
 */
data class TextBlock(
    val original: String,
    val translated: String,
    val language: Language,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** Mean OCR confidence in 0..1, or null when the recognizer reported none. */
    val confidence: Double? = null,
    val lineCount: Int = 1,
) {
    val text: String get() = translated.ifBlank { original }
}
