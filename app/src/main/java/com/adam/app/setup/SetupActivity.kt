package com.adam.app.setup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView
import android.app.NotificationManager
import com.adam.app.R
import com.adam.app.core.AdamService

class SetupActivity : AppCompatActivity() {

    private lateinit var btnNotificationAccess: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnMicrophone: MaterialButton
    private lateinit var btnPhone: MaterialButton
    private lateinit var btnSms: MaterialButton
    private lateinit var btnContacts: MaterialButton
    private lateinit var btnCalendar: MaterialButton
    private lateinit var btnDndAccess: MaterialButton
    private lateinit var btnBattery: MaterialButton
    private lateinit var etClaudeKey: TextInputEditText
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var tvStatus: TextView

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStates() }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStates() }

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStates() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStates() }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStates() }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updatePermissionStates() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        bindViews()
        setupClickListeners()
        loadSavedKeys()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
        updateServiceStatus()
    }

    private fun bindViews() {
        btnNotificationAccess = findViewById(R.id.btnNotificationAccess)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnMicrophone = findViewById(R.id.btnMicrophone)
        btnPhone = findViewById(R.id.btnPhone)
        btnSms = findViewById(R.id.btnSms)
        btnContacts = findViewById(R.id.btnContacts)
        btnCalendar = findViewById(R.id.btnCalendar)
        btnDndAccess = findViewById(R.id.btnDndAccess)
        btnBattery = findViewById(R.id.btnBattery)
        etClaudeKey = findViewById(R.id.etClaudeKey)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvStatus = findViewById(R.id.tvStatus)
    }

    private fun setupClickListeners() {
        btnNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnMicrophone.setOnClickListener {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnPhone.setOnClickListener {
            phonePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }

        btnSms.setOnClickListener {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }

        btnContacts.setOnClickListener {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }

        btnCalendar.setOnClickListener {
            calendarPermissionLauncher.launch(arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            ))
        }

        btnDndAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        btnBattery.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        btnStart.setOnClickListener {
            saveApiKeys()
            startAdam()
        }

        btnStop.setOnClickListener {
            stopAdam()
        }

        btnCheckUpdate.setOnClickListener {
            // Request notification permission on Android 13+
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnClickListener
            }
            btnCheckUpdate.text = "Checking..."
            btnCheckUpdate.isEnabled = false
            val updater = com.adam.app.updater.UpdateChecker(this) { status ->
                runOnUiThread {
                    Toast.makeText(this, status, Toast.LENGTH_LONG).show()
                    btnCheckUpdate.text = "Check for Update"
                    btnCheckUpdate.isEnabled = true
                }
            }
            updater.checkNow()
        }
    }

    private fun updatePermissionStates() {
        updateButton(btnNotificationAccess, "Notification Access", isNotificationListenerEnabled())
        updateButton(btnAccessibility, "Volume Buttons (optional)", isAccessibilityEnabled())
        updateButton(btnMicrophone, "Microphone", hasPermission(Manifest.permission.RECORD_AUDIO))
        updateButton(btnPhone, "Phone Calls", hasPermission(Manifest.permission.CALL_PHONE))
        updateButton(btnSms, "SMS", hasPermission(Manifest.permission.SEND_SMS))
        updateButton(btnContacts, "Contacts", hasPermission(Manifest.permission.READ_CONTACTS))
        updateButton(btnCalendar, "Calendar", hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR))
        updateButton(btnDndAccess, "Do Not Disturb", isDndAccessGranted())
        updateButton(btnBattery, "Battery Optimization", isBatteryOptimizationDisabled())
    }

    private fun updateButton(button: MaterialButton, label: String, granted: Boolean) {
        if (granted) {
            button.text = "$label  ✓"
            button.isEnabled = false
            button.alpha = 0.6f
        } else {
            button.text = label
            button.isEnabled = true
            button.alpha = 1.0f
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(ComponentName(this, "com.adam.app.notifications.NotificationCaptureService").flattenToString()) == true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return flat?.contains(ComponentName(this, "com.adam.app.accessibility.VolumeButtonService").flattenToString()) == true
    }

    private fun isDndAccessGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun saveApiKeys() {
        try {
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                "adam_keys",
                androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
                ),
                this,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val claudeKey = etClaudeKey.text?.toString()?.trim()

            prefs.edit().apply {
                if (!claudeKey.isNullOrBlank()) putString("claude_key", claudeKey)
                apply()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving keys: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSavedKeys() {
        try {
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                "adam_keys",
                androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
                ),
                this,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val claudeKey = prefs.getString("claude_key", null)

            if (!claudeKey.isNullOrBlank()) etClaudeKey.setText(claudeKey)
        } catch (e: Exception) {
            // First launch, no keys yet
        }
    }

    private fun startAdam() {
        val claudeKey = etClaudeKey.text?.toString()?.trim()

        if (claudeKey.isNullOrBlank()) {
            Toast.makeText(this, "Please enter your Anthropic API key", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNotificationListenerEnabled()) {
            Toast.makeText(this, "Please enable Notification Access first", Toast.LENGTH_SHORT).show()
            return
        }

        // Accessibility service is optional — volume buttons are a nice-to-have
        // but all core interaction works through "Hey Adam" voice commands

        saveApiKeys()

        getSharedPreferences("adam_prefs", MODE_PRIVATE)
            .edit().putBoolean("service_running", true).apply()

        ContextCompat.startForegroundService(
            this,
            Intent(this, AdamService::class.java)
        )

        updateServiceStatus()
        Toast.makeText(this, "Adam started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAdam() {
        getSharedPreferences("adam_prefs", MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()

        stopService(Intent(this, AdamService::class.java))
        updateServiceStatus()
        Toast.makeText(this, "Adam stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceStatus() {
        val running = getSharedPreferences("adam_prefs", MODE_PRIVATE)
            .getBoolean("service_running", false)

        if (running) {
            tvStatus.text = "Status: Running"
            tvStatus.setTextColor(0xFF43A047.toInt())
            btnStart.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
        } else {
            tvStatus.text = "Status: Not running"
            tvStatus.setTextColor(0xFFAAAAAA.toInt())
            btnStart.visibility = View.VISIBLE
            btnStop.visibility = View.GONE
        }
    }
}
