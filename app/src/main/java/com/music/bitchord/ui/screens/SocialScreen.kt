package com.music.bitchord.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    onNavigateToJamRoom: (String) -> Unit
) {
    val showCreateRoomDialog = remember { mutableStateOf(false) }
    val sessionName = remember { mutableStateOf("") }
    val isPrivate = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "BitChord",
                modifier = Modifier.height(48.dp).widthIn(min = 48.dp),
                contentScale = ContentScale.Fit
            )
            Surface(
                onClick = { showCreateRoomDialog.value = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Create Room",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Sections
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Active Rooms
            Text(
                text = "Active Rooms",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "No active rooms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Friends
            Text(
                text = "Friends",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "No friends listening",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // My Rooms
            Text(
                text = "My Rooms",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "No rooms yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showCreateRoomDialog.value) {
        AlertDialog(
            onDismissRequest = { showCreateRoomDialog.value = false },
            title = { Text("Create Room") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = sessionName.value,
                        onValueChange = { sessionName.value = it },
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
                            checked = isPrivate.value,
                            onCheckedChange = { isPrivate.value = it }
                        )
                    }
                    Text(
                        text = "Max members: 8",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCreateRoomDialog.value = false }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRoomDialog.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
