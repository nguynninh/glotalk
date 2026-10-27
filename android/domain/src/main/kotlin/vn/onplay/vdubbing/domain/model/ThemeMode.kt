package vn.onplay.vdubbing.domain.model

/**
 * What the user picked under "Giao diện". [SYSTEM] follows the device setting;
 * the other two pin the app so it stays readable on a phone whose system theme
 * does not match the screen being translated.
 *
 * No display label here: the domain has no Context, so the presentation layer
 * maps these to strings.
 */
enum class ThemeMode(val code: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        val OPTIONS: List<ThemeMode> = listOf(SYSTEM, LIGHT, DARK)

        fun fromCode(code: String?): ThemeMode =
            entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}
