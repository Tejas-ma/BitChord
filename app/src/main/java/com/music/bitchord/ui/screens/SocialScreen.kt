package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.bitchord.ui.social.JamViewModel

import com.music.bitchord.data.jam.JamUserManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.ui.social.MoodSummaryScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    onNavigateToJamRoom: (String) -> Unit
) {
    val viewModel: JamViewModel = viewModel()
    val context = LocalContext.current
    val currentUserId = JamUserManager.getCurrentUserId(context)

    

    val rooms by viewModel.rooms.collectAsState()
    val friendsListening by viewModel.friendsListening.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    // Detect if a friend started a new Jam
    var notifiedRooms by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(rooms) {
        val newRooms = rooms.filter { room ->
            val isHostFriend = friendsListening.any { it.userId == room.hostId }
            val isNotNotified = !notifiedRooms.contains(room.id)
            isHostFriend && isNotNotified
        }
        newRooms.forEach { room ->
            notifiedRooms = notifiedRooms + room.id
            val friendName = friendsListening.find { it.userId == room.hostId }?.username ?: "A friend"
            val result = snackbarHostState.showSnackbar(
                message = "$friendName started a Jam 🎵 — Tap to join",
                actionLabel = "Join",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (room.privacy == "invite_only") {
                    viewModel.requestToJoin(room.id, currentUserId)
                } else {
                    viewModel.joinRoom(room.id, currentUserId)
                    onNavigateToJamRoom(room.id)
                }
            }
        }
    }

    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var showCreateRoom by remember { mutableStateOf(false) }

    var showMoodSummary by remember { mutableStateOf(false) }


    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateRoom = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Text("+", color = MaterialTheme.colorScheme.onBackground)
            }
        }
    ) { padding ->

        val isGuest = JamUserManager.isGuest(context)

        if (isGuest) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sign in to use Social features", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { /* Navigate to existing login */ }) {
                        Text("Sign In")
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Text("Guest View (Read-Only)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {

            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                if (error != null && error!!.contains("network", ignoreCase = true)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("No connection", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearError(); /* retry load */ }) {
                                Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
                
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "😎",
                        modifier = Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "You",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Button(
                    onClick = { showMoodSummary = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("My Mood")
                }
            }
            
            Text(
                    "Active Rooms", 
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (rooms.isEmpty()) {
                    Text(
                        "No active rooms",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                } else {
                    rooms.forEach { room ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    room.name, 
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                val isMember = room.members.contains(currentUserId)
                                if (room.privacy == "invite_only" && !isMember && room.hostId != currentUserId) {
                                    Button(
                                        onClick = {
                                            viewModel.requestToJoin(room.id, currentUserId)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text("Request to Join", color = MaterialTheme.colorScheme.onSecondary)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            viewModel.joinRoom(room.id, currentUserId)
                                            onNavigateToJamRoom(room.id)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("Join", color = MaterialTheme.colorScheme.onBackground)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "Friends Listening", 
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (friendsListening.isEmpty()) {
                    Text(
                        "No friends listening",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                } else {
                    friendsListening.forEach { friend ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "🔥",
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        friend.username,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }

                                Text(
                                    "is listening to ${friend.song}",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    if (showCreateRoom) {
        ModalBottomSheet(
            onDismissRequest = { showCreateRoom = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            var roomName by remember { mutableStateOf("") }
            var privacy by remember { mutableStateOf("everyone") }
            
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("Room Name", color = MaterialTheme.colorScheme.onBackground) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = privacy == "everyone",
                        onClick = { privacy = "everyone" },
                        label = { Text("Everyone", color = MaterialTheme.colorScheme.onBackground) }
                    )
                    FilterChip(
                        selected = privacy == "friends",
                        onClick = { privacy = "friends" },
                        label = { Text("Friends", color = MaterialTheme.colorScheme.onBackground) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.createRoom(roomName, "my_user_id", privacy)
                        showCreateRoom = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Start Jam", color = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showMoodSummary) {
        Dialog(
            onDismissRequest = { showMoodSummary = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            MoodSummaryScreen(onBack = { showMoodSummary = false })
        }
    }
}

