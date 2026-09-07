package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.bitchord.ui.social.JamViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    onNavigateToJamRoom: (String) -> Unit
) {
    val viewModel: JamViewModel = viewModel()

    val rooms by viewModel.rooms.collectAsState()
    val friendsListening by viewModel.friendsListening.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showCreateRoom by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateRoom = true }) {
                Text("+")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Active Rooms", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                if (rooms.isEmpty()) {
                    Text("No active rooms")
                } else {
                    rooms.forEach { room ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(room.name, modifier = Modifier.weight(1f))
                            Button(onClick = {
                                viewModel.joinRoom(room.id, "my_user_id") // Should use real user id
                                onNavigateToJamRoom(room.id)
                            }) {
                                Text("Join")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("Friends Listening", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                if (friendsListening.isEmpty()) {
                    Text("No friends listening")
                } else {
                    friendsListening.forEach { friend ->
                        Text("${friend.username} is listening to ${friend.song}")
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    if (showCreateRoom) {
        ModalBottomSheet(onDismissRequest = { showCreateRoom = false }) {
            var roomName by remember { mutableStateOf("") }
            var privacy by remember { mutableStateOf("everyone") }

            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("Room Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = privacy == "everyone",
                        onClick = { privacy = "everyone" },
                        label = { Text("Everyone") }
                    )
                    FilterChip(
                        selected = privacy == "friends",
                        onClick = { privacy = "friends" },
                        label = { Text("Friends") }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.createRoom(roomName, "my_user_id", privacy)
                        showCreateRoom = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Jam")
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
