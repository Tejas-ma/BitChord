package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.audio.RecognitionResult
import com.music.bitchord.audio.RecognizedSong
import com.music.bitchord.audio.SongRecognition
import com.music.bitchord.audio.speechToText

private enum class Mode { CHOOSING, VOICE, IDENTIFY }

/**
 * The search field's microphone: say what to search for, or let Shazam name
 * what is playing and search for that.
 *
 * Both answers end in [onQuery] — the field is filled and the unified search
 * runs — so a match from either path lands on the same all-sources page.
 */
@Composable
fun VoiceInputDialog(
    onDismiss: () -> Unit,
    /** Fill the search field with this and search. */
    onQuery: (String) -> Unit,
) {
    var mode by remember { mutableStateOf(Mode.CHOOSING) }
    var attempt by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var identified by remember { mutableStateOf<RecognizedSong?>(null) }
    var heard by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    val recognition = remember { SongRecognition() }
    val speech = remember { speechToText() }

    LaunchedEffect(mode, attempt) {
        if (mode == Mode.CHOOSING) return@LaunchedEffect
        listening = true
        identified = null
        heard = null
        problem = null
        if (mode == Mode.VOICE) {
            if (!speech.isAvailable) {
                listening = false
                problem = "This device has no speech recogniser installed. Identify Song still works."
                return@LaunchedEffect
            }
            val text = speech.listen()
            listening = false
            if (text.isNullOrBlank()) problem = "Nothing was heard — try again." else heard = text
        } else {
            when (val result = recognition.listen()) {
                is RecognitionResult.Match -> {
                    listening = false
                    identified = result.song
                }
                RecognitionResult.NoMatch -> {
                    listening = false
                    problem = "Shazam did not recognise that. Try again while the track plays."
                }
                RecognitionResult.Silent -> {
                    listening = false
                    problem = "That was silence — turn the music up, or hold the phone closer."
                }
                RecognitionResult.Unsupported -> {
                    listening = false
                    problem = "No microphone is available."
                }
                is RecognitionResult.Failed -> {
                    listening = false
                    problem = result.reason
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    Mode.CHOOSING -> "Search with your voice"
                    Mode.VOICE -> "Voice search"
                    Mode.IDENTIFY -> "Identify song"
                },
            )
        },
        text = {
            Column {
                when {
                    mode == Mode.CHOOSING -> Column {
                        Text(
                            "Say what you want to hear, or hold the phone up to the music and let it find the track.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { mode = Mode.VOICE },
                                enabled = speech.isAvailable,
                            ) { Text("Voice search") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { mode = Mode.IDENTIFY }) { Text("Identify song") }
                        }
                        if (!speech.isAvailable) {
                            Text(
                                "Voice search needs the system speech recogniser, which this device does not have.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    listening -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (mode == Mode.IDENTIFY) "Listening for a few seconds…" else "Listening…",
                        )
                    }

                    identified != null -> Column {
                        Text("That's", style = MaterialTheme.typography.labelMedium)
                        Text(identified!!.title, style = MaterialTheme.typography.titleMedium)
                        if (identified!!.artist.isNotBlank()) {
                            Text(
                                identified!!.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    heard != null -> Text(heard!!, style = MaterialTheme.typography.bodyLarge)

                    else -> Text(
                        problem.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                identified != null -> TextButton(onClick = {
                    onQuery(identified!!.searchQuery)
                    onDismiss()
                }) { Text("Search for it") }

                heard != null -> TextButton(onClick = {
                    onQuery(heard!!)
                    onDismiss()
                }) { Text("Search") }

                mode != Mode.CHOOSING && !listening -> TextButton(onClick = { attempt++ }) {
                    Text("Try again")
                }

                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
