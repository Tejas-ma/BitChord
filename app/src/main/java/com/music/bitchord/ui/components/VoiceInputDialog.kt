package com.music.bitchord.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.audio.AcrCloudClient
import com.music.bitchord.audio.PcmRecorder
import com.music.bitchord.audio.SAMPLE_RATE
import com.music.bitchord.audio.RecognitionResult
import com.music.bitchord.audio.SongRecognition
import com.music.bitchord.audio.hasSignal
import com.music.bitchord.audio.speechToText
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.PI
import kotlin.math.sin

private enum class Tab { VOICE, SONG }

private enum class SongMode { PLAYING, HUM }

/** The ten seconds ACRCloud's matcher wants for a hummed line. */
private const val HUM_CAPTURE_MS = 10_000

/** The sheet's own palette: charcoal, matte grey and white, nothing else. */
private val SheetBackground = Color(0xFF161618)
private val SwitcherWell = Color(0xFF1C1C1E)
private val SelectedPill = Color(0xFF2C2C2E)
private val MutedText = Color(0xFF9A9AA0)

/**
 * The search field's microphone: say what to search for, or let the app name
 * what is playing or what was hummed and search for that.
 *
 * Every answer ends in [onQuery] — the field is filled and the unified search
 * runs — so a match from any path lands on the same all-sources page instead of
 * sending the listener out to another app.
 *
 * The card is deliberately monochrome: a dark-glass sheet with one flowing
 * waveform, scaled by the microphone's RMS level. That is not decoration — a
 * recogniser that shows nothing for eight seconds reads as broken, and the
 * waveform is the only evidence the listener has that the microphone is open.
 */
@Composable
fun VoiceInputDialog(
    onDismiss: () -> Unit,
    /** Fill the search field with this and search. */
    onQuery: (String) -> Unit,
    /**
     * Stops BitChord before the microphone opens.
     *
     * Without it the app recognises its own output: a track playing through the
     * speaker is the loudest thing the microphone hears, so "what is this?"
     * answers with the song already on screen, and a hum is buried under it.
     */
    onPausePlayback: () -> Unit = {},
) {
    val context = LocalContext.current
    val speech = remember { speechToText() }
    val recognition = remember { SongRecognition() }
    val recorder = remember { PcmRecorder() }

    val acrCloudHost by AppSettings.acrCloudHost.collectAsStateWithLifecycle()
    val acrCloudAccessKey by AppSettings.acrCloudAccessKey.collectAsStateWithLifecycle()
    val acrCloudAccessSecret by AppSettings.acrCloudAccessSecret.collectAsStateWithLifecycle()
    val acrCloudEnabled by AppSettings.acrCloudEnabled.collectAsStateWithLifecycle()
    // The switch and the three fields are one answer; read it through the
    // setting's own gate so the sheet cannot drift from the settings row.
    val acrCloudReady = acrCloudEnabled && AppSettings.hasValidAcrCloudConfig

    var tab by remember { mutableStateOf(Tab.VOICE) }
    var songMode by remember { mutableStateOf(SongMode.PLAYING) }
    var listening by remember { mutableStateOf(false) }
    var matching by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var heard by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    /** A recognised answer: fill the field, run the search, and get out of the way. */
    fun searchAndClose(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) onQuery(trimmed)
        onDismiss()
    }

    /**
     * Google's own Sound Search, by package name.
     *
     * It is the fallback for a hum when there are no ACRCloud keys: Google's
     * matcher answers hums too, and its answer arrives as a YouTube or YouTube
     * Music link — which BitChord's manifest claims, so the result comes back
     * into the app rather than stopping in another one.
     */
    fun openGoogle() {
        val intent = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val available = runCatching {
            intent.resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
        val opened = available && runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (opened) {
            onDismiss()
        } else {
            problem = context.getString(R.string.google_search_unavailable)
            Toast.makeText(context, R.string.google_search_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(tab, songMode, attempt, acrCloudReady) {
        // A hum with no ACRCloud keys has nothing to record into; Google listens
        // instead, and the sheet closes behind it.
        if (tab == Tab.SONG && songMode == SongMode.HUM && !acrCloudReady) {
            // The effect only re-runs on a tab/mode/retry change, so this is
            // one launch per attempt — including Try again after a device with
            // no Sound Search answered with the fallback toast.
            onPausePlayback()
            openGoogle()
            return@LaunchedEffect
        }
        listening = true
        matching = false
        level = 0f
        heard = ""
        problem = null
        // Paused before the capture starts, not after: the first second of a
        // Shazam capture is the one that matters most.
        onPausePlayback()
        try {
            when {
                tab == Tab.VOICE -> {
                    if (!speech.isAvailable) {
                        problem = context.getString(R.string.voice_no_recognizer)
                        return@LaunchedEffect
                    }
                    val text = speech.listen(
                        onPartial = { heard = it },
                        onRms = { level = it },
                    )
                    if (text.isNullOrBlank()) {
                        problem = context.getString(R.string.voice_nothing_heard)
                    } else {
                        searchAndClose(text)
                    }
                }

                songMode == SongMode.PLAYING -> when (
                    val result = recognition.listen(
                        onAmplitude = { level = it },
                        onCaptureComplete = { matching = true },
                    )
                ) {
                    is RecognitionResult.Match -> searchAndClose(result.song.searchQuery)
                    RecognitionResult.NoMatch -> problem = context.getString(R.string.song_no_match)
                    RecognitionResult.Silent -> problem = context.getString(R.string.song_silent)
                    RecognitionResult.Unsupported -> problem = context.getString(R.string.song_mic_unavailable)
                    is RecognitionResult.Failed -> problem = result.reason
                }

                else -> {
                    val captured = recorder.record(HUM_CAPTURE_MS) { level = it }
                    if (captured == null || captured.samples.isEmpty()) {
                        problem = context.getString(R.string.song_mic_unavailable)
                        return@LaunchedEffect
                    }
                    // The recorder tags the device's true rate; ACRCloud wants
                    // 16 kHz mono, so normalise before checking the level and
                    // before the client builds the request.
                    val recording = captured.resampledTo(SAMPLE_RATE)
                    if (!recording.hasSignal()) {
                        problem = context.getString(R.string.song_silent)
                        return@LaunchedEffect
                    }
                    matching = true
                    AcrCloudClient.identify(
                        recording = recording,
                        host = acrCloudHost,
                        accessKey = acrCloudAccessKey,
                        accessSecret = acrCloudAccessSecret,
                    ).fold(
                        onSuccess = { song ->
                            if (song == null) {
                                problem = context.getString(R.string.song_no_match)
                            } else {
                                searchAndClose(song.searchQuery)
                            }
                        },
                        onFailure = { problem = context.getString(R.string.song_no_match) },
                    )
                }
            }
        } finally {
            listening = false
            matching = false
            level = 0f
            // Also covers a cancelled Shazam capture: the microphone must not
            // stay open because a coroutine has not resumed yet.
            runCatching { recognition.stop() }
        }
    }

    // The microphone has to go quiet the moment the sheet does, not when the
    // cancelled coroutine next resumes: Android's green indicator is the thing
    // being asked about, and a recogniser left alive holds it for seconds.
    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder.stop() }
            runCatching { recognition.stop() }
            runCatching { speech.cancel() }
        }
    }

    val shape = RoundedCornerShape(28.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 440.dp)
                        .navigationBarsPadding()
                        .clip(shape)
                        .background(SheetBackground.copy(alpha = 0.98f))
                        .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
                        // Swallows taps on the sheet itself; only the scrim dismisses.
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        )
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TabSwitcher(
                        tab = tab,
                        onTab = { chosen ->
                            if (chosen != tab) {
                                tab = chosen
                                attempt++
                            }
                        },
                    )

                    if (tab == Tab.SONG) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModePill(
                                label = stringResource(R.string.song_playing_music),
                                selected = songMode == SongMode.PLAYING,
                                onClick = {
                                    if (songMode != SongMode.PLAYING) {
                                        songMode = SongMode.PLAYING
                                        attempt++
                                    }
                                },
                            )
                            ModePill(
                                label = stringResource(R.string.song_hum_or_sing),
                                selected = songMode == SongMode.HUM,
                                onClick = {
                                    if (songMode != SongMode.HUM) {
                                        songMode = SongMode.HUM
                                        attempt++
                                    }
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    FlowingWaveform(level = level, listening = listening)
                    Spacer(Modifier.height(14.dp))

                    val status = problem ?: when {
                        matching -> stringResource(R.string.song_matching)
                        tab == Tab.VOICE -> stringResource(R.string.voice_listening)
                        songMode == SongMode.PLAYING -> stringResource(R.string.song_listening)
                        else -> stringResource(R.string.hum_listening)
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        color = if (problem != null) Color(0xFFD89A9A) else Color.White.copy(alpha = 0.88f),
                        textAlign = TextAlign.Center,
                    )

                    if (problem == null && heard.isNotBlank() && tab == Tab.VOICE) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "\u201C$heard\u201D",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (tab == Tab.SONG && songMode == SongMode.HUM && !acrCloudReady) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.acrcloud_not_configured),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = MutedText,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (problem != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ActionPill(
                                label = stringResource(R.string.try_again),
                                emphasised = true,
                                onClick = { attempt++ },
                            )
                            TextButton(onClick = { openGoogle() }) {
                                Text(
                                    text = stringResource(R.string.use_google_instead),
                                    color = Color.White.copy(alpha = 0.68f),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ActionPill(
                            label = stringResource(R.string.close),
                            emphasised = true,
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

/** The two-tab switcher that leads the sheet: Voice, or Song. */
@Composable
private fun TabSwitcher(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SwitcherWell)
            .padding(3.dp),
    ) {
        TabButton(
            label = stringResource(R.string.voice_tab),
            selected = tab == Tab.VOICE,
            onClick = { onTab(Tab.VOICE) },
            modifier = Modifier.weight(1f),
        )
        TabButton(
            label = stringResource(R.string.song_tab),
            selected = tab == Tab.SONG,
            onClick = { onTab(Tab.SONG) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) SelectedPill else Color.Transparent)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
            ),
            color = if (selected) Color.White else MutedText,
        )
    }
}

/** "Playing music" / "Hum or sing", the song tab's own choice. */
@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) SelectedPill else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50),
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
            ),
            color = if (selected) Color.White else Color.White.copy(alpha = 0.62f),
        )
    }
}

/** One stroke of the waveform: how it sits, how fast it moves, how bright it is. */
private data class WaveLayer(
    val alpha: Float,
    val scale: Float,
    val speed: Float,
    val shift: Float,
    val strokeDp: Float,
)

/**
 * The centre of the sheet: three smooth sine strokes, tapered to nothing at
 * both edges, moving continuously and swelling with the microphone's RMS
 * level. A small white microphone sits over the middle so the shape reads as
 * listening rather than as an abstract animation.
 */
@Composable
private fun FlowingWaveform(level: Float, listening: Boolean) {
    val eased by animateFloatAsState(
        targetValue = if (listening) level else 0f,
        animationSpec = tween(110),
        label = "waveLevel",
    )
    val pulse = rememberInfiniteTransition(label = "wavePulse")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2_400, easing = LinearEasing),
        ),
        label = "wavePhase",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val drive = 0.12f + 0.88f * eased
            val amplitude = size.height * 0.21f * drive
            val layers = listOf(
                WaveLayer(alpha = 0.92f, scale = 1f, speed = 1f, shift = 0f, strokeDp = 2.2f),
                WaveLayer(alpha = 0.36f, scale = 0.74f, speed = 1.23f, shift = 1.6f, strokeDp = 1.4f),
                WaveLayer(alpha = 0.18f, scale = 1.16f, speed = 0.86f, shift = 3.2f, strokeDp = 1.1f),
            )
            layers.forEach { layer ->
                val path = Path()
                val steps = 120
                for (step in 0..steps) {
                    val t = step / steps.toFloat()
                    // Zero at both ends: the stroke melts into the card instead
                    // of stopping at a hard vertical edge.
                    val taper = sin(PI.toFloat() * t)
                    val wave = sin(t * 2f * PI.toFloat() * 1.35f * layer.speed + phase + layer.shift)
                    val x = size.width * t
                    val y = size.height / 2f + wave * amplitude * layer.scale * taper
                    if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = layer.alpha),
                    style = Stroke(width = layer.strokeDp.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(54.dp)
                .background(SheetBackground, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.94f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A small dark pill used by the sheet's actions. */
@Composable
private fun ActionPill(
    label: String,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (emphasised) SelectedPill else Color.White.copy(alpha = 0.07f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W400,
            ),
            color = if (emphasised) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.70f),
        )
    }
}
