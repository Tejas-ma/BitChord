package com.music.bitchord.data.jam

import kotlinx.serialization.Serializable

@Serializable
data class Room(
    val id: String = "",
    val name: String = "",
    val hostId: String = "",
    val members: List<String> = emptyList(),
    val currentSong: String? = null,
    val privacy: String = "everyone",
    val createdAt: String = ""
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
