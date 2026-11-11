package com.nothingos.reelguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nothingos.reelguard.data.PreferencesManager
import com.nothingos.reelguard.receiver.DailyResetReceiver

/**
 * ReelTrackingAccessibilityService monitors Instagram for Reels activity.
 *
 * It detects when the user is viewing Instagram Reels by inspecting accessibility
 * node hierarchies, counts each distinct reel scroll (debounced), enforces a
 * configurable daily limit, and manages the [OverlayService] overlay.
 */
class ReelTrackingAccessibilityService : AccessibilityService() {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        const val ACTION_LIMIT_REACHED = "com.nothingos.reelguard.LIMIT_REACHED"
        const val ACTION_COUNT_UPDATED  = "com.nothingos.reelguard.COUNT_UPDATED"
        const val EXTRA_CURRENT_COUNT   = "extra_current_count"
        const val EXTRA_DAILY_LIMIT     = "extra_daily_limit"

        private const val INSTAGRAM_PKG = "com.instagram.android"
        private const val SCROLL_DEBOUNCE_MS = 300L

        // Instagram view IDs that signal the Reels tab / viewer is active
        private val REEL_VIEW_IDS = listOf(
            "$INSTAGRAM_PKG:id/clips_viewer_pager",
            "$INSTAGRAM_PKG:id/reel_viewer_root",
            "$INSTAGRAM_PKG:id/clips_tab",
            "$INSTAGRAM_PKG:id/clips_viewer",
            "$INSTAGRAM_PKG:id/video_player"
        )

        // Content-description substrings that indicate Reels context
        private val REEL_CONTENT_DESC_HINTS = listOf(
            "reel", "clips_tab", "clips_viewer", "video_player", "reels"
        )
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Whether the user is currently inside the Instagram Reels viewer. */
    private var isInReels = false

    /** Whether the OverlayService is currently running. */
    private var isOverlayActive = false

    /**
     * Last scroll Y position seen on TYPE_VIEW_SCROLLED. We only count a reel
     * view when the scroll delta is large enough to indicate a full reel change.
     */
    private var lastScrollY = -1

    /** Handler / Runnable used to debounce rapid scroll events. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scrollDebounceRunnable: Runnable? = null

    /** Receives the daily-reset broadcast so we can re-enable tracking. */
    private val dailyResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DailyResetReceiver.ACTION_DAILY_RESET) {
                PreferencesManager.resetCount()
                isInReels = false
                lastScrollY = -1
                // Restart the overlay if service is still enabled
                if (PreferencesManager.isServiceEnabled()) {
                    startOverlay()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Initialise preferences (idempotent if already initialised elsewhere)
        PreferencesManager.initialize(applicationContext)

        // Configure service info programmatically as a supplement to the XML config
        serviceInfo = serviceInfo.apply {
            eventTypes = (
                AccessibilityEvent.TYPE_VIEW_SCROLLED
                    or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    or AccessibilityEvent.TYPE_VIEW_FOCUSED
            )
            feedbackType  = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags         = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            notificationTimeout = 100
            packageNames        = arrayOf(INSTAGRAM_PKG)
        }

        // Register the daily-reset receiver
        val filter = IntentFilter(DailyResetReceiver.ACTION_DAILY_RESET)
        ContextCompat.registerReceiver(
            this,
            dailyResetReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Do not auto-start overlay on connected — it must only appear when Reels is active
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopOverlay()
        try {
            unregisterReceiver(dailyResetReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was never registered (e.g. onServiceConnected not yet called)
        }
        mainHandler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    // -------------------------------------------------------------------------
    // Accessibility event dispatch
    // -------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString()

        // If user is outside Instagram (launcher, settings, another app), hide overlay immediately!
        if (pkg != INSTAGRAM_PKG) {
            if (isOverlayActive) {
                stopOverlay()
            }
            isInReels = false
            return
        }

        // If disabled in ReelGuard settings, hide overlay and do nothing
        if (!PreferencesManager.isServiceEnabled()) {
            if (isOverlayActive) stopOverlay()
            isInReels = false
            return
        }

        // Check if currently inside the Reels viewer
        val reelsActive = isUserInReels(event)
        isInReels = reelsActive

        if (reelsActive) {
            // Enforce hard limit immediately if reached
            if (isLimitReached()) {
                enforceLimit()
                return
            }

            // Show overlay only when Reels is active
            if (!isOverlayActive) {
                startOverlay()
            }

            // If scrolling inside Reels, debounce & increment counter
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                handleReelScrolled(event)
            }
        } else {
            // User is on Instagram Feed, Stories, DMs, or Profile — hide overlay and DO NOT count!
            if (isOverlayActive) {
                stopOverlay()
            }
        }
    }

    /**
     * Inspects the active window hierarchy to distinguish between:
     * - Reels viewer / Clips tab (returns true)
     * - Feed post scroll, Stories, Explore, Messages (returns false)
     */
    private fun isUserInReels(event: AccessibilityEvent): Boolean {
        // Fast path: check event source
        event.source?.let { node ->
            try {
                if (nodeMatchesReels(node)) return true
            } finally {
                node.recycle()
            }
        }

        // Deep path: inspect root in active window
        rootInActiveWindow?.let { root ->
            try {
                return nodeMatchesReels(root)
            } finally {
                root.recycle()
            }
        }

        return false
    }

    private fun nodeMatchesReels(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 6) return false

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Specific signals found exclusively inside Instagram Reels viewer
        if (desc.contains("reel by") ||
            desc.contains("original audio") ||
            desc.contains("audio by") ||
            desc.contains("remix with") ||
            viewId.contains("clips_viewer") ||
            viewId.contains("reel_viewer") ||
            viewId.contains("clips_video_container") ||
            text.contains("original audio") ||
            text.contains("use audio")
        ) {
            return true
        }

        // Bottom navigation bar check: Reels tab selected
        if ((desc == "reels" || desc == "clips") && node.isSelected) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (nodeMatchesReels(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }

        return false
    }

    private fun handleReelScrolled(event: AccessibilityEvent) {
        if (isLimitReached()) {
            enforceLimit()
            return
        }

        val currentScrollY = event.scrollY
        val scrollDelta = if (lastScrollY >= 0) Math.abs(currentScrollY - lastScrollY) else -1
        lastScrollY = currentScrollY

        // Filter tiny micro-drags
        if (scrollDelta == 0) return

        scrollDebounceRunnable?.let { mainHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (isInReels) {
                if (!isLimitReached()) {
                    incrementAndCheck()
                } else {
                    enforceLimit()
                }
            }
        }
        scrollDebounceRunnable = runnable
        mainHandler.postDelayed(runnable, SCROLL_DEBOUNCE_MS)
    }

    // -------------------------------------------------------------------------
    // Detection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if the accessibility [event]'s node hierarchy contains any
     * indicator that the Reels viewer is currently displayed.
     */
    private fun detectReelsContext(event: AccessibilityEvent): Boolean {
        val source = event.source ?: return checkByClassName(event)
        return try {
            isReelsNode(source) || checkByClassName(event)
        } finally {
            source.recycle()
        }
    }

    /**
     * Traverses [node] and its subtree looking for any Reels-related view ID or
     * content description.
     */
    private fun isReelsNode(node: AccessibilityNodeInfo): Boolean {
        // Check well-known view IDs first (fast path)
        for (viewId in REEL_VIEW_IDS) {
            val results = node.findAccessibilityNodeInfosByViewId(viewId)
            if (results.isNotEmpty()) {
                results.forEach { it.recycle() }
                return true
            }
        }

        // Check content descriptions recursively (slower, but catches renamed IDs)
        return containsReelContentDesc(node)
    }

    /**
     * Recursively checks [node] and its children for content descriptions
     * matching known Reels hints.
     */
    private fun containsReelContentDesc(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 6) return false // limit recursion depth for performance

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        for (hint in REEL_CONTENT_DESC_HINTS) {
            if (desc.contains(hint) || viewId.contains(hint) || text.contains(hint)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsReelContentDesc(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }

        return false
    }

    /**
     * Fallback: checks if the event's class name hints at a Reels-related Activity.
     */
    private fun checkByClassName(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()?.lowercase() ?: return false
        return REEL_CONTENT_DESC_HINTS.any { className.contains(it) }
    }

    // -------------------------------------------------------------------------
    // Counter logic & limit enforcement
    // -------------------------------------------------------------------------

    /** Increments the reel counter and enforces the daily limit if reached. */
    private fun incrementAndCheck() {
        val newCount = PreferencesManager.incrementReelCount()
        val limit = PreferencesManager.getDailyLimit()

        // Instant live update directly to the floating HUD
        OverlayService.updateLiveCount(newCount, limit)

        // Broadcast count update so the UI can refresh
        broadcastCountUpdated(newCount)

        if (newCount >= limit) {
            enforceLimit()
        }
    }

    /** Returns true if the daily limit has already been reached. */
    private fun isLimitReached(): Boolean =
        PreferencesManager.getCurrentCount() >= PreferencesManager.getDailyLimit()

    /**
     * Enforces the daily limit: navigates home, shows a Toast, stops the
     * overlay, and broadcasts [ACTION_LIMIT_REACHED].
     */
    private fun enforceLimit() {
        // Navigate to the Home screen
        performGlobalAction(GLOBAL_ACTION_HOME)

        // Show a Toast on the main thread
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "Daily Reels limit reached! Resets at midnight.",
                Toast.LENGTH_LONG
            ).show()
        }

        // Stop the overlay
        stopOverlay()
        isInReels = false

        // Broadcast so DailyResetReceiver / NotificationHelper can show a blocking notification
        val limitIntent = Intent(ACTION_LIMIT_REACHED).apply {
            setPackage(packageName)
            putExtra(EXTRA_CURRENT_COUNT, PreferencesManager.getCurrentCount())
            putExtra(EXTRA_DAILY_LIMIT, PreferencesManager.getDailyLimit())
        }
        sendBroadcast(limitIntent)
    }

    /** Broadcasts [ACTION_COUNT_UPDATED] with the latest counts. */
    private fun broadcastCountUpdated(currentCount: Int) {
        val intent = Intent(ACTION_COUNT_UPDATED).apply {
            setPackage(packageName)
            putExtra(EXTRA_CURRENT_COUNT, currentCount)
            putExtra(EXTRA_DAILY_LIMIT, PreferencesManager.getDailyLimit())
        }
        sendBroadcast(intent)
    }

    // -------------------------------------------------------------------------
    // Overlay management
    // -------------------------------------------------------------------------

    /** Starts [OverlayService] if it is not already running. */
    private fun startOverlay() {
        if (isOverlayActive) return
        try {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_OVERLAY
                putExtra(OverlayService.EXTRA_COUNT, PreferencesManager.getCurrentCount())
                putExtra(OverlayService.EXTRA_LIMIT, PreferencesManager.getDailyLimit())
            }
            ContextCompat.startForegroundService(this, intent)
            isOverlayActive = true
            OverlayService.show()
        } catch (e: Exception) {
            isOverlayActive = false
        }
    }

    /** Stops [OverlayService] if it is currently running. */
    private fun stopOverlay() {
        if (!isOverlayActive) return
        try {
            OverlayService.hide()
            val intent = Intent(this, OverlayService::class.java)
            stopService(intent)
        } catch (_: Exception) {
            // Ignore
        } finally {
            isOverlayActive = false
        }
    }
}
