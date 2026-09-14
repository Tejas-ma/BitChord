package com.music.bitchord.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.jam.JamRoom
import com.music.bitchord.data.jam.JamQueueItem

@Composable
fun JamRoomScreen(
    room: JamRoom,
    currentUserId: String?,
    jamViewModel: JamViewModel,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queue by jamViewModel.queue.collectAsStateWithLifecycle()
    val nowPlayingTitle by jamViewModel.nowPlayingTitle.collectAsStateWithLifecycle()
    val nowPlayingArtist by jamViewModel.nowPlayingArtist.collectAsStateWithLifecycle()
    val isHost = currentUserId == room.hostId
    var showMenu by remember { mutableStateOf(false) }
    var allowOthersToPlay by remember {
        mutableStateOf(room.allowOthersToPlay)
    }
    var allowInvite by remember {
        mutableStateOf(room.allowInvite)
    }
    var showAddSongs by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onLeave) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Leave"
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (room.privacy == "private")
                        "Private" else "Public",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Code: ${room.id.take(6).uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Settings"
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (isHost) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.SpaceBetween,
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {
                                    Text("Others can play")
                                    Switch(
                                        checked = allowOthersToPlay,
                                        onCheckedChange = {
                                            allowOthersToPlay = it
                                            jamViewModel.updateRoomSettings(
                                                room.id,
                                                it,
                                                allowInvite
                                            )
                                        }
                                    )
                                }
                            },
                            onClick = {}
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.SpaceBetween,
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {
                                    Text("Anyone can invite")
                                    Switch(
                                        checked = allowInvite,
                                        onCheckedChange = {
                                            allowInvite = it
                                            jamViewModel.updateRoomSettings(
                                                room.id,
                                                allowOthersToPlay,
                                                it
                                            )
                                        }
                                    )
                                }
                            },
                            onClick = {}
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "End Room",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                jamViewModel.endRoom(room.id)
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Leave Room") },
                            onClick = {
                                showMenu = false
                                onLeave()
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // Now Playing placeholder
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Now Playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = nowPlayingTitle ?: "No song playing",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (nowPlayingArtist != null) {
                        Text(
                            text = nowPlayingArtist!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Queue header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Next from ${room.name}",
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = { showAddSongs = true }) {
                Text("+ Add songs")
            }
        }

        // Queue list — Spotify style
        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Queue is empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(queue) { index, item ->
                    JamQueueRow(item = item, index = index)
                }
            }
        }
    }

    if (showAddSongs) {
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showAddSongs = false },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add to Queue",
                    style = MaterialTheme.typography
                        .titleMedium
                )
                var searchQuery by remember {
                    mutableStateOf("")
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search songs") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Search coming in next update",
                    style = MaterialTheme.typography
                        .bodySmall,
                    color = MaterialTheme.colorScheme
                        .onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun JamQueueRow(item: JamQueueItem, index: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Artwork placeholder
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Added by ${item.addedBy}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Drag handle
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Drag",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
