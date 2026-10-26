package vn.onplay.vdubbing.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Turns a resource-backed [UiContent] into text in the language the UI is being
 * drawn in right now. Called from the composable, so a message that is already
 * on screen follows a language change instead of keeping its old wording.
 */
@Composable
fun UiContent.resolve(): String = when (this) {
    is UiContent.Raw -> text
    is UiContent.Text -> stringResource(id, *args.map { it.resolve() }.toTypedArray())
    is UiContent.Quantity ->
        // The plural rule picks the form; the count is still a format argument.
        pluralStringResource(id, count, count, *args.map { it.resolve() }.toTypedArray())
}

@Composable
private fun UiArg.resolve(): Any = when (this) {
    is UiArg.Literal -> text
    is UiArg.Res -> stringResource(id)
    is UiArg.Num -> value
}
