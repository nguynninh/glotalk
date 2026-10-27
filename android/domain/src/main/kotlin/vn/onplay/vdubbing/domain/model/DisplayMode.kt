package vn.onplay.vdubbing.domain.model

/**
 * How a translated line is drawn over the original screen.
 *
 * [PARALLEL] keeps the original words visible and puts the translation directly
 * under them; [REPLACE] paints a plate the colour of the surrounding page over
 * the original and writes the translation in its place. Replace is the default:
 * it is the only mode that stays readable when the two languages use different
 * line lengths, which is most of the time.
 */
enum class DisplayMode(val code: String) {
    REPLACE("replace"),
    PARALLEL("parallel"),
    ;

    companion object {
        /** Offered in the panel, in order. */
        val CHOICES: List<DisplayMode> = listOf(PARALLEL, REPLACE)

        fun fromCode(code: String?): DisplayMode =
            entries.firstOrNull { it.code == code } ?: REPLACE
    }
}
