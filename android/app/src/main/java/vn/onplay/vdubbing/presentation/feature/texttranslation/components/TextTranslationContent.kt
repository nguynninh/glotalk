package vn.onplay.vdubbing.presentation.feature.texttranslation.components

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.components.AppIcon
import vn.onplay.vdubbing.presentation.designsystem.components.AppIconSource
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.designsystem.components.button.LanguagePicker
import vn.onplay.vdubbing.presentation.designsystem.components.bottomsheet.LanguageSelectionSheet
import vn.onplay.vdubbing.presentation.designsystem.components.card.CardNavigationTextTranslation
import vn.onplay.vdubbing.presentation.designsystem.components.card.CardTranslate
import vn.onplay.vdubbing.presentation.designsystem.uimodel.TranslateUiState
import vn.onplay.vdubbing.presentation.designsystem.help.translationLabel

@Composable
internal fun TextTranslationContent(
    input: TextFieldValue,
    source: Language,
    target: Language,
    result: String?,
    translating: Boolean,
    @StringRes error: Int?,
    onInputChange: (TextFieldValue) -> Unit,
    onSourceChange: (Language) -> Unit,
    onTargetChange: (Language) -> Unit,
    onReadAloud: (String, Language) -> Unit,
    onCopyText: (String) -> Unit,
    onUnavailable: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
    isKeyboardVisible: Boolean? = null,
    voiceRequest: Int = 0,
    onInputFocusChange: (Boolean) -> Unit = {},
) {
    var showingVoiceInput by rememberSaveable { mutableStateOf(false) }
    var pickingSource by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var inputFocused by remember { mutableStateOf(false) }
    val text = input.text
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val keyboardVisible = isKeyboardVisible ?: (WindowInsets.ime.getBottom(LocalDensity.current) > 0)
    val editing = keyboardVisible || inputFocused
    val swapLanguages: () -> Unit = {
        val previousSource = source
        onSourceChange(target)
        onTargetChange(previousSource)
    }
    val enterText: () -> Unit = {
        focus.requestFocus()
        keyboard?.show()
    }

    val openVoiceInput: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
        showingVoiceInput = true
    }

    var handledVoiceRequest by rememberSaveable { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(voiceRequest) {
        if (voiceRequest > handledVoiceRequest) {
            handledVoiceRequest = voiceRequest
            openVoiceInput()
        }
    }

    Column(modifier.padding(horizontal = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (editing) {
                IconButton(
                    onClick = {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    },
                    modifier = Modifier.offset(x = (-8).dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_overlay_chevron),
                        stringResource(R.string.text_back),
                        Modifier.rotate(180f).size(38.dp),
                    )
                }
            } else {
                Text(stringResource(R.string.home_text_tab), fontSize = 16.sp)
            }
            IconButton(
                onClick = { if (editing || text.isNotEmpty()) onInputChange(TextFieldValue()) else onHistory() },
            ) {
                Box(
                    modifier = Modifier.size(32.dp).background(Color(0xFFF4F4F4), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(
                        source = AppIconSource.DrawableSource(
                            if (editing || text.isNotEmpty()) R.drawable.ic_overlay_close else R.drawable.ic_history,
                        ),
                        contentDescription = stringResource(if (editing || text.isNotEmpty()) R.string.text_clear else R.string.text_history),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF171717),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            CardTranslate(
                source = TranslateUiState(source.translationLabel(), text),
                target = TranslateUiState(target.translationLabel(), result.orEmpty()),
                isLoading = translating,
                showTranslation = editing || text.isNotBlank(),
                onSwapLanguages = swapLanguages,
                onCopy = { isTarget -> onCopyText(if (isTarget) result.orEmpty() else text) },
                onSpeak = { isTarget ->
                    onReadAloud(if (isTarget) result.orEmpty() else text, if (isTarget) target else source)
                },
                sourceContent = {
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp, max = 180.dp)
                            .focusRequester(focus).onFocusChanged {
                                inputFocused = it.isFocused
                                onInputFocusChange(it.isFocused)
                            }.padding(top = 12.dp, bottom = 8.dp),
                        textStyle = TextStyle(color = colors.onSurface, fontSize = 20.sp),
                        decorationBox = { input ->
                            Box(
                                Modifier.fillMaxWidth().clickable(enabled = text.isEmpty(), onClick = enterText),
                            ) {
                                if (text.isEmpty()) {
                                    Text(stringResource(R.string.text_input_hint), fontSize = 20.sp)
                                }
                                input()
                            }
                        },
                    )
                },
            )
            if (text.isBlank() && !editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportButton(R.string.text_import_file, R.drawable.ic_text_upload, onUnavailable)
                    ImportButton(R.string.text_import_link, R.drawable.ic_text_link, onUnavailable)
                }
            } else {
                error?.let {
                    Text(stringResource(it), Modifier.padding(vertical = 12.dp), color = colors.error)
                }
            }
        }
        if (editing) {
            CardNavigationTextTranslation(
                source = source,
                target = target,
                onSourceClick = { pickingSource = true },
                onTargetClick = { pickingSource = false },
                onSwapLanguages = swapLanguages,
                onVoiceInput = openVoiceInput,
                onCameraInput = onUnavailable,
                onKeyboardInput = enterText,
            )
        } else {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF202D4A), Color(0xFF2E4575))),
                        RoundedCornerShape(18.dp),
                    ).padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LanguagePicker(source, { pickingSource = true }, Modifier.weight(1f))
                    IconButton(onClick = swapLanguages, modifier = Modifier.size(24.dp)) {
                        Icon(painterResource(R.drawable.ic_overlay_swap), stringResource(R.string.panel_swap_languages), tint = Color.White)
                    }
                    LanguagePicker(target, { pickingSource = false }, Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InputButton(R.drawable.ic_mic, R.string.text_voice_input, false, openVoiceInput)
                    InputButton(R.drawable.ic_text_camera, R.string.text_camera, true, onUnavailable)
                    InputButton(R.drawable.ic_text_edit, R.string.text_keyboard, false, enterText)
                }
            }
        }
    }
    if (showingVoiceInput) {
        VoiceInputSheet(
            source = source,
            onTranscript = { text ->
                onInputChange(TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length)))
            },
            onResult = { text ->
                onInputChange(TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length)))
                showingVoiceInput = false
            },
            onDismiss = { showingVoiceInput = false },
        )
    }
    pickingSource?.let { isSource ->
        LanguageSelectionSheet(
            language = if (isSource) source else target,
            onConfirm = {
                if (isSource) onSourceChange(it) else onTargetChange(it)
                pickingSource = null
            },
            onDismiss = { pickingSource = null },
        )
    }

}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TextTranslationContentPreview() {
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
