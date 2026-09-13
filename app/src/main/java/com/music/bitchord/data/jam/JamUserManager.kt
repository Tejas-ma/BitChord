package com.music.bitchord.data.jam

import android.content.Context
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.auth.GoogleAccountSession

object JamUserManager {

    private const val GUEST_ID = "guest"

    private fun getActiveSession(context: Context): GoogleAccountSession? {
        val authStore = AuthStore(context)
        return authStore.activeSession
    }

    fun getCurrentUserId(context: Context): String {
        val session = getActiveSession(context)
        if (session != null) {
            val profile = session.profiles.firstOrNull { it.profileId == session.activeProfileId } ?: session.profiles.firstOrNull()
            if (profile != null) {
                return profile.profileId
            }
        }
        return GUEST_ID
    }

    fun getCurrentUsername(context: Context): String {
        val session = getActiveSession(context)
        if (session != null) {
            val profile = session.profiles.firstOrNull { it.profileId == session.activeProfileId } ?: session.profiles.firstOrNull()
            if (profile != null) {
                return profile.name
            }
        }
        return "Guest"
    }

    fun isLoggedIn(context: Context): Boolean {
        return getCurrentUserId(context) != GUEST_ID
    }

    fun isGuest(context: Context): Boolean {
        return !isLoggedIn(context)
    }
}
