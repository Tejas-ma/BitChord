package com.music.bitchord.data.jam

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import androidx.work.ListenableWorker.Result

class WeeklySummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = MoodSummaryRepository(applicationContext)
        var dominantMood = "Vibing 🎵"

        repository.getWeeklyMoods().collect { entries ->
            val moodCounts = entries.groupingBy { it.mood }.eachCount()
            dominantMood = moodCounts.maxByOrNull { it.value }?.key ?: "Vibing 🎵"
        }

        val notification = NotificationCompat.Builder(applicationContext, "mood_channel")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Your Weekly Mood Recap")
            .setContentText("This week you were mostly $dominantMood")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(1001, notification)
        } catch (e: SecurityException) {
            // Permission not granted
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeeklySummaryWorker>(
                7, TimeUnit.DAYS
            ).build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
