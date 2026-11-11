package com.nothingos.reelguard

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.nothingos.reelguard.data.PreferencesManager
import com.nothingos.reelguard.worker.DailyResetWorker

class MainActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager

    // Status
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView

    // Counter
    private lateinit var counterText: TextView
    private lateinit var progressBar: ProgressBar

    // Limit input
    private lateinit var limitInput: EditText
    private lateinit var setLimitButton: Button

    // Service toggle
    private lateinit var serviceToggle: Switch

    // Permission cards
    private lateinit var overlayStatusText: TextView
    private lateinit var overlayGrantButton: Button
    private lateinit var accessibilityStatusText: TextView
    private lateinit var accessibilityGrantButton: Button
    private lateinit var notificationStatusText: TextView
    private lateinit var notificationGrantButton: Button

    // Reset
    private lateinit var resetButton: Button

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionStatuses() }

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionStatuses() }

    private val notificationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionStatuses() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferencesManager = PreferencesManager(this)

        bindViews()
        setupClickListeners()

        // Schedule midnight reset on first launch
        DailyResetWorker.scheduleMidnightReset(this)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatuses()
        updateCounterDisplay()
        updateServiceStatus()
        // Restore limit input
        limitInput.setText(preferencesManager.getDailyLimit().toString())
        serviceToggle.isChecked = preferencesManager.isServiceEnabled()
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        counterText = findViewById(R.id.counter_text)
        progressBar = findViewById(R.id.progress_bar)
        limitInput = findViewById(R.id.limit_input)
        setLimitButton = findViewById(R.id.set_limit_button)
        serviceToggle = findViewById(R.id.service_toggle)

        overlayStatusText = findViewById(R.id.overlay_status_text)
        overlayGrantButton = findViewById(R.id.overlay_grant_button)
        accessibilityStatusText = findViewById(R.id.accessibility_status_text)
        accessibilityGrantButton = findViewById(R.id.accessibility_grant_button)
        notificationStatusText = findViewById(R.id.notification_status_text)
        notificationGrantButton = findViewById(R.id.notification_grant_button)

        resetButton = findViewById(R.id.reset_button)
    }

    private fun setupClickListeners() {
        setLimitButton.setOnClickListener { handleSetLimit() }
        serviceToggle.setOnCheckedChangeListener { _, isChecked -> handleServiceToggle(isChecked) }
        overlayGrantButton.setOnClickListener { requestOverlayPermission() }
        accessibilityGrantButton.setOnClickListener { requestAccessibilityPermission() }
        notificationGrantButton.setOnClickListener { requestNotificationPermission() }
        resetButton.setOnClickListener { showResetConfirmationDialog() }
    }

    private fun handleSetLimit() {
        val input = limitInput.text.toString().trim()
        val limit = input.toIntOrNull()
        if (limit == null || limit < 1 || limit > 9999) {
            Toast.makeText(this, "Enter a limit between 1 and 9999", Toast.LENGTH_SHORT).show()
            return
        }
        preferencesManager.setDailyLimit(limit)
        updateCounterDisplay()
        Toast.makeText(this, "Limit set to $limit reels", Toast.LENGTH_SHORT).show()
    }

    private fun handleServiceToggle(isChecked: Boolean) {
        preferencesManager.setServiceEnabled(isChecked)
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val enabled = preferencesManager.isServiceEnabled()
        if (enabled) {
            statusDot.setTextColor(getColor(R.color.status_active))
            statusText.text = getString(R.string.service_active)
        } else {
            statusDot.setTextColor(getColor(R.color.status_inactive))
            statusText.text = getString(R.string.service_disabled)
        }
        serviceToggle.isChecked = enabled
    }

    private fun updateCounterDisplay() {
        val count = preferencesManager.getTodayCount()
        val limit = preferencesManager.getDailyLimit()
        counterText.text = getString(R.string.counter_format, count, limit)
        progressBar.max = limit
        progressBar.progress = count.coerceAtMost(limit)
    }

    private fun refreshPermissionStatuses() {
        // Overlay
        val hasOverlay = Settings.canDrawOverlays(this)
        overlayStatusText.text = if (hasOverlay) getString(R.string.status_granted) else getString(R.string.status_required)
        overlayStatusText.setTextColor(
            if (hasOverlay) getColor(R.color.status_active) else getColor(R.color.status_inactive)
        )
        overlayGrantButton.isEnabled = !hasOverlay

        // Accessibility
        val hasAccessibility = isAccessibilityServiceEnabled()
        accessibilityStatusText.text = if (hasAccessibility) getString(R.string.status_granted) else getString(R.string.status_required)
        accessibilityStatusText.setTextColor(
            if (hasAccessibility) getColor(R.color.status_active) else getColor(R.color.status_inactive)
        )
        accessibilityGrantButton.isEnabled = !hasAccessibility

        // Notifications
        val hasNotifications = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notificationStatusText.text = if (hasNotifications) getString(R.string.status_granted) else getString(R.string.status_required)
        notificationStatusText.setTextColor(
            if (hasNotifications) getColor(R.color.status_active) else getColor(R.color.status_inactive)
        )
        notificationGrantButton.isEnabled = !hasNotifications
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val targetService = "${packageName}/.service.ReelTrackingAccessibilityService"
        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.let { si ->
                "${si.packageName}/${si.name}" == targetService ||
                si.name.contains("ReelTrackingAccessibilityService")
            }
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
    }

    private fun requestNotificationPermission() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        notificationSettingsLauncher.launch(intent)
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this, R.style.ReelGuardDialog)
            .setTitle(getString(R.string.reset_dialog_title))
            .setMessage(getString(R.string.reset_dialog_message))
            .setPositiveButton(getString(R.string.reset_confirm)) { _, _ ->
                preferencesManager.resetCount()
                updateCounterDisplay()
                Toast.makeText(this, getString(R.string.reset_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
