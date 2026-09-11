package com.music.bitchord.data.jam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JamRoom(
    val id: String = "",
    val name: String = "",
    @SerialName("host_id") val hostId: String = "",
    val privacy: String = "everyone",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("allow_others_to_play")
        val allowOthersToPlay: Boolean = false,
    @SerialName("allow_invite") val allowInvite: Boolean = true
)

@Serializable
data class JamQueueItem(
    val id: String = "",
    @SerialName("room_id") val roomId: String = "",
    @SerialName("video_id") val videoId: String = "",
    val title: String = "",
    @SerialName("added_by") val addedBy: String = "",
    val position: Int = 0,
    @SerialName("created_at") val createdAt: String = ""
)
