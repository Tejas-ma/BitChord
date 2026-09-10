package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.scale

import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Add
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.weight(1f).padding(end = 16.dp)
                ) {
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
                AnimatedVisibility(
                    visible = rooms.isEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Text(
                        "No active rooms",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                
                AnimatedVisibility(
                    visible = rooms.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Column {
                        rooms.forEach { room ->
                            // Pulse animation for Join button
                            val infiniteTransition = rememberInfiniteTransition()
                            val scale by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.05f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000),
                                    repeatMode = RepeatMode.Reverse
                                ), label = ""
                            )
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
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.scale(scale)
                                    ) {
                                        Text("Join", color = MaterialTheme.colorScheme.onBackground)
                                    }
                                }
                            }
                        }
                    }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "Friends",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                AnimatedVisibility(
                    visible = friendsListening.isEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Text(
                        "No friends listening",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                
                AnimatedVisibility(
                    visible = friendsListening.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Column {
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
            }


                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "My Rooms",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Coming soon",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

            Spacer(modifier = Modifier.height(72.dp))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    if (showCreateRoom) {
        var isPrivate by remember { mutableStateOf(false) }
        var roomName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateRoom = false },
            title = { Text("Create Room", color = MaterialTheme.colorScheme.onBackground) },
            text = {
                Column {
                    OutlinedTextField(
                        value = roomName,
                        onValueChange = { roomName = it },
                        label = { Text("Session name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Private room", color = MaterialTheme.colorScheme.onBackground)
                        Switch(
                            checked = isPrivate,
                            onCheckedChange = { isPrivate = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Max members: 8", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showCreateRoom = false }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRoom = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }


}
