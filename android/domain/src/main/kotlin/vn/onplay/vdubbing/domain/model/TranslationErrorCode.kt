package vn.onplay.vdubbing.domain.model

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
