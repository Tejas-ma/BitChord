package com.music.bitchord.data.jam

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class Room(
    val id: String = "",
    val name: String = "",
    @SerialName("host_id")
    val hostId: String = "",
    val members: List<String> = emptyList(),
    val currentSong: String? = null,
    val privacy: String = "everyone",
    val createdAt: String = "",
    @SerialName("can_guests_add")
    val canGuestsAdd: Boolean = true,
    @SerialName("can_guests_skip")
    val canGuestsSkip: Boolean = false
)

@Serializable
data class FriendActivity(
    val userId: String = "",
    val username: String = "",
    val song: String = "",
    val mood: String = "",
    val updatedAt: String = ""
)

@Serializable
data class PlaybackState(
    val roomId: String = "",
    val position: Long = 0,
    val isPlaying: Boolean = false,
    val updatedAt: String = ""
)

@Serializable
data class JamInvite(
    val id: String = "",
    val roomId: String = "",
    val fromUserId: String = "",
    val toUserId: String = "",
    val status: String = "pending"
)
