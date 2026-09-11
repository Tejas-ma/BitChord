import re
import os

# --- 1. Fix FrostedTopBar brace wrap ---
file_path_main = "app/src/main/java/com/music/bitchord/MainActivity.kt"
with open(file_path_main, "r") as f:
    main_content = f.read()

# Replace: `if (selectedTab != TAB_EXPLORE) FrostedTopBar(`
# With: `if (selectedTab != TAB_EXPLORE) {\n                    FrostedTopBar(`
main_content = main_content.replace(
    "                if (selectedTab != TAB_EXPLORE) FrostedTopBar(",
    "                if (selectedTab != TAB_EXPLORE) {\n                    FrostedTopBar("
)

# Then find the closing ) of FrostedTopBar
# It is the ) on its own line immediately before this comment: "// Drawn before the bars so their own glass reads on top of it."
# "After that closing ), add a new line: }"
closing_brace_replacement = """                    },
                )
                }

                // Drawn before the bars so their own glass reads on top of it."""
main_content = main_content.replace(
    "                    },\n                )\n\n                // Drawn before the bars so their own glass reads on top of it.",
    closing_brace_replacement
)

# Also wrap TopFadeBlur
top_fade_blur_original = """                val isDetailVisible = detail != null && !isLocalDetail && !showSettings &&
                    !showAccountScrobbling && !showSources && !showReplay
                TopFadeBlur(
                    hazeState = hazeState,"""
top_fade_blur_new = """                val isDetailVisible = detail != null && !isLocalDetail && !showSettings &&
                    !showAccountScrobbling && !showSources && !showReplay
                if (selectedTab != TAB_EXPLORE) {
                TopFadeBlur(
                    hazeState = hazeState,"""
main_content = main_content.replace(top_fade_blur_original, top_fade_blur_new)

# And add the closing brace for TopFadeBlur
top_fade_blur_end_orig = """                    scrimColor = when {
                        showReplay -> Color.Black
                        isDetailVisible -> detailPalette.background
                        else -> MaterialTheme.colorScheme.background
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )"""
top_fade_blur_end_new = """                    scrimColor = when {
                        showReplay -> Color.Black
                        isDetailVisible -> detailPalette.background
                        else -> MaterialTheme.colorScheme.background
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                }"""
main_content = main_content.replace(top_fade_blur_end_orig, top_fade_blur_end_new)


# --- FIX 3 — Jam FAB bottom padding in MainActivity.kt ---
# Change its modifier to:
#     modifier = Modifier
#         .padding(bottom = 80.dp, end = 16.dp)
#         .align(Alignment.BottomEnd)
fab_old = r"""                        Box\(modifier = Modifier\.fillMaxWidth\(\)\.padding\(start = 10\.dp, bottom = 4\.dp\)\) \{
                            FloatingActionButton\("""
fab_new = """                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 80.dp, end = 16.dp), contentAlignment = Alignment.BottomEnd) {
                            FloatingActionButton("""
# Let's check what it currently looks like since the base branch was reset.
# It might be `Box(modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 80.dp), contentAlignment = Alignment.BottomEnd)` from my previous commit if it got merged.
# Or `Alignment.BottomStart` or something.
# The prompt says: "It currently uses Alignment.BottomStart or is left-aligned. Change its alignment to Alignment.BottomEnd"
# Wait, I'll search using regex to find the exact Box wrapping FloatingActionButton.
fab_box_pattern = r"                        Box\(modifier = Modifier\.fillMaxWidth\(\)\.[^)]+\)\)(, contentAlignment = Alignment\.[a-zA-Z]+)? \{\n                            FloatingActionButton\("
fab_box_new = r"""                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 80.dp, end = 16.dp), contentAlignment = Alignment.BottomEnd) {
                            FloatingActionButton("""
main_content = re.sub(fab_box_pattern, fab_box_new, main_content)

with open(file_path_main, "w") as f:
    f.write(main_content)


# --- FIX 2 — SocialScreen.kt full content replacement ---
social_screen_content = """package com.music.bitchord.ui.screens

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
                modifier = Modifier.height(32.dp),
                contentScale = ContentScale.Fit
            )
            IconButton(onClick = { showCreateRoomDialog.value = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create Room",
                    tint = MaterialTheme.colorScheme.onBackground
                )
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
"""

with open("app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt", "w") as f:
    f.write(social_screen_content)

