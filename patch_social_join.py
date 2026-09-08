import sys

content = open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt').read()

imports = """
import com.music.bitchord.data.jam.JamUserManager
import androidx.compose.ui.platform.LocalContext
"""

if "import com.music.bitchord.data.jam.JamUserManager" not in content:
    content = content.replace("import com.music.bitchord.ui.social.JamViewModel\n", "import com.music.bitchord.ui.social.JamViewModel\n" + imports)

context_mod = """    val viewModel: JamViewModel = viewModel()"""

context_rep = """    val viewModel: JamViewModel = viewModel()
    val context = LocalContext.current
    val currentUserId = JamUserManager.getCurrentUserId(context)
"""

content = content.replace(context_mod, context_rep)

btn_mod = """                                Button(
                                    onClick = { 
                                        viewModel.joinRoom(room.id, "my_user_id") // Should use real user id
                                        onNavigateToJamRoom(room.id) 
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Join", color = MaterialTheme.colorScheme.onBackground)
                                }"""

btn_rep = """                                val isMember = room.members.contains(currentUserId)
                                if (room.privacy == "invite_only" && !isMember && room.hostId != currentUserId) {
                                    Button(
                                        onClick = { 
                                            viewModel.requestToJoin(room.id, currentUserId)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text("Request to Join", color = MaterialTheme.colorScheme.onSecondary)
                                    }
                                } else {
                                    Button(
                                        onClick = { 
                                            viewModel.joinRoom(room.id, currentUserId)
                                            onNavigateToJamRoom(room.id) 
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("Join", color = MaterialTheme.colorScheme.onBackground)
                                    }
                                }"""

content = content.replace(btn_mod, btn_rep)

open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt', 'w').write(content)
print("Success")
