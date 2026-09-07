import re

with open('./app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt', 'r') as f:
    code = f.read()

# Add imports
imports = """
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.rounded.People
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import com.music.bitchord.ui.social.JamRoomScreen
"""
code = code.replace("import androidx.compose.material3.Text\n", "import androidx.compose.material3.Text\n" + imports)

# Check if already added
if "Icons.Rounded.People" in code:
    print("Already added")
    exit(0)

# Modify MiniPlayer function to add states
state_declarations = """
    var showCreateRoom by remember { mutableStateOf(false) }
    var roomName by remember { mutableStateOf(song.title) }
    var selectedPrivacy by remember { mutableStateOf("Everyone") }
    var inJamRoom by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
"""
code = code.replace("val shape = RoundedCornerShape(percent = 50)", state_declarations + "\n    val shape = RoundedCornerShape(percent = 50)")

# Add button at the end of controls row
button_code = """
            IconButton(
                onClick = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                modifier = Modifier.size(GLYPH_SLOT),
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.widget_next),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
            Spacer(Modifier.width(TRANSPORT_GAP))
            IconButton(
                onClick = {
                    showCreateRoom = true
                },
                modifier = Modifier.size(GLYPH_SLOT),
            ) {
                Icon(
                    Icons.Rounded.People,
                    contentDescription = "Quick Jam",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
"""
code = code.replace("""
            IconButton(
                onClick = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                modifier = Modifier.size(GLYPH_SLOT),
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.widget_next),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }""", button_code)

# Add ModalBottomSheet and Dialog outside the Box
sheet_code = """
    if (showCreateRoom) {
        ModalBottomSheet(
            onDismissRequest = { showCreateRoom = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Start a Quick Jam", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("Room Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("Everyone", "Friends", "Invite Only").forEach { privacy ->
                        FilterChip(
                            selected = selectedPrivacy == privacy,
                            onClick = { selectedPrivacy = privacy },
                            label = { Text(privacy) }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        showCreateRoom = false
                        inJamRoom = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Jam")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (inJamRoom) {
        Dialog(
            onDismissRequest = { inJamRoom = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            JamRoomScreen(roomName = roomName, onBack = { inJamRoom = false })
        }
    }
}"""
# The function ends with } 
code = code.replace("        }\n    }\n}", "        }\n    }\n" + sheet_code)
code = code.replace("@OptIn(ExperimentalHazeMaterialsApi::class)", "@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalMaterial3Api::class)")

with open('./app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt', 'w') as f:
    f.write(code)

print("Patched MiniPlayer.kt")
