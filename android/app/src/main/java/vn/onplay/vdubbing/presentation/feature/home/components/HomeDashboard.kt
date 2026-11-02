package vn.onplay.vdubbing.presentation.feature.home.components

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme

@Composable
internal fun HomeDashboard(
    bottomInset: Dp,
    onSettings: () -> Unit,
    onText: () -> Unit,
    onHistory: () -> Unit,
    onVoice: () -> Unit = onText,
    onFlashcards: () -> Unit = onHistory,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val ink = if (dark) Color(0xFFEAE7FF) else Color(0xFF222160)
    val purple = Color(0xFF6557F5)
    var audioHelp by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(if (dark) listOf(Color(0xFF232039), Color(0xFF151322)) else listOf(Color(0xFFF0EEFF), Color(0xFFE3DEFF), Color(0xFFF5F3FF))))
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomInset + 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text("Glotalk", color = ink, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(painterResource(R.drawable.ic_overlay_settings), stringResource(R.string.home_settings_tab), tint = ink)
            }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 142.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFFCEC7FF), Color(0xFFE6E1FF))), RoundedCornerShape(22.dp)),
        ) {
            Column(Modifier.padding(16.dp).fillMaxWidth(0.63f)) {
                Text(stringResource(R.string.home_hero_title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFF29236B))
                Text(stringResource(R.string.home_hero_subtitle), fontSize = 12.sp, color = Color(0xFF373170))
                Spacer(Modifier.height(14.dp))
                Icon(painterResource(R.drawable.ic_overlay_language), null, Modifier.size(34.dp), tint = Color(0xFF4185FF))
            }
            Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(125.dp)) {
                val w = size.width
                val h = size.height
                drawLine(purple, Offset(w * .47f, h * .3f), Offset(w * .42f, h * .13f), w * .025f)
                drawLine(purple, Offset(w * .72f, h * .3f), Offset(w * .8f, h * .12f), w * .025f)
                drawCircle(purple, w * .04f, Offset(w * .42f, h * .12f))
                drawCircle(purple, w * .04f, Offset(w * .8f, h * .12f))
                drawOval(Color(0xFFB2A7F0), Offset(w * .2f, h * .86f), Size(w * .7f, h * .09f))
                drawOval(Color.White, Offset(w * .32f, h * .62f), Size(w * .49f, h * .28f))
                drawRoundRect(Color.White, Offset(w * .22f, h * .26f), Size(w * .69f, h * .46f), CornerRadius(w * .2f))
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF444DC7), Color(0xFF20256C))), Offset(w * .29f, h * .33f), Size(w * .55f, h * .31f), CornerRadius(w * .14f))
                drawCircle(Color(0xFF82EEFF), w * .035f, Offset(w * .44f, h * .46f))
                drawCircle(Color(0xFF82EEFF), w * .035f, Offset(w * .68f, h * .46f))
                drawLine(Color(0xFF82EEFF), Offset(w * .53f, h * .52f), Offset(w * .57f, h * .55f), w * .02f)
                drawLine(Color(0xFF82EEFF), Offset(w * .57f, h * .55f), Offset(w * .61f, h * .52f), w * .02f)
                drawOval(Color.White, Offset(w * .06f, h * .59f), Size(w * .2f, h * .22f))
                drawCircle(Color(0xFFFFB53F), w * .13f, Offset(w * .87f, h * .72f))
            }
        }
        val titles = listOf(R.string.home_screen_tab, R.string.home_text_tab, R.string.home_voice_title, R.string.audio_title, R.string.home_flashcard_title, R.string.home_history_title)
        val descriptions = listOf(R.string.home_screen_description, R.string.home_text_description, R.string.home_voice_description, R.string.home_audio_description, R.string.home_flashcard_description, R.string.home_history_description)
        val icons = listOf(R.drawable.ic_nav_screen, R.drawable.ic_nav_text, R.drawable.ic_mic, R.drawable.ic_text_speak, R.drawable.ic_history_cards, R.drawable.ic_history)
        val tints = listOf(Color(0xFF4388FF), Color(0xFF2FCB70), purple, Color(0xFFFF941F), Color(0xFF2AC97B), Color(0xFF4388FF))
        val actions = listOf(onSettings, onText, onVoice, { audioHelp = true }, onFlashcards, onHistory)
        for (row in 0..2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (column in 0..1) {
                    val index = row * 2 + column
                    Card(
                        onClick = actions[index], modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = if (dark) Color(0xFF302C46) else Color(0xFFF9F8FF)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(painterResource(icons[index]), null, Modifier.size(34.dp), tint = tints[index])
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(titles[index]), color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(descriptions[index]), color = ink.copy(alpha = .8f), fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
    if (audioHelp) {
        AlertDialog(onDismissRequest = { audioHelp = false },
            title = { Text(stringResource(R.string.audio_title)) },
            text = { Text(stringResource(R.string.audio_select_app)) },
            confirmButton = { TextButton(onClick = { audioHelp = false; onSettings() }) { Text(stringResource(R.string.home_settings_tab)) } },
            dismissButton = { TextButton(onClick = { audioHelp = false }) { Text(stringResource(R.string.action_close)) } })
    }
}

@Preview(name = "Dashboard light", widthDp = 390, heightDp = 844)
@Preview(name = "Dashboard dark", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeDashboardPreview() {
    VDubbingTheme { HomeDashboard(54.dp, {}, {}, {}) }
}
