package com.music.bitchord.data.jam

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.moodSummaryDataStore by preferencesDataStore(name = "mood_summary")

@Serializable
data class MoodEntry(
    val mood: String,
    val date: String,
    val timestamp: Long = System.currentTimeMillis()
)

class MoodSummaryRepository(private val context: Context) {

    private val MOOD_ENTRIES_KEY = stringPreferencesKey("mood_entries")

    suspend fun saveDailyMood(mood: String) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val entry = MoodEntry(mood = mood, date = date)
        context.moodSummaryDataStore.edit { prefs ->
            val existingStr = prefs[MOOD_ENTRIES_KEY]
            val existing = if (existingStr != null) {
                Json.decodeFromString<List<MoodEntry>>(existingStr)
            } else {
                emptyList()
            }
            val updated = existing.filter { it.date != date } + entry
            prefs[MOOD_ENTRIES_KEY] = Json.encodeToString(updated)
        }
    }

    fun getWeeklyMoods(): Flow<List<MoodEntry>> = getMoods(7)
    fun getMonthlyMoods(): Flow<List<MoodEntry>> = getMoods(30)
    fun getYearlyMoods(): Flow<List<MoodEntry>> = getMoods(365)

    private fun getMoods(days: Int): Flow<List<MoodEntry>> =
        context.moodSummaryDataStore.data.map { prefs ->
            val existingStr = prefs[MOOD_ENTRIES_KEY]
            val entries = if (existingStr != null) {
                Json.decodeFromString<List<MoodEntry>>(existingStr)
            } else {
                emptyList()
            }
            val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
            entries.filter { it.timestamp >= cutoff }
        }
}
