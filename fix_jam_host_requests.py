import sys

content = open('app/src/main/java/com/music/bitchord/ui/social/JamRoomScreen.kt').read()

import_req = """
import com.music.bitchord.data.jam.JamInvite
"""

if "import com.music.bitchord.data.jam.JamInvite" not in content:
    content = content.replace("import com.music.bitchord.data.jam.Room\n", "import com.music.bitchord.data.jam.Room\n" + import_req)

req_ui = """
        if (showSettingsSheet && isHost) {
"""

req_ui_rep = """
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
"""

content = content.replace(req_ui, req_ui_rep)

open('app/src/main/java/com/music/bitchord/ui/social/JamRoomScreen.kt', 'w').write(content)
print("Success")
