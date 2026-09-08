package com.music.bitchord.ui.social

import android.widget.Toast

import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.PersonAdd
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.jam.JamUserManager
import com.music.bitchord.data.jam.Room

import com.music.bitchord.data.jam.JamInvite
import com.music.bitchord.data.jam.PlaybackState
import com.music.bitchord.data.model.Song
import coil3.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
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
    val queue by remember(queueJsonStrings) { 
        derivedStateOf { 
            queueJsonStrings.mapNotNull {
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
        } 
    }
    
    val playbackStateState = viewModel.observePlayback(roomId).collectAsState(initial = null)
    val playbackState = playbackStateState.value
    
    var showAddSheet by remember { mutableStateOf(false) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showInviteSheet by remember { mutableStateOf(false) }

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
                        IconButton(onClick = { showInviteSheet = true }) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = "Invite")
                        }
                        if (isHost) {
                            IconButton(onClick = { showSettingsSheet = true }) {
                                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                            }
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
            val error by viewModel.error.collectAsState()
            var isReconnecting by remember { mutableStateOf(false) }
            
            LaunchedEffect(error) {
                if (error?.contains("network", ignoreCase = true) == true) {
                    isReconnecting = true
                    while(isReconnecting) {
                        delay(5000)
                        viewModel.clearError()
                        // retry logic would go here
                        isReconnecting = false // Assume success for now, actual implementation would check connection
                    }
                }
            }

            if (isReconnecting) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(16.dp))
                        Text("Reconnecting...", color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }

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

        if (showInviteSheet) {
            ModalBottomSheet(onDismissRequest = { showInviteSheet = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Invite Friends", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(16.dp))
                    
                    val link = "https://bitchord.app/jam/${room.id}"
                    
                    ListItem(
                        headlineContent = { Text("Share Link") },
                        leadingContent = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Join my Jam on BitChord: $link")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Jam Link"))
                            showInviteSheet = false
                        }
                    )
                    
                    ListItem(
                        headlineContent = { Text("Share via WhatsApp") },
                        leadingContent = { Text("💬", fontSize = 24.sp) }, // Placeholder for WhatsApp icon
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                setPackage("com.whatsapp")
                                putExtra(Intent.EXTRA_TEXT, "Join my Jam on BitChord: $link")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                            }
                            showInviteSheet = false
                        }
                    )
                    
                    ListItem(
                        headlineContent = { Text("Copy Link") },
                        leadingContent = { Text("🔗", fontSize = 24.sp) },
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Jam Link", link))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            showInviteSheet = false
                        }
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }

        if (showAddSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Search and Add Songs", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Search functionality coming soon...")
                    Spacer(Modifier.height(32.dp))
                }
            }
        }


        val joinRequestsState = viewModel.observeJoinRequests(roomId).collectAsState(initial = emptyList())
        val joinRequests = joinRequestsState.value
        
        if (isHost && joinRequests.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Join Requests") },
                text = { 
                    Column {
                        joinRequests.forEach { req ->
                            Text("${req.fromUserId} wants to join your Jam")
                            Row {
                                TextButton(onClick = { viewModel.handleJoinRequest(req.id, roomId, req.fromUserId, false) }) { Text("Decline") }
                                TextButton(onClick = { viewModel.handleJoinRequest(req.id, roomId, req.fromUserId, true) }) { Text("Accept") }
                            }
                        }
                    }
                },
                confirmButton = { }
            )
        }

        if (showSettingsSheet && isHost) {
            var showEndConfirm by remember { mutableStateOf(false) }
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Jam Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("Who can join", fontWeight = FontWeight.Bold)
                    var privacyOption by remember { mutableStateOf(room.privacy) }
                    val privacyOptions = listOf("everyone", "friends", "invite_only")
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        privacyOptions.forEach { option ->
                            FilterChip(
                                selected = privacyOption == option,
                                onClick = {
                                    privacyOption = option
                                    viewModel.updatePrivacy(room.id, option)
                                },
                                label = { Text(option.replace("_", " ").replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Who can add tracks", fontWeight = FontWeight.Bold)
                            Text(if (room.canGuestsAdd) "Everyone" else "Host only", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = room.canGuestsAdd,
                            onCheckedChange = { 
                                viewModel.updateRoomPermissions(room.id, it, room.canGuestsSkip) 
                            }
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Who can skip", fontWeight = FontWeight.Bold)
                            Text(if (room.canGuestsSkip) "Everyone" else "Host only", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = room.canGuestsSkip,
                            onCheckedChange = { 
                                viewModel.updateRoomPermissions(room.id, room.canGuestsAdd, it) 
                            }
                        )
                    }
                    Spacer(Modifier.height(32.dp))

                    Button(
                        onClick = { showEndConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("End Jam")
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }

            if (showEndConfirm) {
                AlertDialog(
                    onDismissRequest = { showEndConfirm = false },
                    title = { Text("End Jam?") },
                    text = { Text("Are you sure you want to end this jam session for everyone?") },
                    confirmButton = { TextButton(onClick = { viewModel.endRoom(room.id); onBack() }) { Text("End Jam") } },
                    dismissButton = { TextButton(onClick = { showEndConfirm = false }) { Text("Cancel") } }
                )
            }
        }

        if (isHost) {
            var showAutoEndDialog by remember { mutableStateOf(false) }
            LaunchedEffect(queue.isEmpty(), playbackState?.isPlaying) {
                if (queue.isEmpty() && playbackState?.isPlaying != true) {
                    kotlinx.coroutines.delay(10 * 60 * 1000L) // 10 minutes
                    showAutoEndDialog = true
                }
            }
            if (showAutoEndDialog) {
                AlertDialog(
                    onDismissRequest = { showAutoEndDialog = false },
                    title = { Text("Jam Inactive") },
                    text = { Text("Your Jam seems inactive — End it or keep it going?") },
                    confirmButton = { TextButton(onClick = { viewModel.endRoom(room.id); onBack() }) { Text("End") } },
                    dismissButton = { TextButton(onClick = { showAutoEndDialog = false }) { Text("Continue") } }
                )
            }
        }

        // Handle when room ends
        LaunchedEffect(rooms) {
            if (rooms.isNotEmpty() && rooms.none { it.id == roomId }) {
                // Room no longer exists
            }
        }
        
        if (rooms.isNotEmpty() && rooms.none { it.id == roomId }) {
            AlertDialog(
                onDismissRequest = { onBack() },
                title = { Text("Jam Ended") },
                text = { Text("This Jam has ended") },
                confirmButton = {
                    TextButton(onClick = onBack) {
                        Text("Back to Social")
                    }
                }
            )
        }
    }
}
