package com.music.bitchord.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
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

/** Which of the microphone's two jobs a dialog was opened for. */
enum class VoiceAction { VOICE_SEARCH, IDENTIFY_SONG }

/**
 * The desktop's microphone, in one dialog: either speech-to-text into the
 * search field, or a Shazam listen that answers with a track to search for.
 *
 * The listening happens in a coroutine started when the dialog opens, so the
 * dialog itself only draws the four states — listening, heard something,
 * heard nothing, failed — and a retry re-runs the same call.
 */
@Composable
fun VoiceSearchDialog(
    action: VoiceAction,
    onDismiss: () -> Unit,
    /** Put [text] in the search field and run the search. */
    onQuery: (String) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf(true) }
    var heard by remember { mutableStateOf<String?>(null) }
    var identified by remember { mutableStateOf<RecognizedSong?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var rejection by remember { mutableStateOf<String?>(null) }

    val recognition = remember { SongRecognition() }
    val speech = remember { speechToText() }

    LaunchedEffect(action, attempt) {
        listening = true
        heard = null
        identified = null
        message = null
        rejection = null
        when (action) {
            VoiceAction.VOICE_SEARCH -> {
                if (!speech.isAvailable) {
                    listening = false
                    rejection = "Voice search needs the phone's speech recogniser, which this desktop build does not have. Identify Song works here."
                    return@LaunchedEffect
                }
                val text = speech.listen()
                listening = false
                if (text.isNullOrBlank()) {
                    rejection = "Nothing was heard. Try again, a little closer to the microphone."
                } else {
                    heard = text
                }
            }

            VoiceAction.IDENTIFY_SONG -> {
                when (val result = recognition.listen()) {
                    is RecognitionResult.Match -> {
                        listening = false
                        identified = result.song
                    }
                    RecognitionResult.NoMatch -> {
                        listening = false
                        rejection = "Shazam did not recognise that. Try again while the track is playing."
                    }
                    RecognitionResult.Silent -> {
                        listening = false
                        rejection = "That was silence — turn the music up, or hold the microphone closer."
                    }
                    RecognitionResult.Unsupported -> {
                        listening = false
                        rejection = "No microphone is available to this build."
                    }
                    is RecognitionResult.Failed -> {
                        listening = false
                        message = result.reason
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (action) {
                    VoiceAction.VOICE_SEARCH -> "Voice search"
                    VoiceAction.IDENTIFY_SONG -> "Identify song"
                },
            )
        },
        text = {
            Column {
                when {
                    listening -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (action == VoiceAction.IDENTIFY_SONG) {
                                "Listening for a few seconds…"
                            } else {
                                "Listening…"
                            },
                        )
                    }

                    identified != null -> Column {
                        Text("That's", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(identified!!.title, style = MaterialTheme.typography.titleMedium)
                        if (identified!!.artist.isNotBlank()) {
                            Text(
                                identified!!.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Searching your sources will find it on whichever catalogue has it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    heard != null -> Column {
                        Text(heard!!, style = MaterialTheme.typography.bodyLarge)
                    }

                    else -> Column {
                        Text(
                            message ?: rejection.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
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

                !listening -> TextButton(onClick = { attempt++ }) { Text("Try again") }

                else -> Unit
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (listening) {
                    TextButton(onClick = { attempt++ }) { Text("Start over") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
