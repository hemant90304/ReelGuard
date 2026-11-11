package com.nothingos.reelguard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PreferencesManager {

    private const val TAG = "PreferencesManager"
    private const val PREFS_FILE = "reelguard_prefs"
    private const val KEY_COUNT = "daily_count"
    private const val KEY_LIMIT = "daily_limit"
    private const val KEY_LAST_RESET = "last_reset_date"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val DEFAULT_LIMIT = 100

    private lateinit var prefs: SharedPreferences

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun initialize(context: Context) {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to regular SharedPreferences: ${e.message}")
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    fun getCurrentCount(): Int {
        checkInitialized()
        return prefs.getInt(KEY_COUNT, 0)
    }

    fun getDailyLimit(): Int {
        checkInitialized()
        return prefs.getInt(KEY_LIMIT, DEFAULT_LIMIT)
    }

    fun setDailyLimit(limit: Int) {
        checkInitialized()
        prefs.edit().putInt(KEY_LIMIT, limit).apply()
    }

    @Synchronized
    fun incrementReelCount(): Int {
        checkInitialized()
        val newCount = getCurrentCount() + 1
        prefs.edit().putInt(KEY_COUNT, newCount).apply()
        return newCount
    }

    fun resetCount() {
        checkInitialized()
        prefs.edit().putInt(KEY_COUNT, 0).apply()
    }

    fun isServiceEnabled(): Boolean {
        checkInitialized()
        return prefs.getBoolean(KEY_SERVICE_ENABLED, true)
    }

    fun setServiceEnabled(enabled: Boolean) {
        checkInitialized()
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun getLastResetDate(): String {
        checkInitialized()
        return prefs.getString(KEY_LAST_RESET, "") ?: ""
    }

    fun setLastResetDate(date: String) {
        checkInitialized()
        prefs.edit().putString(KEY_LAST_RESET, date).apply()
    }

    fun shouldReset(): Boolean {
        val lastReset = getLastResetDate()
        if (lastReset.isEmpty()) return true
        val today = dateFormat.format(Date())
        return lastReset != today
    }

    fun todayString(): String = dateFormat.format(Date())

    private fun checkInitialized() {
        check(::prefs.isInitialized) {
            "PreferencesManager is not initialized. Call PreferencesManager.initialize(context) first."
        }
    }
}
