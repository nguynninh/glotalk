package vn.onplay.vdubbing.presentation.feature.flashcard

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.flashcard.components.FlashcardContent

@Composable
internal fun FlashcardUI(
    entries: List<TranslationHistoryEntry>,
    onEvent: (FlashcardEvent) -> Unit,
    bookmarkEnabled: Boolean,
    modifier: Modifier = Modifier,
    viewModel: FlashcardViewModel = hiltViewModel(),
) {
    val index by viewModel.index.collectAsStateWithLifecycle()
    val revealed by viewModel.revealed.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    LaunchedEffect(playing, index, revealed, entries.size) { viewModel.advancePlayback(entries.size) }
    DisposableEffect(viewModel) { onDispose { viewModel.onCommand(FlashcardCommand.Pause) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { currentOnEvent(it) }
        }
    }
    FlashcardContent(entries, index, revealed, playing, bookmarkEnabled, viewModel::onCommand, modifier)
}

@Preview(name = "Flashcard light", showBackground = true)
@Preview(name = "Flashcard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FlashcardUIPreview() {
    VDubbingTheme {
        FlashcardContent(
            index = 0, revealed = false, playing = false,
            entries = listOf(TranslationHistoryEntry(
                id = 1,
                original = "你好",
                translated = "Xin chào",
                source = Language.CHINESE,
                target = Language.VIETNAMESE,
                createdAt = 0,
                bookmarked = true,
            )),
            onCommand = {},
            bookmarkEnabled = true,
        )
    }
}
