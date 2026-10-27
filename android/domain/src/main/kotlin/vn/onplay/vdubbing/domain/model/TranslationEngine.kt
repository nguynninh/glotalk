package vn.onplay.vdubbing.domain.model

/** Which translator produced a [Translation]. */
enum class TranslationEngine(val wireName: String) {
    ON_DEVICE("on-device"),
    CLOUD("cloud"),
}
