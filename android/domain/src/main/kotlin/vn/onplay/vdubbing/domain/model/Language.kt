package vn.onplay.vdubbing.domain.model

/**
 * Languages this app offers. Codes are the ones ML Kit Translate understands;
 * [AUTO] and [UNKNOWN] exist only on the source side.
 */
enum class Language(val code: String, val label: String, val script: Script) {
    AUTO("auto", "Tự nhận diện", Script.LATIN),
    ENGLISH("en", "English", Script.LATIN),
    VIETNAMESE("vi", "Tiếng Việt", Script.LATIN),
    JAPANESE("ja", "日本語", Script.JAPANESE),
    KOREAN("ko", "한국어", Script.KOREAN),
    CHINESE("zh", "中文", Script.CHINESE),
    UNKNOWN("und", "Không rõ", Script.LATIN),
    ;

    val isTranslatable: Boolean get() = this != AUTO && this != UNKNOWN

    companion object {
        /** Offered as translation targets, in picker order. */
        val TARGETS: List<Language> = listOf(VIETNAMESE, ENGLISH, JAPANESE, KOREAN, CHINESE)

        /**
         * Offered as source languages: everything we can name, plus detection.
         * It is the same list a target may be, because reading a page in a
         * language and writing into it are symmetric choices.
         */
        val SOURCES: List<Language> = listOf(AUTO) + TARGETS

        fun fromCode(code: String?): Language {
            val base = code?.substringBefore('-')?.lowercase()
            return entries.firstOrNull { it.code == base } ?: UNKNOWN
        }
    }
}
