package vn.onplay.vdubbing.domain.model

/**
 * The language the app's own interface is drawn in. Separate from [Language],
 * which is about what gets translated on screen.
 *
 * [SYSTEM] has no tag: it follows the device's language. Any other entry pins
 * one locale, and the domain keeps only the BCP-47 tag — display names are the
 * presentation layer's job, since they need resources.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    VIETNAMESE("vi"),
    ENGLISH("en"),
    JAPANESE("ja"),
    CHINESE_SIMPLIFIED("zh-CN"),
    KOREAN("ko"),
    ;

    companion object {
        /** Offered in the picker, in order, system first. */
        val CHOICES: List<AppLanguage> = entries

        /**
         * Maps anything the platform can hand us back — `"zh-CN"`, `"zh_Hans_CN"`,
         * a raw `values-zh-rCN` name, or a full tag with script/region — to the
         * closest choice we actually ship.
         */
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            val base = tag.replace('_', '-').substringBefore('-').lowercase()
            if (base == "zh") return CHINESE_SIMPLIFIED
            return entries.firstOrNull {
                it.tag != null && it.tag.substringBefore('-').lowercase() == base
            } ?: SYSTEM
        }
    }
}
