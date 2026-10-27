package vn.onplay.vdubbing.presentation.uimodel

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * A one-shot user-facing notice; the ViewModel clears it once shown.
 *
 * It carries a resource id rather than a string: the ViewModel outlives an
 * activity recreation (including the one a language change causes), so text
 * built here would be stuck in the language it was built in.
 */
data class UiMessage(val content: UiContent, val isError: Boolean = false)

sealed interface UiContent {
    data class Text(@StringRes val id: Int, val args: List<UiArg> = emptyList()) : UiContent

    /** [count] feeds the plural rule; the same number is also the first format argument. */
    data class Quantity(
        @PluralsRes val id: Int,
        val count: Int,
        val args: List<UiArg> = emptyList(),
    ) : UiContent

    /** Only for text that is not ours, such as a message from a calling app. */
    data class Raw(val text: String) : UiContent
}

sealed interface UiArg {
    data class Literal(val text: String) : UiArg
    data class Res(@StringRes val id: Int) : UiArg
    data class Num(val value: Int) : UiArg
}
