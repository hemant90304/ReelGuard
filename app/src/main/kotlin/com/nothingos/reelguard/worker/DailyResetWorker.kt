package com.nothingos.reelguard.worker

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nothingos.reelguard.data.PreferencesManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DailyResetWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // Ensure PreferencesManager is initialized
        PreferencesManager.initialize(applicationContext)

        // Reset the reel count and record today's reset date
        PreferencesManager.resetCount()
        PreferencesManager.setLastResetDate(PreferencesManager.todayString())

        // Broadcast count update so the UI can refresh
        val intent = Intent(ACTION_COUNT_UPDATED).apply {
            putExtra(EXTRA_COUNT, 0)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

        // Schedule the next midnight reset
        scheduleMidnightReset(applicationContext)

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "DailyReelReset"
        const val ACTION_COUNT_UPDATED = "com.nothingos.reelguard.ACTION_COUNT_UPDATED"
        const val EXTRA_COUNT = "count"

        fun scheduleMidnightReset(context: Context) {
            val now = Calendar.getInstance()

            // Target: next midnight (00:00:00.000)
            val nextMidnight = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val delayMs = nextMidnight.timeInMillis - now.timeInMillis
            val delayMinutes = delayMs.coerceAtLeast(0L)

            val workRequest = OneTimeWorkRequestBuilder<DailyResetWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}
