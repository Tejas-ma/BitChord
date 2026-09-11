package com.music.bitchord.data.jam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JamRoom(
    val id: String = "",
    val name: String = "",
    @SerialName("host_id") val hostId: String = "",
    @SerialName("is_private") val isPrivate: Boolean = false,
    @SerialName("max_members") val maxMembers: Int = 8,
    @SerialName("created_at") val createdAt: String = ""
)
