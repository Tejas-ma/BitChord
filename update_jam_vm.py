import re

with open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'r') as f:
    content = f.read()

import_auth = "import io.github.jan.supabase.gotrue.auth\n"
if import_auth not in content:
    content = content.replace("import io.github.jan.supabase.postgrest.postgrest", "import io.github.jan.supabase.postgrest.postgrest\n" + import_auth)

active_room_id = """
    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId.asStateFlow()

    private val _myRooms = MutableStateFlow<List<Room>>(emptyList())
    val myRooms: StateFlow<List<Room>> = _myRooms.asStateFlow()
"""
if "_activeRoomId" not in content:
    content = content.replace("private val _rooms = MutableStateFlow<List<Room>>(emptyList())", active_room_id + "\n    private val _rooms = MutableStateFlow<List<Room>>(emptyList())")

join_room = """
    fun joinRoom(roomId: String) {
        _activeRoomId.value = roomId
    }
"""
if "fun joinRoom(roomId: String) {" not in content:
    content = content.replace("fun joinRoom(roomId: String, userId: String) {", join_room + "\n    fun joinRoom(roomId: String, userId: String) {")

load_rooms = """
    fun loadRooms() {
        viewModelScope.launch {
            try {
                repository.getRooms().collect {
                    _rooms.value = it
                    val userId = supabase.auth.currentUserOrNull()?.id
                    if (userId != null) {
                        _myRooms.value = it.filter { room -> room.hostId == userId }
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }
"""

# Replace existing loadRooms (private fun loadRooms)
content = re.sub(r'private fun loadRooms\(\) \{.*?\n    \}', load_rooms.strip(), content, flags=re.DOTALL)

with open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'w') as f:
    f.write(content)
