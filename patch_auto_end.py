import sys

content = open('app/src/main/java/com/music/bitchord/ui/social/JamRoomScreen.kt').read()

insertion = """
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
"""

index = content.rfind("    }\n}")
if index != -1:
    new_content = content[:index] + insertion + content[index:]
    open('app/src/main/java/com/music/bitchord/ui/social/JamRoomScreen.kt', 'w').write(new_content)
    print("Success")
else:
    print("Could not find insertion point")
