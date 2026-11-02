package vn.onplay.vdubbing.presentation.feature.texttranslation.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.IconButton
import vn.onplay.vdubbing.presentation.speech.VoiceInput
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.presentation.designsystem.help.translationLabel
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceInputSheet(
    source: Language,
    onTranscript: (String) -> Unit,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val result = rememberUpdatedState(onResult)
    val transcript = rememberUpdatedState(onTranscript)
    val voice = remember(context, source) {
        VoiceInput(context, onTranscript = { transcript.value(it) }, onResult = { result.value(it) })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start(source) else voice.permissionDenied()
    }
    val start: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start(source)
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(voice, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voice.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            voice.destroy()
        }
    }
    LaunchedEffect(voice) { start() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        VoiceInputContent(source, voice.message, voice.transcript, voice.busy, start)
    }
}

@Composable
private fun VoiceInputContent(
    source: Language,
    message: Int = R.string.voice_ready,
    transcript: String = "",
    busy: Boolean = false,
    onStart: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.text_voice_input), style = MaterialTheme.typography.titleLarge)
        Text(source.translationLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(
            onClick = onStart,
            enabled = !busy,
            modifier = Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        ) {
            Icon(
                painterResource(R.drawable.ic_mic), stringResource(R.string.text_voice_input), Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            transcript.ifBlank { stringResource(message) },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Preview(name = "Voice input light", showBackground = true)
@Preview(name = "Voice input dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun VoiceInputPreview() {
    VDubbingTheme {
        VoiceInputContent(Language.CHINESE)
    }
}
