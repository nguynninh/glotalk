package vn.onplay.vdubbing.presentation.feature.texttranslation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.speech.rememberReadAloud
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.texttranslation.components.TextTranslationContent

@Composable
fun TextTranslationUI(
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TextTranslationViewModel = hiltViewModel(),
) {
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val text = input.text
    var source by rememberSaveable { mutableStateOf(Language.CHINESE) }
    var target by rememberSaveable { mutableStateOf(Language.VIETNAMESE) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.text_feature_unavailable)
    // shortcut: imports and capture show a notice until their processing flows exist.
    val showUnavailable: () -> Unit = {
        scope.launch { snackbar.showSnackbar(unavailable) }
    }

    val translation by viewModel.state.collectAsStateWithLifecycle()
    val historySaveError = stringResource(R.string.history_save_error)
    val context = LocalContext.current
    LaunchedEffect(text, input.composition, source, target) {
        viewModel.translate(text, input.composition != null, source, target) {
            snackbar.showSnackbar(historySaveError)
        }
    }
    val speechUnavailable = stringResource(R.string.text_speech_unavailable)
    val readAloud = rememberReadAloud(listOf(text, input.composition, source, target)) {
        scope.launch { snackbar.showSnackbar(speechUnavailable) }
    }
    val copyText: (String) -> Unit = { content ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, content))
    }

    val colors = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = if (colors.surface.luminance() > 0.5f) Color.White else colors.surface,
        contentColor = colors.onSurface,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        TextTranslationContent(
            input = input,
            source = source,
            target = target,
            result = translation.result,
            translating = translation.translating,
            error = translation.error,
            onInputChange = { input = it },
            onSourceChange = { source = it },
            onTargetChange = { target = it },
            onReadAloud = readAloud,
            onCopyText = copyText,
            onUnavailable = showUnavailable,
            onHistory = onHistory,
            modifier = Modifier.padding(padding),
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TextTranslationUIPreview() {
    VDubbingTheme {
        TextTranslationContent(
            input = TextFieldValue("你好"),
            source = Language.CHINESE,
            target = Language.VIETNAMESE,
            result = "Xin chào",
            translating = false,
            error = null,
            onInputChange = {},
            onSourceChange = {},
            onTargetChange = {},
            onReadAloud = { _, _ -> },
            onCopyText = {},
            onUnavailable = {},
            onHistory = {},
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        )
    }
}
