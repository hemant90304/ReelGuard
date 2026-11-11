package com.nothingos.reelguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.nothingos.reelguard.MainActivity
import com.nothingos.reelguard.data.PreferencesManager

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "reelguard_overlay"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SHOW_OVERLAY = "com.nothingos.reelguard.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.nothingos.reelguard.HIDE_OVERLAY"
        const val ACTION_UPDATE_HUD = "com.nothingos.reelguard.UPDATE_HUD"
        const val EXTRA_COUNT = "count"
        const val EXTRA_LIMIT = "limit"
    }

    private var windowManager: WindowManager? = null
    private var hudView: TextView? = null
    private var isViewAdded = false
    private val preferencesManager = PreferencesManager

    private val countUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ReelTrackingAccessibilityService.ACTION_COUNT_UPDATED) {
                val count = intent.getIntExtra(EXTRA_COUNT, 0)
                val limit = preferencesManager.getDailyLimit()
                updateHUD(count, limit)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.initialize(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val count = preferencesManager.getTodayReelCount()
        val limit = preferencesManager.getDailyLimit()
        startForeground(NOTIFICATION_ID, buildNotification(count, limit))

        val filter = IntentFilter(ReelTrackingAccessibilityService.ACTION_COUNT_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(countUpdatedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(countUpdatedReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val count = intent.getIntExtra(EXTRA_COUNT, preferencesManager.getTodayReelCount())
                val limit = intent.getIntExtra(EXTRA_LIMIT, preferencesManager.getDailyLimit())
                if (!isViewAdded) {
                    createOverlayView(count, limit)
                } else {
                    updateHUD(count, limit)
                }
            }
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_UPDATE_HUD -> {
                val count = intent.getIntExtra(EXTRA_COUNT, preferencesManager.getTodayReelCount())
                val limit = intent.getIntExtra(EXTRA_LIMIT, preferencesManager.getDailyLimit())
                updateHUD(count, limit)
            }
            else -> {
                // Service started without specific action — update HUD if already visible
                if (isViewAdded) {
                    val count = preferencesManager.getTodayReelCount()
                    val limit = preferencesManager.getDailyLimit()
                    updateHUD(count, limit)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ //
    //  Overlay view                                                        //
    // ------------------------------------------------------------------ //

    private fun createOverlayView(count: Int, limit: Int) {
        val wm = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(48)
        }

        val hud = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            background = buildHudBackground()
            text = formatHudText(count, limit)
        }

        hudView = hud

        try {
            wm.addView(hud, params)
            isViewAdded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showOverlay() {
        if (!isViewAdded) {
            val count = preferencesManager.getTodayReelCount()
            val limit = preferencesManager.getDailyLimit()
            createOverlayView(count, limit)
        }
    }

    fun hideOverlay() {
        if (isViewAdded) {
            try {
                windowManager?.removeView(hudView)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isViewAdded = false
                hudView = null
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  HUD update                                                          //
    // ------------------------------------------------------------------ //

    fun updateHUD(count: Int, limit: Int) {
        hudView?.let { tv ->
            tv.text = formatHudText(count, limit)
            if (count >= limit) {
                tv.setTextColor(Color.parseColor("#FF4444"))
            } else {
                tv.setTextColor(Color.parseColor("#E0E0E0"))
            }
        }

        // Also refresh the notification text
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(count, limit))
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private fun formatHudText(count: Int, limit: Int): String =
        "● REELS  $count / $limit"

    private fun buildHudBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(6).toFloat()
            setColor(Color.parseColor("#CC000000"))
        }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------ //
    //  Notification                                                        //
    // ------------------------------------------------------------------ //

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ReelGuard Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent HUD tracking your daily Reels count"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(count: Int, limit: Int): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ReelGuard Active")
            .setContentText("Reels today: $count / $limit")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ------------------------------------------------------------------ //
    //  Lifecycle                                                           //
    // ------------------------------------------------------------------ //

    override fun onDestroy() {
        hideOverlay()
        try {
            unregisterReceiver(countUpdatedReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was never registered — safe to ignore
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
