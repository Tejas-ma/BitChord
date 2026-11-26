package com.music.bitchord.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.audio.RecognitionResult
import com.music.bitchord.audio.RecognizedSong
import com.music.bitchord.audio.SongRecognition
import com.music.bitchord.audio.speechToText

/** Which of the microphone's two jobs a dialog was opened for. */
enum class VoiceAction { VOICE_SEARCH, IDENTIFY_SONG }

/** What the panel is doing right now; exactly one of these is on screen. */
private enum class VoiceStage {
    /** The microphone is open and the bars move with what it hears. */
    LISTENING,

    /** The capture is over and Shazam is being asked. */
    MATCHING,

    /** Speech was transcribed; the words can be searched for. */
    HEARD,

    /** Shazam named the track. */
    IDENTIFIED,

    /** A failure a retry can fix — or explain why it cannot. */
    PROBLEM,

    /** This build has no speech recogniser at all. */
    UNAVAILABLE,
}

/** How many recent amplitude readings the waveform keeps. */
private const val WAVE_BARS = 44

/** The panel's dark glass, matching the desktop player's own surfaces. */
private val GlassBackground = Color(0xF214141B)
private val GlassEdge = Color(0x33FFFFFF)
private val Accent = Color(0xFF7C8CFF)

/**
 * The desktop's microphone, in one panel: either speech-to-text into the search
 * field, or a Shazam listen that answers with a track to search for.
 *
 * The listening happens in a coroutine started when the panel opens, so the
 * panel itself only draws what that coroutine reports — a live waveform while
 * the microphone is open, a progress bar while Shazam is asked, the words or
 * the track it found, and a retry for anything that failed. Closing the panel
 * stops the microphone rather than waiting for the capture to finish.
 */
@Composable
fun VoiceSearchDialog(
    action: VoiceAction,
    onDismiss: () -> Unit,
    /** Put [text] in the search field and run the search. */
    onQuery: (String) -> Unit,
    /**
     * Offered when this build has no speech recogniser, so the microphone's
     * other job — which does work here — is one tap away rather than a trip
     * back to the search field.
     */
    onIdentifySong: (() -> Unit)? = null,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf(VoiceStage.LISTENING) }
    var partial by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf<String?>(null) }
    var identified by remember { mutableStateOf<RecognizedSong?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var captureProgress by remember { mutableFloatStateOf(0f) }
    val levels = remember { mutableStateListOf<Float>() }

    val recognition = remember { SongRecognition() }
    val speech = remember { speechToText() }

    LaunchedEffect(action, attempt) {
        stage = VoiceStage.LISTENING
        partial = ""
        heard = null
        identified = null
        problem = null
        captureProgress = 0f
        levels.clear()
        when (action) {
            VoiceAction.VOICE_SEARCH -> {
                if (!speech.isAvailable) {
                    stage = VoiceStage.UNAVAILABLE
                    return@LaunchedEffect
                }
                val text = speech.listen(
                    // The words appear as they are recognised, rather than only
                    // once the recogniser decides the sentence is over.
                    onPartial = { partial = it },
                    onRms = levels::push,
                )
                if (text.isNullOrBlank()) {
                    stage = VoiceStage.PROBLEM
                    problem = "Nothing was heard. Try again, a little closer to the microphone."
                } else {
                    heard = text
                    stage = VoiceStage.HEARD
                }
            }

            VoiceAction.IDENTIFY_SONG -> {
                when (
                    val result = recognition.listen(
                        onAmplitude = { level ->
                            levels.push(level)
                            // The capture is a fixed few seconds; the bar is how
                            // much of it has been heard rather than a guess.
                            captureProgress = (captureProgress + 1f / (CAPTURE_BAR_TICKS)).coerceIn(0f, 1f)
                        },
                        onCaptureComplete = { stage = VoiceStage.MATCHING },
                    )
                ) {
                    is RecognitionResult.Match -> {
                        identified = result.song
                        stage = VoiceStage.IDENTIFIED
                    }

                    RecognitionResult.NoMatch -> {
                        stage = VoiceStage.PROBLEM
                        problem = "Shazam did not recognise that. Try again while the track is playing."
                    }

                    RecognitionResult.Silent -> {
                        stage = VoiceStage.PROBLEM
                        problem = "That was silence — turn the music up, or hold the microphone closer."
                    }

                    RecognitionResult.Unsupported -> {
                        stage = VoiceStage.PROBLEM
                        problem = "No microphone is available to this build."
                    }

                    is RecognitionResult.Failed -> {
                        stage = VoiceStage.PROBLEM
                        problem = result.reason
                    }
                }
            }
        }
    }

    // Closing the panel releases the microphone now, rather than when the
    // cancelled capture next resumes.
    DisposableEffect(action) {
        onDispose {
            speech.cancel()
            recognition.stop()
        }
    }

    val listening = stage == VoiceStage.LISTENING || stage == VoiceStage.MATCHING

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassBackground,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            color = if (listening) Accent else GlassEdge,
                            shape = RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (action == VoiceAction.IDENTIFY_SONG) "Identify song" else "Voice search",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Waveform(
                    levels = levels,
                    active = stage == VoiceStage.LISTENING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(top = 4.dp),
                )
                Spacer(Modifier.height(14.dp))
                when (stage) {
                    VoiceStage.LISTENING -> {
                        Text(
                            text = if (action == VoiceAction.IDENTIFY_SONG) {
                                "Listening… hold the music up to the microphone."
                            } else {
                                partial.ifBlank { "Listening… say what you want to hear." }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.86f),
                        )
                        if (action == VoiceAction.IDENTIFY_SONG) {
                            Spacer(Modifier.height(10.dp))
                            // How much of the fixed capture has been recorded, so five
                            // seconds of listening does not look like a hang.
                            LinearProgressIndicator(
                                progress = { captureProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Accent,
                                trackColor = Color.White.copy(alpha = 0.12f),
                            )
                        }
                    }

                    VoiceStage.MATCHING -> {
                        Text(
                            "Checking which track that is…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.86f),
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Accent,
                            trackColor = Color.White.copy(alpha = 0.12f),
                        )
                    }

                    VoiceStage.HEARD -> {
                        Text(
                            "Heard",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.55f),
                            letterSpacing = 1.2.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            heard.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    VoiceStage.IDENTIFIED -> {
                        Text(
                            "That's",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.55f),
                            letterSpacing = 1.2.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            identified?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        identified?.artist?.takeIf(String::isNotBlank)?.let { artist ->
                            Text(
                                artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Searching your sources will find it on whichever catalogue has it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }

                    VoiceStage.PROBLEM -> {
                        Text(
                            problem.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Nothing was saved. Trying again starts a fresh listen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }

                    VoiceStage.UNAVAILABLE -> {
                        Text(
                            "This build has no speech recogniser, so voice search cannot listen. " +
                                "Identify song records the microphone and asks Shazam, which works here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (stage) {
                    VoiceStage.IDENTIFIED -> {
                        TextButton(onClick = { attempt++ }) { Text("Try again") }
                        TextButton(onClick = {
                            identified?.let { onQuery(it.searchQuery) }
                            onDismiss()
                        }) { Text("Search for it") }
                    }

                    VoiceStage.HEARD -> {
                        TextButton(onClick = { attempt++ }) { Text("Try again") }
                        TextButton(onClick = {
                            onQuery(heard.orEmpty())
                            onDismiss()
                        }) { Text("Search") }
                    }

                    VoiceStage.PROBLEM -> {
                        TextButton(onClick = onDismiss) { Text("Close") }
                        TextButton(onClick = { attempt++ }) { Text("Try again") }
                    }

                    VoiceStage.UNAVAILABLE -> {
                        TextButton(onClick = onDismiss) { Text("Close") }
                        if (onIdentifySong != null) {
                            TextButton(onClick = {
                                onIdentifySong()
                            }) { Text("Identify a song") }
                        }
                    }

                    VoiceStage.LISTENING -> {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(onClick = { attempt++ }) { Text("Start over") }
                    }

                    VoiceStage.MATCHING -> {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            }
        },
    )
}

/**
 * The last few amplitude readings, drawn as a centred bar waveform.
 *
 * It is fed by the microphone itself — speech-to-text's RMS and the capture's
 * amplitude — so a quiet room is a flat line and a playing track is not, which
 * is the difference between "it is listening" and "it is listening and hearing
 * something".
 */
@Composable
private fun Waveform(
    levels: List<Float>,
    active: Boolean,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val count = WAVE_BARS
        val step = size.width / count
        val barWidth = (step * 0.55f).coerceAtLeast(1f)
        for (index in 0 until count) {
            val level = levels.getOrNull(levels.size - count + index) ?: 0f
            // A little height even at silence, so the line reads as a waveform
            // waiting rather than as an empty box.
            val share = (level.coerceIn(0f, 1f) * 0.9f + 0.06f)
            val barHeight = size.height * share
            drawRoundRect(
                color = Accent.copy(alpha = if (active) 0.9f else 0.4f),
                topLeft = Offset(index * step + (step - barWidth) / 2f, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** Keeps the newest [WAVE_BARS] readings, dropping the oldest as they age out. */
private fun SnapshotStateList<Float>.push(level: Float) {
    add(level.coerceIn(0f, 1f))
    if (size > WAVE_BARS * 2) {
        removeAll(subList(0, size - WAVE_BARS * 2).toList())
    }
}

/**
 * Roughly how many amplitude readings the five-second capture makes, so the
 * progress bar fills at about the pace the recording is actually filling.
 */
private const val CAPTURE_BAR_TICKS = 120f
