package com.music.bitchord.data.jam

import com.music.bitchord.supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.FilterOperation
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class JamRepository {

    suspend fun createRoom(name: String, hostId: String, privacy: String = "everyone"): Room {
        return supabase.postgrest["rooms"].insert(
            Room(name = name, hostId = hostId, privacy = privacy)
        ).decodeSingle()
    }

    suspend fun joinRoom(roomId: String, userId: String): Boolean {
        return try {
            supabase.postgrest["rooms"].update({
                set("members", arrayOf(userId))
            }) { FilterOperation("id", FilterOperator.EQ, roomId) }
            true
        } catch (e: Exception) { false }
    }

    suspend fun leaveRoom(roomId: String, userId: String) {
        supabase.postgrest["rooms"].update({
            set("members", emptyArray<String>())
        }) { FilterOperation("id", FilterOperator.EQ, roomId) }
    }

    fun getRooms(): Flow<List<Room>> = flow {
        val rooms = supabase.postgrest["rooms"].select().decodeList<Room>()
        emit(rooms)
    }

    fun getFriendsListening(): Flow<List<FriendActivity>> = flow {
        val activity = supabase.postgrest["friend_activity"]
            .select().decodeList<FriendActivity>()
        emit(activity)
    }

    suspend fun addToQueue(roomId: String, songJson: String) {
        supabase.postgrest["queue"].insert(
            mapOf("room_id" to roomId, "song" to songJson, "added_by" to "")
        )
    }

    fun observeQueue(roomId: String): Flow<List<String>> = flow {
        val queue = supabase.postgrest["queue"]
            .select { FilterOperation("room_id", FilterOperator.EQ, roomId) }
            .decodeList<Map<String, String>>()
        emit(queue.mapNotNull { it["song"] })
    }

    suspend fun syncPlayback(roomId: String, position: Long, isPlaying: Boolean) {
        supabase.postgrest["playback_state"].upsert(
            PlaybackState(roomId = roomId, position = position, isPlaying = isPlaying)
        )
    }

    fun observePlayback(roomId: String): Flow<PlaybackState> = flow {
        val state = supabase.postgrest["playback_state"]
            .select { FilterOperation("room_id", FilterOperator.EQ, roomId) }
            .decodeSingle<PlaybackState>()
        emit(state)
    }

    suspend fun sendInvite(roomId: String, fromUserId: String, toUserId: String) {
        supabase.postgrest["jam_invites"].insert(
            JamInvite(roomId = roomId, fromUserId = fromUserId, toUserId = toUserId)
        )
    }

    suspend fun updateRoomPrivacy(roomId: String, privacy: String) {
        supabase.postgrest["rooms"].update({
            set("privacy", privacy)
        }) { FilterOperation("id", FilterOperator.EQ, roomId) }
    }

    suspend fun updateFriendMood(userId: String, mood: String) {
        supabase.postgrest["friend_activity"].update({
            set("mood", mood)
        }) { FilterOperation("user_id", FilterOperator.EQ, userId) }
    }

    suspend fun updateRoomPermissions(roomId: String, canGuestsAdd: Boolean, canGuestsSkip: Boolean) {
        supabase.postgrest["rooms"].update({
            set("can_guests_add", canGuestsAdd)
            set("can_guests_skip", canGuestsSkip)
        }) { FilterOperation("id", FilterOperator.EQ, roomId) }
    }

    suspend fun endRoom(roomId: String) {
        supabase.postgrest["rooms"].delete { FilterOperation("id", FilterOperator.EQ, roomId) }
    }
}
