import sys

content = open('app/src/main/java/com/music/bitchord/ui/social/JamRoomScreen.kt').read()

imports = """
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.PersonAdd
"""

if "import android.content.Intent" not in content:
    content = content.replace("import android.widget.Toast\n", "import android.widget.Toast\n" + imports)

topbar_mod = """
                        if (isHost) {
                            IconButton(onClick = { showSettingsSheet = true }) {
                                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                            }
                        }
"""

topbar_rep = """
                        IconButton(onClick = { showInviteSheet = true }) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = "Invite")
                        }
                        if (isHost) {
                            IconButton(onClick = { showSettingsSheet = true }) {
                                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                            }
                        }
"""

content = content.replace(topbar_mod, topbar_rep)

sheet_mod = """
    var showSettingsSheet by remember { mutableStateOf(false) }
"""

sheet_rep = """
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showInviteSheet by remember { mutableStateOf(false) }
"""

content = content.replace(sheet_mod, sheet_rep)

bottom_sheet_mod = """
        if (showAddSheet) {
"""

bottom_sheet_rep = """
        if (showInviteSheet) {
            ModalBottomSheet(onDismissRequest = { showInviteSheet = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Invite Friends", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(16.dp))
                    
                    val link = "https://bitchord.app/jam/${room.id}"
                    
                    ListItem(
                        headlineContent = { Text("Share Link") },
                        leadingContent = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Join my Jam on BitChord: $link")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Jam Link"))
                            showInviteSheet = false
                        }
                    )
                    
                    ListItem(
                        headlineContent = { Text("Share via WhatsApp") },
                        leadingContent = { Text("💬", fontSize = 24.sp) }, // Placeholder for WhatsApp icon
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                setPackage("com.whatsapp")
                                putExtra(Intent.EXTRA_TEXT, "Join my Jam on BitChord: $link")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                            }
                            showInviteSheet = false
                        }
                    )
                    
                    ListItem(
                        headlineContent = { Text("Copy Link") },
                        leadingContent = { Text("🔗", fontSize = 24.sp) },
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Jam Link", link))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            showInviteSheet = false
                        }
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }

        if (showAddSheet) {
"""

content = content.replace(bottom_sheet_mod, bottom_sheet_rep)

open('app/src/main/java/com/music/bitchord/ui/social/JamRoomScreen.kt', 'w').write(content)
print("Success")
