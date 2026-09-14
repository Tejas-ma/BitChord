package com.music.bitchord.data.jam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Friendship(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("friend_id") val friendId: String = "",
    val status: String = "pending",
    @SerialName("friend_code") val friendCode: String = "",
    @SerialName("created_at") val createdAt: String = ""
)
