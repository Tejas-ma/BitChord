import sys

content = open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt').read()

imports = """
import com.music.bitchord.data.jam.JamInvite
import kotlinx.coroutines.flow.Flow
"""

if "import com.music.bitchord.data.jam.JamInvite" not in content:
    content = content.replace("import com.music.bitchord.data.jam.Room\n", "import com.music.bitchord.data.jam.Room\n" + imports)

methods = """
    fun observeJoinRequests(roomId: String): Flow<List<JamInvite>> {
        return repository.observeJoinRequests(roomId)
    }

    fun handleJoinRequest(requestId: String, roomId: String, userId: String, accept: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateInviteStatus(requestId, if (accept) "accepted" else "declined")
                if (accept) {
                    repository.joinRoom(roomId, userId)
                    loadRooms()
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }
"""

index = content.rfind("}")
if index != -1:
    content = content[:index] + methods + content[index:]
    open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'w').write(content)
    print("Success")
else:
    print("Failed")
