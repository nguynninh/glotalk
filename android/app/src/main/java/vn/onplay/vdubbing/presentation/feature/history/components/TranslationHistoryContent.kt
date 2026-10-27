package vn.onplay.vdubbing.presentation.feature.history.components

import vn.onplay.vdubbing.presentation.feature.history.TranslationHistoryCommand
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.presentation.preview.PreviewHistoryRepository
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun TranslationHistoryContent(
    entries: List<TranslationHistoryEntry>,
    loading: Boolean,
    failed: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onCommand: (TranslationHistoryCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Set<Long>?>(null) }
    val colors = MaterialTheme.colorScheme
    val background = if (colors.surface.luminance() > 0.5f) Color.White else colors.surface
    val failure = stringResource(R.string.history_error)
    BackHandler { onCommand(TranslationHistoryCommand.Back) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onCommand(TranslationHistoryCommand.Back) }) {
                    Icon(painterResource(R.drawable.ic_overlay_chevron), stringResource(R.string.text_back),
                        Modifier.rotate(180f))
                }
                Spacer(Modifier.width(48.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.text_history), fontSize = 20.sp)
                }
                IconButton(
                    onClick = { onCommand(TranslationHistoryCommand.Review) },
                    enabled = entries.isNotEmpty() && !busy,
                ) {
                    Icon(painterResource(R.drawable.ic_history_cards), stringResource(R.string.history_review))
                }
                IconButton(
                    onClick = { pendingDelete = entries.map { it.id }.toSet() },
                    enabled = !busy && entries.isNotEmpty(),
                ) {
                    Icon(painterResource(R.drawable.ic_history_delete), stringResource(R.string.history_delete),
                        tint = if (entries.isNotEmpty()) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            failed -> Column(Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(failure)
                TextButton(onClick = { onCommand(TranslationHistoryCommand.Retry) }, enabled = !busy) {
                    Text(stringResource(R.string.history_retry))
                }
            }
            entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.history_empty))
            }
            else -> {
                val zone = ZoneId.systemDefault()
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: Locale.getDefault()
                val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
                val groups = entries.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    groups.forEach { (day, records) ->
                        item(key = "date:$day") {
                            Text(day.format(dateFormat), Modifier.padding(top = 20.dp, bottom = 8.dp), fontSize = 16.sp)
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        items(records, key = { it.id }) { entry ->
                            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.original, Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 18.sp)
                                    IconButton(onClick = { onCommand(TranslationHistoryCommand.ReadAloud(entry.original, entry.source)) }) {
                                        Icon(painterResource(R.drawable.ic_text_speak), stringResource(R.string.text_speak), Modifier.size(22.dp))
                                    }
                                    IconToggleButton(checked = entry.bookmarked, enabled = !busy,
                                        onCheckedChange = { checked -> onCommand(TranslationHistoryCommand.Bookmark(entry.id, checked)) }) {
                                        Icon(painterResource(if (entry.bookmarked) R.drawable.ic_history_bookmarked else R.drawable.ic_history_bookmark),
                                            stringResource(if (entry.bookmarked) R.string.history_unbookmark else R.string.history_bookmark),
                                            Modifier.size(22.dp),
                                            tint = if (entry.bookmarked) Color(0xFF203A83) else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                Text(entry.translated, fontSize = 16.sp)
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete)) },
            text = { Text(stringResource(R.string.history_delete_confirmation, ids.size)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onCommand(TranslationHistoryCommand.Delete(ids))
                }) { Text(stringResource(R.string.history_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.history_cancel)) } },
        )
    }
}

@Preview(name = "TranslationHistoryUI light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "TranslationHistoryUI dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Composable
private fun TranslationHistoryContentPreview() {
    VDubbingTheme {
        TranslationHistoryContent(
            entries = PreviewHistoryRepository.sampleEntries,
            loading = false, failed = false, busy = false,
            snackbar = remember { SnackbarHostState() },
            onCommand = {},
        )
    }
}
