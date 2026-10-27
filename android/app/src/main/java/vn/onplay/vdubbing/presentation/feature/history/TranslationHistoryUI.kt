package vn.onplay.vdubbing.presentation.feature.history

import vn.onplay.vdubbing.presentation.feature.flashcard.FlashcardEvent
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.preview.PreviewHistoryRepository
import vn.onplay.vdubbing.presentation.speech.rememberReadAloud
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.flashcard.FlashcardUI
import vn.onplay.vdubbing.presentation.feature.history.components.TranslationHistoryContent

@Composable
fun TranslationHistoryUI(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TranslationHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries = state.entries
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val background = if (colors.surface.luminance() > 0.5f) Color.White else colors.surface
    val snackbar = remember { SnackbarHostState() }
    val failure = stringResource(R.string.history_error)
    val speechFailure = stringResource(R.string.text_speech_unavailable)
    val readAloud = rememberReadAloud(reviewing) { scope.launch { snackbar.showSnackbar(speechFailure) } }
    LaunchedEffect(viewModel) { viewModel.onCommand(TranslationHistoryCommand.Init) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnBack by rememberUpdatedState(onBack)
    val currentReadAloud by rememberUpdatedState(readAloud)
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    TranslationHistoryEvent.Back -> currentOnBack()
                    TranslationHistoryEvent.Review -> reviewing = true
                    TranslationHistoryEvent.Failure -> scope.launch { snackbar.showSnackbar(failure) }
                    is TranslationHistoryEvent.ReadAloud -> currentReadAloud(event.text, event.language)
                }
            }
        }
    }
    if (reviewing && entries.isNotEmpty()) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            FlashcardUI(
                entries = entries,
                onEvent = { event ->
                    when (event) {
                        FlashcardEvent.Back -> reviewing = false
                        is FlashcardEvent.ReadAloud -> readAloud(event.text, event.language)
                        is FlashcardEvent.Bookmark -> viewModel.onCommand(
                            TranslationHistoryCommand.Bookmark(event.entry.id, !event.entry.bookmarked),
                        )
                    }
                },
                bookmarkEnabled = !state.busy,
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    TranslationHistoryContent(
        entries = entries,
        loading = state.loading,
        failed = state.failed,
        busy = state.busy,
        snackbar = snackbar,
        onCommand = viewModel::onCommand,
        modifier = modifier,
    )
}

@Preview(name = "TranslationHistoryUI light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "TranslationHistoryUI dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Composable
private fun TranslationHistoryUIPreview() {
    VDubbingTheme {
        TranslationHistoryContent(
            entries = PreviewHistoryRepository.sampleEntries,
            loading = false, failed = false, busy = false,
            snackbar = remember { SnackbarHostState() },
            onCommand = {},
        )
    }
}
