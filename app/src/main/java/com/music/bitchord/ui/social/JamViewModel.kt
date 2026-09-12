package com.music.bitchord.ui.social

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.jam.JamRoom
import com.music.bitchord.data.jam.JamQueueItem
import com.music.bitchord.supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JamViewModel(application: Application) : AndroidViewModel(application) {

    private val authStore = AuthStore(application.applicationContext)
    private val localUserId get() = authStore.localUserId

    private val _rooms = MutableStateFlow<List<JamRoom>>(emptyList())
    val rooms: StateFlow<List<JamRoom>> = _rooms.asStateFlow()

    private val _myRooms = MutableStateFlow<List<JamRoom>>(emptyList())
    val myRooms: StateFlow<List<JamRoom>> = _myRooms.asStateFlow()

    private val _activeRoom = MutableStateFlow<JamRoom?>(null)
    val activeRoom: StateFlow<JamRoom?> = _activeRoom.asStateFlow()

    private val _queue = MutableStateFlow<List<JamQueueItem>>(emptyList())
    val queue: StateFlow<List<JamQueueItem>> = _queue.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _nowPlayingVideoId = MutableStateFlow<String?>(null)
    val nowPlayingVideoId: StateFlow<String?> = _nowPlayingVideoId.asStateFlow()

    private val _nowPlayingTitle = MutableStateFlow<String?>(null)
    val nowPlayingTitle: StateFlow<String?> = _nowPlayingTitle.asStateFlow()

    private val _nowPlayingArtist = MutableStateFlow<String?>(null)
    val nowPlayingArtist: StateFlow<String?> = _nowPlayingArtist.asStateFlow()

    private var broadcastChannel: RealtimeChannel? = null


    fun createRoom(name: String, isPrivate: Boolean, maxMembers: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val room = supabase.postgrest["rooms"].insert(
                    buildJsonObject {
                        put("name", name)
                        put("host_id", localUserId)
                        put("privacy", if (isPrivate) "private" else "everyone")
                        put("is_active", true)
                        put("allow_others_to_play", false)
                        put("allow_invite", true)
                    }
                ).decodeAs<JamRoom>()
                _rooms.value = _rooms.value + room
                _myRooms.value = _myRooms.value + room
                _activeRoom.value = room
                loadQueue(room.id)
                loadRooms()
            } catch (e: Exception) {
                _error.value = "Could not create room: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun joinRoom(room: JamRoom) {
        viewModelScope.launch {
            _activeRoom.value = room
            loadQueue(room.id)
            startBroadcast(room.id)
        }
    }

    fun leaveRoom() {
        stopBroadcast()
        _activeRoom.value = null
        _queue.value = emptyList()
    }

    fun endRoom(roomId: String) {
        stopBroadcast()
        viewModelScope.launch {
            try {
                supabase.postgrest["queue"]
                    .delete { filter { eq("room_id", roomId) } }
                supabase.postgrest["rooms"]
                    .update({ set("is_active", false) }) {
                        filter { eq("id", roomId) }
                    }
                _activeRoom.value = null
                _queue.value = emptyList()
                loadRooms()
            } catch (e: Exception) {
                _error.value = "Could not end room: ${e.message}"
            }
        }
    }

    fun updateRoomSettings(
        roomId: String,
        allowOthersToPlay: Boolean,
        allowInvite: Boolean
    ) {
        viewModelScope.launch {
            try {
                supabase.postgrest["rooms"]
                    .update({
                        set("allow_others_to_play", allowOthersToPlay)
                        set("allow_invite", allowInvite)
                    }) {
                        filter { eq("id", roomId) }
                    }
                _activeRoom.value = _activeRoom.value?.copy(
                    allowOthersToPlay = allowOthersToPlay,
                    allowInvite = allowInvite
                )
            } catch (e: Exception) {
                _error.value = "Could not update settings"
            }
        }
    }

    fun loadQueue(roomId: String) {
        viewModelScope.launch {
            try {
                val items = supabase.postgrest["queue"]
                    .select { filter { eq("room_id", roomId) } }
                    .decodeList<JamQueueItem>()
                _queue.value = items.sortedBy { it.position }
            } catch (e: Exception) {
                _error.value = "Could not load queue"
            }
        }
    }

    fun loadRooms() {
        viewModelScope.launch {
            try {
                val all = supabase.postgrest["rooms"]
                    .select()
                    .decodeList<JamRoom>()
                _rooms.value = all.filter {
                    it.isActive != false
                }
                _myRooms.value = _rooms.value.filter {
                    it.hostId == localUserId
                }
            } catch (e: Exception) {
                _error.value = "Could not load rooms"
            }
        }
    }

    fun startBroadcast(roomId: String) {
        viewModelScope.launch {
            try {
                broadcastChannel = supabase.channel("room:$roomId")
                broadcastChannel?.subscribe()
                val flow = broadcastChannel?.broadcastFlow<Map<String, String>>("playback")
                flow?.collect { payload ->
                    val videoId = payload["videoId"]
                    val title = payload["title"]
                    val artist = payload["artist"]
                    _nowPlayingVideoId.value = videoId
                    _nowPlayingTitle.value = title
                    _nowPlayingArtist.value = artist
                }
            } catch (e: Exception) {
                _error.value = "Could not connect to room"
            }
        }
    }

    fun broadcastNowPlaying(videoId: String, title: String, artist: String) {
        viewModelScope.launch {
            try {
                broadcastChannel?.broadcast(
                    event = "playback",
                    message = mapOf("videoId" to videoId, "title" to title, "artist" to artist)
                )
            } catch (e: Exception) {
                // silent fail — broadcast is best effort
            }
        }
    }

    fun stopBroadcast() {
        viewModelScope.launch {
            try {
                broadcastChannel?.let { supabase.realtime.removeChannel(it) }
                broadcastChannel = null
            } catch (e: Exception) {
                // silent
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopBroadcast()
    }

    fun clearError() { _error.value = null }

    init {
        loadRooms()
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                loadRooms()
            }
        }
    }
}
