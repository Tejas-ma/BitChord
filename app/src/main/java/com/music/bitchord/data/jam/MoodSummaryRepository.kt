package com.music.bitchord.data.jam

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class MoodSummaryRepository(private val context: Context) {
    fun getWeeklyMoods(): Flow<List<FriendActivity>> {
        return flowOf(emptyList())
    }
}
