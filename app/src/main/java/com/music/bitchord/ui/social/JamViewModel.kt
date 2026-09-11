package com.music.bitchord.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.jam.FriendActivity
import com.music.bitchord.data.jam.JamRepository
import com.music.bitchord.data.jam.Room
import com.music.bitchord.supabase
import io.github.jan.supabase.postgrest.postgrest


import com.music.bitchord.data.jam.JamInvite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class JamViewModel : ViewModel() {

    private val repository = JamRepository()


    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId.asStateFlow()

    private val _myRooms = MutableStateFlow<List<Room>>(emptyList())
    val myRooms: StateFlow<List<Room>> = _myRooms.asStateFlow()

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _friendsListening = MutableStateFlow<List<FriendActivity>>(emptyList())
    val friendsListening: StateFlow<List<FriendActivity>> = _friendsListening.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun createRoom(name: String, isPrivate: Boolean, maxMembers: Int) {
        viewModelScope.launch {
            try {
                supabase.postgrest["rooms"].insert(
                    mapOf(
                        "name" to name,
                        "is_private" to isPrivate,
                        "max_members" to maxMembers,
                        "host_id" to null
                    )
                )
            } catch (e: Exception) {
                _error.value = "Could not create room. Try again."
            }
        }
    }


    init {
        loadRooms()
        loadFriendsListening()
    }

    fun loadRooms() {
        viewModelScope.launch {
            try {
                repository.getRooms().collect {
                    _rooms.value = it
                    // Since auth is not resolved, fallback to no filtering or empty myRooms
                    _myRooms.value = it
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    private fun loadFriendsListening() {
        viewModelScope.launch {
            try {
                repository.getFriendsListening().collect {
                    _friendsListening.value = it
                }
            } catch (e: Exception) {
                _friendsListening.value = emptyList()
                _error.value = "Could not load activity. Pull to refresh."
            }
        }
    }

    fun createRoom(name: String, hostId: String, privacy: String = "everyone") {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.createRoom(name, hostId, privacy)
                loadRooms()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }


    fun joinRoom(roomId: String) {
        _activeRoomId.value = roomId
    }

    fun joinRoom(roomId: String, userId: String) {
        viewModelScope.launch {
            try {
                repository.joinRoom(roomId, userId)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun leaveRoom(roomId: String, userId: String) {
        viewModelScope.launch {
            try {
                repository.leaveRoom(roomId, userId)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun sendInvite(roomId: String, fromUserId: String, toUserId: String) {
        viewModelScope.launch {
            try {
                repository.sendInvite(roomId, fromUserId, toUserId)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun updatePrivacy(roomId: String, privacy: String) {
        viewModelScope.launch {
            try {
                repository.updateRoomPrivacy(roomId, privacy)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun clearError() { _error.value = null }



    fun observeQueue(roomId: String) = repository.observeQueue(roomId)
    fun observePlayback(roomId: String) = repository.observePlayback(roomId)


    fun updateRoomPermissions(roomId: String, canGuestsAdd: Boolean, canGuestsSkip: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateRoomPermissions(roomId, canGuestsAdd, canGuestsSkip)
                loadRooms()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun endRoom(roomId: String) {
        viewModelScope.launch {
            try {
                repository.endRoom(roomId)
                loadRooms()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun requestToJoin(roomId: String, userId: String) {
        viewModelScope.launch {
            try {
                repository.sendInvite(roomId, userId, "") // Using sendInvite for requests where toUserId is empty/host
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

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
}
