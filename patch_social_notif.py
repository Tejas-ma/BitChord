import sys

content = open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt').read()

notif = """
    val rooms by viewModel.rooms.collectAsState()
    val friendsListening by viewModel.friendsListening.collectAsState()

    // Detect if a friend started a new Jam
    LaunchedEffect(rooms) {
        val newRooms = rooms.filter { room -> 
            val isHostFriend = friendsListening.any { it.userId == room.hostId }
            val isRecent = true // simplified check for new room
            isHostFriend && isRecent
        }
        newRooms.forEach { room ->
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
"""

content = content.replace("    val rooms by viewModel.rooms.collectAsState()\n    val friendsListening by viewModel.friendsListening.collectAsState()", notif)

open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt', 'w').write(content)
print("Success")
