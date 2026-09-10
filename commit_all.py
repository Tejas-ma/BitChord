import sys
import re

file_path = "app/src/main/java/com/music/bitchord/MainActivity.kt"
with open(file_path, "r") as f:
    content = f.read()

# Make sure it's modified
imports = """import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
"""
if "import androidx.compose.material3.FloatingActionButton" not in content:
    content = re.sub(r'import androidx\.compose\.material3\.MaterialTheme\n', r'import androidx.compose.material3.MaterialTheme\n' + imports, content, count=1)

if "var showStartJamDialog by remember" not in content:
    content = re.sub(r'var showAccountSelector by remember \{ mutableStateOf\(false\) \}\n', r'var showAccountSelector by remember { mutableStateOf(false) }\n    var showStartJamDialog by remember { mutableStateOf(false) }\n', content, count=1)

if "BottomTab(searchLabel, BitChordIcons.Search)" in content:
    content = re.sub(r'            BottomTab\(searchLabel, BitChordIcons\.Search\),\n', r'', content, count=1)

search_icon_code = """                            if (!showSettings && !showAccountScrobbling && detail == null) {
                                IconButton(
                                    onClick = {
                                        if (selectedTab == TAB_SEARCH) {
                                            searchFocusTrigger++
                                        } else {
                                            searchFocusTrigger = 0
                                            viewModel.clearDetail()
                                            viewModel.closeMoodGenre()
                                            showSettings = false
                                            showAccountScrobbling = false
                                            showSources = false
                                            showReplay = false
                                            showHistory = false
                                            libraryShowAll = null
                                            selectedTab = TAB_SEARCH
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = stringResource(R.string.search),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
"""

if "IconButton(" not in content.split("TopBarDownloadButton(onClick")[0][-500:]:
    search_pattern = r"                            TopBarDownloadButton\(onClick = \{ showDownloadManager = true \}\)"
    replace_with = search_icon_code + "                            TopBarDownloadButton(onClick = { showDownloadManager = true })"
    content = re.sub(search_pattern, replace_with, content)


fab_code = """                    player.song?.takeUnless { playerDocked }?.let { song ->
                        Box(modifier = Modifier.fillMaxWidth().padding(start = PAGE_GUTTER, bottom = 4.dp)) {
                            FloatingActionButton(
                                onClick = {
                                    if (activeJamRoomId == null) {
                                        showStartJamDialog = true
                                    } else {
                                        val current = activeJamRoomId
                                        activeJamRoomId = null
                                        activeJamRoomId = current
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                containerColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(BitChordIcons.MusicNote, contentDescription = "Start Jam")
                            }
                        }
"""
if "FloatingActionButton" not in content.split("MiniPlayer(")[0][-500:]:
    content = re.sub(r"                    player\.song\?\.takeUnless \{ playerDocked \}\?\.let \{ song ->", fab_code, content)

    miniplayer_call = r"""                        MiniPlayer\(
                            song = song,
                            isPlaying = player.isPlaying,
                            isLoading = player.isLoading,
                            hazeState = hazeState,
                            onPlayPause = \{
                                controller\?\.let \{ if \(it\.isPlaying\) it\.pause\(\) else it\.play\(\) \}
                            \},
                            onNext = \{ controller\?\.seekToNextMediaItem\(\) \},
                            onExpand = \{ showNowPlaying = true \},
                            modifier = Modifier\.fillMaxWidth\(\),
                            onJamClick = \{ if \(activeJamRoomId == null\) activeJamRoomId = "quick_jam" \}
                        \)"""

    new_miniplayer_call = """                        MiniPlayer(
                            song = song,
                            isPlaying = player.isPlaying,
                            isLoading = player.isLoading,
                            hazeState = hazeState,
                            onPlayPause = {
                                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            },
                            onNext = { controller?.seekToNextMediaItem() },
                            onExpand = { showNowPlaying = true },
                            modifier = Modifier.fillMaxWidth()
                        )"""
    content = re.sub(miniplayer_call, new_miniplayer_call, content)


dialog_code = """
        if (showStartJamDialog) {
            AlertDialog(
                onDismissRequest = { showStartJamDialog = false },
                confirmButton = {
                    TextButton(onClick = { showStartJamDialog = false }) { Text("Dismiss") }
                },
                title = { Text("Start Jam") },
                text = { Text("Start Jam — coming soon") }
            )
        }

"""
if "AlertDialog" not in content.split("editingSource?.let { config ->")[0][-500:]:
    content = re.sub(r"        editingSource\?\.let \{ config ->", dialog_code + r"        editingSource?.let { config ->", content)

with open(file_path, "w") as f:
    f.write(content)


file_path_mini = "app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt"
with open(file_path_mini, "r") as f:
    content_mini = f.read()

if "onJamClick:" in content_mini:
    content_mini = re.sub(r"    onJamClick: \(\) -> Unit,\n", "", content_mini)

jam_icon_pattern = r"            Spacer\(Modifier\.width\(TRANSPORT_GAP\)\)\n            IconButton\(\n                onClick = \{\n                    onJamClick\(\)\n                \},\n                modifier = Modifier\.size\(GLYPH_SLOT\),\n            \) \{\n                Icon\(\n                    Icons\.Rounded\.People,\n                    contentDescription = \"Quick Jam\",\n                    tint = MaterialTheme\.colorScheme\.onBackground,\n                    modifier = Modifier\.size\(GLYPH_SIZE\),\n                \)\n            \}"

if "Icons.Rounded.People" in content_mini:
    content_mini = re.sub(jam_icon_pattern, "", content_mini)

with open(file_path_mini, "w") as f:
    f.write(content_mini)
