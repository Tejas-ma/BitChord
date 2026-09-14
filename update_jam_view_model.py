import re

file_path = "app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt"
with open(file_path, "r") as f:
    content = f.read()

# 1. Update init block
old_init = """    init {
        loadRooms()
        loadFriends()
        viewModelScope.launch {
            subscribeToRooms()
            subscribeToFriendRequests()
        }
    }"""

new_init = """    init {
        loadRooms()
        loadFriends()
        subscribeToRooms()
        viewModelScope.launch {
            subscribeToFriendRequests()
        }
    }"""

content = content.replace(old_init, new_init)


# 2. Update subscribeToRooms function
# Find the start and end of subscribeToRooms
import re

pattern = r"    private suspend fun subscribeToRooms\(\) \{.*?\n    \}"
new_subscribe_code = """    private fun subscribeToRooms() {
        viewModelScope.launch {
            try {
                supabase.realtime.connect()
                roomsChannel = supabase.realtime
                    .createChannel("public:rooms")
                roomsChannel!!
                    .postgresChangeFlow<PostgresAction.Insert>(
                        schema = "public"
                    ) { table = "rooms" }
                    .onEach { change ->
                        try {
                            val newRoom =
                                change.decodeRecord<JamRoom>()
                            if (newRoom.isActive != false) {
                                _rooms.value =
                                    (_rooms.value + newRoom)
                                    .distinctBy { it.id }
                                if (newRoom.hostId ==
                                    localUserId) {
                                    _myRooms.value =
                                        (_myRooms.value +
                                        newRoom)
                                        .distinctBy { it.id }
                                }
                            }
                        } catch (e: Exception) { }
                    }
                    .launchIn(viewModelScope)

                roomsChannel!!
                    .postgresChangeFlow<PostgresAction.Update>(
                        schema = "public"
                    ) { table = "rooms" }
                    .onEach { change ->
                        try {
                            val updated =
                                change.decodeRecord<JamRoom>()
                            _rooms.value = _rooms.value.map {
                                if (it.id == updated.id)
                                    updated else it
                            }
                            _myRooms.value =
                                _myRooms.value.map {
                                if (it.id == updated.id)
                                    updated else it
                            }
                            if (updated.isActive == false) {
                                _rooms.value = _rooms.value
                                    .filter {
                                        it.id != updated.id
                                    }
                                _myRooms.value =
                                    _myRooms.value
                                    .filter {
                                        it.id != updated.id
                                    }
                            }
                        } catch (e: Exception) { }
                    }
                    .launchIn(viewModelScope)

                roomsChannel!!.subscribe()
            } catch (e: Exception) {
                _error.value = "Could not subscribe to rooms"
            }
        }
    }"""

content = re.sub(pattern, new_subscribe_code, content, flags=re.DOTALL)

with open(file_path, "w") as f:
    f.write(content)

print("Updated successfully")
