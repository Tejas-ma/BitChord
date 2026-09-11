package com.music.bitchord.ui.social

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.jam.JamRoom
import com.music.bitchord.supabase
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class JamViewModel(application: Application) : AndroidViewModel(application) {

    private val supabaseClient = supabase

    private val _rooms = MutableStateFlow<List<JamRoom>>(emptyList())
    val rooms: StateFlow<List<JamRoom>> = _rooms

    private val _myRooms = MutableStateFlow<List<JamRoom>>(emptyList())
    val myRooms: StateFlow<List<JamRoom>> = _myRooms

    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun createRoom(name: String, isPrivate: Boolean, maxMembers: Int) {
        viewModelScope.launch {
            try {
                val userId = supabaseClient.auth.currentUserOrNull()?.id
                    ?: return@launch
                val result = supabaseClient.postgrest["rooms"].insert(
                    mapOf(
                        "name" to name,
                        "is_private" to isPrivate,
                        "max_members" to maxMembers,
                        "host_id" to userId
                    )
                ) { select() }.decodeSingle<JamRoom>()
                _activeRoomId.value = result.id
                loadRooms()
            } catch (e: Exception) {
                _error.value = "Could not create room. Try again."
            }
        }
    }

    fun loadRooms() {
        viewModelScope.launch {
            try {
                val userId = supabaseClient.auth.currentUserOrNull()?.id
                    ?: return@launch
                val allRooms = supabaseClient.postgrest["rooms"]
                    .select()
                    .decodeList<JamRoom>()
                _rooms.value = allRooms
                _myRooms.value = allRooms.filter { it.hostId == userId }
            } catch (e: Exception) {
                _error.value = "Could not load rooms."
            }
        }
    }

    fun joinRoom(roomId: String) {
        viewModelScope.launch {
            try {
                _activeRoomId.value = roomId
            } catch (e: Exception) {
                _error.value = "Could not join room."
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
