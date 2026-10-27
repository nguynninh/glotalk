package vn.onplay.vdubbing.presentation.designsystem.components.bottomsheet

import vn.onplay.vdubbing.presentation.designsystem.help.translationLabel
import vn.onplay.vdubbing.presentation.designsystem.help.flag
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSelectionSheet(
    language: Language,
    onConfirm: (Language) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by rememberSaveable(language) { mutableStateOf(language) }
    var query by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > 0.5f
    val accent = if (light) Color(0xFF2E4075) else colors.primary
    val searchLabel = stringResource(R.string.text_choose_language)
    val search = query.trim()
    val options = Language.TARGETS.map { it to it.translationLabel() }
        .filter { (option, label) ->
            label.contains(search, ignoreCase = true) ||
                option.label.contains(search, ignoreCase = true) ||
                option.code.contains(search, ignoreCase = true)
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (light) Color.White else colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.7f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_language_search), null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = searchLabel },
                    textStyle = TextStyle(color = colors.onSurface, fontSize = 18.sp),
                    cursorBrush = SolidColor(accent),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(searchLabel, color = colors.onSurfaceVariant, fontSize = 18.sp)
                            field()
                        }
                    },
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
                if (options.isEmpty()) {
                    item { Text(stringResource(R.string.text_language_not_found), Modifier.padding(16.dp)) }
                }
                items(options, key = { it.first.code }) { (option, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = pending == option,
                            role = Role.RadioButton,
                            onClick = { pending = option },
                        ).heightIn(min = 50.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(colors.surfaceContainer).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            Text(option.flag(), fontSize = 28.sp)
                        }
                        Text(label, Modifier.weight(1f).padding(horizontal = 10.dp), fontSize = 18.sp)
                        RadioButton(selected = pending == option, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = accent))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (light) Color(0xFFF2F2F2) else colors.surfaceContainer,
                        contentColor = colors.onSurface,
                    ), contentPadding = PaddingValues(vertical = 8.dp)) {
                    Text(stringResource(R.string.history_cancel), fontSize = 18.sp)
                }
                Button(onClick = { onConfirm(pending) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent,
                        contentColor = if (light) Color.White else colors.onPrimary),
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    Text(stringResource(R.string.dialog_done_title), fontSize = 18.sp)
                }
            }
        }
    }
}

@Preview(name = "LanguageSelectionSheet light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "LanguageSelectionSheet dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Composable
private fun LanguageSelectionSheetPreview() {
    VDubbingTheme {
        LanguageSelectionSheet(Language.CHINESE, {}, {})
    }
}
