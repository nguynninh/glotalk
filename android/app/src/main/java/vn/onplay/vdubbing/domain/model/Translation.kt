package vn.onplay.vdubbing.domain.model

/** Which translator produced a [Translation]. */
enum class TranslationEngine(val wireName: String) {
    ON_DEVICE("on-device"),
    CLOUD("cloud"),
}

data class Translation(
    val blocks: List<TextBlock>,
    val engine: TranslationEngine,
    val source: Language,
    val target: Language,
) {
    val plainText: String get() = blocks.joinToString("\n") { it.text }
}

/** Stable failure codes; mirrored by the AIDL contract and docs/INTEGRATION.md. */
enum class TranslationErrorCode(val value: Int) {
    ACCESSIBILITY_DISABLED(1),
    CAPTURE_FAILED(2),
    NO_TEXT_FOUND(3),
    TRANSLATION_FAILED(4),
    INVALID_API_KEY(5),
    CLOUD_QUOTA_EXCEEDED(6),
    OFFLINE(7),
    BUSY(8),
    ;

    companion object {
        fun fromValue(value: Int): TranslationErrorCode =
            entries.firstOrNull { it.value == value } ?: TRANSLATION_FAILED
    }
}

/** Every failure crossing a layer boundary, so callers switch on a type not a string. */
sealed class TranslationException(
    val code: TranslationErrorCode,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class AccessibilityDisabled : TranslationException(
        TranslationErrorCode.ACCESSIBILITY_DISABLED,
        "Chưa bật quyền Trợ năng cho VDubbing.",
    )

    class CaptureFailed(cause: Throwable? = null) : TranslationException(
        TranslationErrorCode.CAPTURE_FAILED,
        "Không chụp được màn hình.",
        cause,
    )

    class NoTextFound : TranslationException(
        TranslationErrorCode.NO_TEXT_FOUND,
        "Không nhận diện được chữ trên màn hình.",
    )

    class TranslationFailed(message: String, cause: Throwable? = null) :
        TranslationException(TranslationErrorCode.TRANSLATION_FAILED, message, cause)

    class InvalidApiKey : TranslationException(
        TranslationErrorCode.INVALID_API_KEY,
        "API key Gemini không hợp lệ.",
    )

    class QuotaExceeded : TranslationException(
        TranslationErrorCode.CLOUD_QUOTA_EXCEEDED,
        "Đã hết hạn mức Gemini.",
    )

    class Offline(cause: Throwable? = null) : TranslationException(
        TranslationErrorCode.OFFLINE,
        "Không có mạng.",
        cause,
    )

    class Busy : TranslationException(TranslationErrorCode.BUSY, "Đang dịch, thử lại sau.")
}
