package com.ds3charger.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView

/**
 * Thin UI shell - all real USB/HID work + the persistent notification live
 * in Ds3ChargerService (started here, keeps running after this screen is
 * left). This activity just displays whatever the service reports, via a
 * bound-service Listener callback, and requests notification permission on
 * API 33+ (needed for the service's persistent battery-% notification to
 * actually show).
 */
class MainActivity : Activity(), Ds3ChargerService.Listener {

    private lateinit var statusView: TextView
    private lateinit var authCheckButton: Button
    private lateinit var authCheckResultView: TextView
    private var service: Ds3ChargerService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as Ds3ChargerService.LocalBinder).getService()
            service?.setListener(this@MainActivity)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusText)
        authCheckButton = findViewById(R.id.authCheckButton)
        authCheckResultView = findViewById(R.id.authCheckResult)
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        authCheckButton.setOnClickListener {
            val deviceId = service?.firstConnectedDeviceId()
            if (deviceId == null) {
                authCheckResultView.text = "No controller connected."
                return@setOnClickListener
            }
            authCheckButton.isEnabled = false
            authCheckResultView.text = "Move a stick or press a button gently - checking..."
            service?.startAuthenticityCheck(deviceId)
        }

        requestNotificationPermissionIfNeeded()

        val svcIntent = Intent(this, Ds3ChargerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
        bindService(svcIntent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStatusUpdate(text: String) {
        runOnUiThread { statusView.text = text }
    }

    override fun onAuthCheckProgress(secondsLeft: Int) {
        runOnUiThread { authCheckResultView.text = "Checking... ${secondsLeft}s left - keep moving the stick/button" }
    }

    override fun onAuthCheckDone(result: Ds3ChargerService.AuthCheckResult) {
        runOnUiThread {
            authCheckButton.isEnabled = true
            authCheckResultView.text = "${result.verdict}\n${result.detail}"
        }
    }

    override fun onStop() {
        super.onStop()
        // Unbind (Activity no longer needs live updates), but do NOT stop
        // the service - it keeps polling/charging-monitoring + the
        // notification running in the background, the whole point of the
        // foreground-service move.
        service?.setListener(null)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService(Intent(this, Ds3ChargerService::class.java), connection, Context.BIND_AUTO_CREATE)
            bound = true
        }
    }
}
