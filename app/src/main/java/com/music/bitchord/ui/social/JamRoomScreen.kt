package com.music.bitchord.ui.social

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.jam.JamUserManager
import com.music.bitchord.data.jam.Room
import com.music.bitchord.data.jam.PlaybackState
import com.music.bitchord.data.model.Song
import coil3.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import org.json.JSONObject

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun JamRoomScreen(
    roomId: String,
    viewModel: JamViewModel,
    roomName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentUserId = JamUserManager.getCurrentUserId(context)
    val isGuest = JamUserManager.isGuest(context)

    val roomsState = viewModel.rooms.collectAsState(initial = emptyList())
    val rooms = roomsState.value
    val room = rooms.find { it.id == roomId } ?: Room(name = roomName)
    val isHost = room.hostId == currentUserId

    val queueJsonStringsState = viewModel.observeQueue(roomId).collectAsState(initial = emptyList())
    val queueJsonStrings = queueJsonStringsState.value
    val queue = queueJsonStrings.mapNotNull {
        try {
            val json = JSONObject(it)
            Song(
                videoId = json.optString("videoId"),
                title = json.optString("title"),
                artist = json.optString("artist"),
                thumbnailUrl = json.optString("thumbnailUrl", null)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    val playbackStateState = viewModel.observePlayback(roomId).collectAsState(initial = null)
    val playbackState = playbackStateState.value
    
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(room.name, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("${room.members.size} members", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Row(modifier = Modifier.padding(end = 8.dp)) {
                        room.members.take(3).forEach { memberId ->
                            AsyncImage(
                                model = "https://ui-avatars.com/api/?name=${memberId}&background=random",
                                contentDescription = "Member",
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (isGuest) {
                        Toast.makeText(context, "Login to add songs", Toast.LENGTH_SHORT).show()
                    } else {
                        showAddSheet = true
                    }
                },
                icon = { Text("🎵") },
                text = { Text("Add to Queue") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Currently Playing Section
            val currentSongId = room.currentSong
            val currentSong = queue.find { it.videoId == currentSongId }
            
            if (currentSong != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = currentSong.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(200.dp)
                                .clip(MaterialTheme.shapes.medium)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(currentSong.title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(currentSong.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val controlsEnabled = isHost // simplify as per prompt for host or if host allowed
                            IconButton(onClick = { /* TODO */ }, enabled = controlsEnabled) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                            }
                            IconButton(
                                onClick = {
                                    /* TODO: toggle playback using viewModel */
                                },
                                enabled = controlsEnabled,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    if (playbackState?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            IconButton(onClick = { /* TODO */ }, enabled = controlsEnabled) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                            }
                        }
                    }
                }
            }
            
            Text(
                text = "Up Next",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            LazyColumn {
                items(queue) { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = "https://ui-avatars.com/api/?name=User&background=random", // Placeholder for adder avatar
                            contentDescription = "Added by",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(song.artist, fontSize = 12.sp, maxLines = 1)
                        }
                        if (isHost /* || song added by this user */) {
                            IconButton(onClick = { /* TODO: remove song */ }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}
