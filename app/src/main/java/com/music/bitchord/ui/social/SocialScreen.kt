package com.music.bitchord.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.jam.JamRoom

@Composable
fun SocialScreen(
    jamViewModel: JamViewModel,
    modifier: Modifier = Modifier
) {
    val rooms by jamViewModel.rooms.collectAsStateWithLifecycle()
    val myRooms by jamViewModel.myRooms.collectAsStateWithLifecycle()

    var showCreateRoomDialog by remember { mutableStateOf(false) }
    var sessionName by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Find the actual BitChord logo drawable name in res/drawable
            // and use it here. If ic_launcher_foreground exists use that.
            Text(
                text = "BitChord",
                style = MaterialTheme.typography.titleLarge
            )
            Surface(
                onClick = { showCreateRoomDialog = true },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 12.dp, vertical = 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Create Room",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Active Rooms section
            Text(
                text = "Active Rooms",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (rooms.isEmpty()) {
                SectionEmptyBox(text = "No active rooms")
            } else {
                rooms.forEach { room ->
                    RoomCard(
                        room = room,
                        onJoin = { jamViewModel.joinRoom(room) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Friends section
            Text(
                text = "Friends",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            SectionEmptyBox(text = "No friends listening")

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // My Rooms section
            Text(
                text = "My Rooms",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (myRooms.isEmpty()) {
                SectionEmptyBox(text = "No rooms yet")
            } else {
                myRooms.forEach { room ->
                    RoomCard(
                        room = room,
                        onJoin = { jamViewModel.joinRoom(room) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    if (showCreateRoomDialog) {
        AlertDialog(
            onDismissRequest = { showCreateRoomDialog = false },
            title = { Text("Create Room") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = sessionName,
                        onValueChange = { sessionName = it },
                        label = { Text("Session name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Private room")
                        Switch(
                            checked = isPrivate,
                            onCheckedChange = { isPrivate = it }
                        )
                    }
                    Text(
                        text = "Max members: 8",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (sessionName.isNotBlank()) {
                        jamViewModel.createRoom(
                            name = sessionName.trim(),
                            isPrivate = isPrivate,
                            maxMembers = 8
                        )
                        showCreateRoomDialog = false
                        sessionName = ""
                        isPrivate = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRoomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RoomCard(room: JamRoom, onJoin: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (room.privacy == "private") "Private" else "Public",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onJoin,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Join")
            }
        }
    }
}

@Composable
fun SectionEmptyBox(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
