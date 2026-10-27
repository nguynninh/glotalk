package vn.onplay.vdubbing.presentation.feature.flashcard.components

import vn.onplay.vdubbing.presentation.feature.flashcard.FlashcardCommand
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun FlashcardContent(
    entries: List<TranslationHistoryEntry>,
    index: Int,
    revealed: Boolean,
    playing: Boolean,
    bookmarkEnabled: Boolean,
    onCommand: (FlashcardCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val entry = entries[index.coerceIn(entries.indices)]
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > 0.5f
    val cardColor = if (light) Color.White else colors.surface
    val background = if (light) Color(0xFFF0F1F6) else colors.surfaceContainer
    BackHandler { onCommand(FlashcardCommand.Back) }
    Column(modifier.fillMaxSize().background(background)) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(cardColor),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onCommand(FlashcardCommand.Back) }) {
                Icon(painterResource(R.drawable.ic_overlay_chevron), stringResource(R.string.text_back), Modifier.rotate(180f))
            }
            Spacer(Modifier.width(48.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("☷ ${index + 1}/${entries.size}",
                    Modifier.background(colors.surfaceContainer, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
                    fontSize = 20.sp)
            }
            // shortcut: writing practice and review settings are visual placeholders until their flows are requested.
            IconButton(onClick = {}, enabled = false) {
                Icon(painterResource(R.drawable.ic_card_pen), stringResource(R.string.cards_writing), tint = colors.onSurface)
            }
            IconButton(onClick = {}, enabled = false) {
                Icon(painterResource(R.drawable.ic_overlay_settings), stringResource(R.string.cards_settings), tint = colors.onSurface)
            }
        }
        Surface(
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 24.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = cardColor,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconToggleButton(checked = entry.bookmarked, enabled = bookmarkEnabled, onCheckedChange = { onCommand(FlashcardCommand.Bookmark(entry)) }) {
                        Icon(painterResource(if (entry.bookmarked) R.drawable.ic_card_heart_filled else R.drawable.ic_card_heart),
                            stringResource(if (entry.bookmarked) R.string.history_unbookmark else R.string.history_bookmark),
                            tint = if (entry.bookmarked) Color(0xFFDF252B) else colors.onSurface)
                    }
                    IconButton(onClick = {
                        onCommand(FlashcardCommand.ReadAloud(
                            if (revealed) entry.translated else entry.original,
                            if (revealed) entry.target else entry.source,
                        ))
                    }) {
                        Icon(painterResource(R.drawable.ic_text_speak), stringResource(R.string.text_speak))
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f).clickable(
                    onClickLabel = stringResource(R.string.cards_flip),
                    onClick = { onCommand(FlashcardCommand.Flip) },
                ).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Text(if (revealed) entry.translated else entry.original,
                        color = if (revealed) colors.onSurface else Color(0xFFDF252B),
                        fontSize = if (revealed) 28.sp else 48.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Spacer(Modifier.height(48.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ReviewButton(R.drawable.ic_card_undo, R.string.cards_previous, colors.onSurface, enabled = index > 0) { onCommand(FlashcardCommand.Previous) }
            ReviewButton(if (playing) R.drawable.ic_card_pause else R.drawable.ic_card_play,
                if (playing) R.string.cards_pause else R.string.cards_play, Color(0xFF233D80)) { onCommand(FlashcardCommand.TogglePlayback) }
            ReviewButton(R.drawable.ic_card_wrong, R.string.cards_wrong, Color(0xFFE52930)) { onCommand(FlashcardCommand.Next(entries.size)) }
            val unsure = stringResource(R.string.cards_unsure)
            FilledIconButton(onClick = { onCommand(FlashcardCommand.Next(entries.size)) }, modifier = Modifier.size(48.dp).semantics { contentDescription = unsure }, colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = cardColor, contentColor = Color(0xFFFFBA18))) {
                Text("?", fontSize = 30.sp)
            }
            ReviewButton(R.drawable.ic_card_check, R.string.cards_known, Color(0xFF299367)) { onCommand(FlashcardCommand.Next(entries.size)) }
        }
    }
}

@Preview(name = "Flashcard light", showBackground = true)
@Preview(name = "Flashcard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FlashcardContentPreview() {
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
