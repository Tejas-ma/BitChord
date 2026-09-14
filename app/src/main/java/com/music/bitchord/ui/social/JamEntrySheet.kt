package com.music.bitchord.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamEntrySheet(
    jamViewModel: JamViewModel,
    friendCode: String,
    onDismiss: () -> Unit,
    onRoomJoined: () -> Unit
) {
    val context = LocalContext.current
    val isScanning by jamViewModel.isScanning
        .collectAsStateWithLifecycle()
    val nearbyRoomIds by jamViewModel.nearbyRoomIds
        .collectAsStateWithLifecycle()
    val rooms by jamViewModel.rooms
        .collectAsStateWithLifecycle()
    val isBluetoothAvailable by jamViewModel
        .isBluetoothAvailable
        .collectAsStateWithLifecycle()
    val activeRoom by jamViewModel.activeRoom
        .collectAsStateWithLifecycle()

    var roomCode by remember { mutableStateOf("") }
    var showCodeError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        jamViewModel.startRoomDiscovery()
    }

    LaunchedEffect(activeRoom) {
        if (activeRoom != null) {
            jamViewModel.stopRoomDiscovery()
            onRoomJoined()
        }
    }

    val nearbyRooms = rooms.filter { room ->
        nearbyRoomIds.contains(room.id)
    }

    ModalBottomSheet(
        onDismissRequest = {
            jamViewModel.stopRoomDiscovery()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Friend code display
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme
                    .secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Your Friend Code",
                            style = MaterialTheme.typography
                                .labelSmall,
                            color = MaterialTheme.colorScheme
                                .onSecondaryContainer
                        )
                        Text(
                            text = friendCode,
                            style = MaterialTheme.typography
                                .titleMedium,
                            color = MaterialTheme.colorScheme
                                .onSecondaryContainer,
                            letterSpacing = 4.sp
                        )
                    }
                    IconButton(onClick = {
                        val clipboard = context
                            .getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Friend Code",
                                friendCode
                            )
                        )
                    }) {
                        Icon(
                            imageVector =
                                Icons.Default.ContentCopy,
                            contentDescription = "Copy code",
                            tint = MaterialTheme.colorScheme
                                .onSecondaryContainer
                        )
                    }
                }
            }

            Text(
                text = "Join a Jam",
                style = MaterialTheme.typography.titleLarge
            )

            // BLE scanning section
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.People,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme
                                .primary
                        )
                        Text(
                            text = "Nearby Rooms",
                            style = MaterialTheme.typography
                                .titleSmall
                        )
                    }
                    if (!isBluetoothAvailable) {
                        Text(
                            text = "Enable Bluetooth to discover nearby rooms",
                            style = MaterialTheme.typography
                                .bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (nearbyRooms.isEmpty()) {
                            Text(
                                text = "Scanning for nearby rooms...",
                                style = MaterialTheme.typography
                                    .bodySmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        } else {
                            nearbyRooms.forEach { room ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.SpaceBetween,
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = room.name,
                                            style = MaterialTheme
                                                .typography
                                                .bodyMedium
                                        )
                                        Text(
                                            text = if (room
                                                .privacy ==
                                                "private")
                                                "🔒 Private"
                                            else "🌐 Public",
                                            style = MaterialTheme
                                                .typography
                                                .bodySmall,
                                            color = MaterialTheme
                                                .colorScheme
                                                .onSurfaceVariant
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            jamViewModel
                                                .joinRoom(room)
                                        },
                                        shape =
                                            RoundedCornerShape(
                                                12.dp
                                            )
                                    ) {
                                        Text("Join")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Room code entry
            Text(
                text = "Enter Room Code",
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = roomCode,
                    onValueChange = {
                        roomCode = it.uppercase().take(6)
                        showCodeError = false
                    },
                    label = { Text("6-character code") },
                    singleLine = true,
                    isError = showCodeError,
                    supportingText = if (showCodeError) {
                        { Text("Room not found") }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = {
                        if (roomCode.length == 6) {
                            jamViewModel.joinRoomByCode(
                                roomCode
                            )
                        } else {
                            showCodeError = true
                        }
                    },
                    enabled = roomCode.length == 6,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Join")
                }
            }

            HorizontalDivider()

            Text(
                text = "Add a Friend",
                style = MaterialTheme.typography.titleSmall
            )

            var friendCodeInput by remember {
                mutableStateOf("")
            }
            var friendRequestSent by remember {
                mutableStateOf(false)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = friendCodeInput,
                    onValueChange = {
                        friendCodeInput =
                            it.uppercase().take(6)
                        friendRequestSent = false
                    },
                    label = { Text("Friend's code") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = {
                        if (friendCodeInput.length == 6) {
                            jamViewModel.sendFriendRequest(
                                friendCodeInput
                            )
                            friendRequestSent = true
                            friendCodeInput = ""
                        }
                    },
                    enabled = friendCodeInput.length == 6,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (friendRequestSent) "Sent!"
                        else "Add"
                    )
                }
            }
        }
    }
}
