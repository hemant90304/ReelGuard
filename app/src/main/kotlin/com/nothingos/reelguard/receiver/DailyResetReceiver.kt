package com.nothingos.reelguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nothingos.reelguard.data.PreferencesManager
import com.nothingos.reelguard.worker.DailyResetWorker

class DailyResetReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyResetReceiver"
        const val ACTION_DAILY_RESET = "com.nothingos.reelguard.DAILY_RESET"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed — checking if reset is needed.")
                handleReset(context)
            }
            ACTION_DAILY_RESET -> {
                Log.d(TAG, "Daily reset action received — checking if reset is needed.")
                handleReset(context)
            }
            else -> {
                Log.w(TAG, "Received unknown action: ${intent.action}")
            }
        }
    }

    private fun handleReset(context: Context) {
        // Initialize preferences (safe to call multiple times; idempotent guard inside)
        PreferencesManager.initialize(context)

        if (PreferencesManager.shouldReset()) {
            Log.d(TAG, "Reset needed — resetting reel count.")
            PreferencesManager.resetCount()
            PreferencesManager.setLastResetDate(PreferencesManager.todayString())
        } else {
            Log.d(TAG, "No reset needed today.")
        }

        // Always (re-)schedule the next WorkManager midnight reset
        DailyResetWorker.scheduleMidnightReset(context)
    }
}
