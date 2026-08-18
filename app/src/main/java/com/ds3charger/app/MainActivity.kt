package com.ds3charger.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Thin UI shell - all real USB/HID work + the persistent notification live
 * in Ds3ChargerService (started here, keeps running after this screen is
 * left). This activity just displays whatever the service reports, via a
 * bound-service Listener callback, and requests notification permission on
 * API 33+ (needed for the service's persistent battery-% notification to
 * actually show).
 *
 * Each connected controller renders as its own card (title/detail/battery/
 * auth result), built dynamically since the controller count varies - no
 * RecyclerView needed given counts are always small. Each USB card gets its
 * own Check Authenticity / Test Rumble buttons so a multi-controller setup
 * can drive them independently instead of one screen-wide action.
 */
class MainActivity : Activity(), Ds3ChargerService.Listener {

    private lateinit var statusView: TextView
    private lateinit var devicesContainer: LinearLayout
    private var service: Ds3ChargerService? = null
    private var bound = false

    // Rebuilt fresh every onDevicesUpdate - lets onAuthCheckProgress/Done
    // update just the one card's result text + re-enable just its button,
    // without a full card rebuild (which would happen anyway on the next
    // periodic refresh and pick up the persisted result then).
    private val authLineViews = mutableMapOf<Int, TextView>()
    private val checkButtons = mutableMapOf<Int, Button>()

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
        devicesContainer = findViewById(R.id.devicesContainer)
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()

        // Real bug found+fixed 2026-08-17: when this app is the registered default handler
        // for the DS3's device_filter (the normal case after first-time setup), Android
        // delivers USB_DEVICE_ATTACHED by launching THIS activity directly with the device in
        // the intent -- it does NOT also send a general broadcast that the service's own
        // Context-registered usbReceiver would catch. Previously this intent's device extra
        // was silently discarded (the service was just started/bound generically), so a
        // controller plugged in while the app/service wasn't already tracking it -- e.g. a
        // fully powered-off DS3, since it enumerates fresh on every plug -- never actually
        // triggered the charge-command handshake at all. Confirmed live: MainActivity launched,
        // zero Ds3Charger log lines, "No DualShock 3 connected" shown despite the controller
        // being plugged in and USB permission already granted. Forwarding the device through
        // to the service closes the gap.
        @Suppress("DEPRECATION")
        val attachedDevice: android.hardware.usb.UsbDevice? =
            if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            } else null

        val svcIntent = Intent(this, Ds3ChargerService::class.java).apply {
            if (attachedDevice != null) {
                action = android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
                putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, attachedDevice)
            }
        }
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
        // Only meaningful as the "no controller connected" empty-state
        // message now - onDevicesUpdate owns the connected-device display.
        runOnUiThread { statusView.text = text }
    }

    override fun onDevicesUpdate(cards: List<Ds3ChargerService.DeviceCardInfo>) {
        runOnUiThread {
            statusView.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
            devicesContainer.removeAllViews()
            authLineViews.clear()
            checkButtons.clear()
            for (card in cards) devicesContainer.addView(buildCardView(card))
        }
    }

    override fun onAuthCheckProgress(deviceId: Int, secondsLeft: Int) {
        runOnUiThread {
            authLineViews[deviceId]?.text = "Checking... ${secondsLeft}s left - keep moving the stick/button"
        }
    }

    override fun onAuthCheckDone(deviceId: Int, result: Ds3ChargerService.AuthCheckResult) {
        runOnUiThread {
            authLineViews[deviceId]?.text = "${result.verdict}\n${result.detail}"
            checkButtons[deviceId]?.isEnabled = true
        }
    }

    private fun buildCardView(card: Ds3ChargerService.DeviceCardInfo): View {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFE8E8E8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        }
        container.addView(TextView(this).apply {
            text = card.title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        container.addView(TextView(this).apply { text = card.detail; textSize = 14f })
        container.addView(TextView(this).apply { text = card.batteryLine; textSize = 14f })

        val authView = TextView(this).apply {
            text = card.authLine ?: ""
            textSize = 14f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        container.addView(authView)

        // Only USB devices can be checked/rumbled - Bluetooth-only entries
        // (deviceId == null) have no UsbDeviceConnection for the service to
        // act on, so they just show status, no action buttons.
        val id = card.deviceId
        if (id != null) {
            authLineViews[id] = authView
            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            val checkBtn = Button(this).apply {
                text = "Check Authenticity"
                setOnClickListener {
                    isEnabled = false
                    authView.text = "Move a stick or press a button gently - checking..."
                    service?.startAuthenticityCheck(id)
                }
            }
            checkButtons[id] = checkBtn
            val rumbleBtn = Button(this).apply {
                text = "Test Rumble"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (8 * dp).toInt() }
                setOnClickListener { service?.testRumble(id) }
            }
            // Real DS3 pairing is USB-write-driven (see pairToHost's kdoc) -
            // restores wireless use after a factory reset wipes the
            // controller's stored master address. A normal app can't read
            // its own device's real Bluetooth MAC on this Android version
            // (privacy restriction since Android 6.0), so the target host's
            // MAC is typed in each time rather than auto-detected or
            // hardcoded - no device-specific address belongs in public
            // source. Find yours via Settings, or on the host machine
            // itself (e.g. `adb shell settings get secure bluetooth_address`
            // on an Android host).
            val pairBtn = Button(this).apply {
                text = "Pair to Host..."
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (8 * dp).toInt() }
                setOnClickListener { promptForHostMacAndPair(id) }
            }
            buttonRow.addView(checkBtn)
            buttonRow.addView(rumbleBtn)
            buttonRow.addView(pairBtn)
            container.addView(buttonRow)
        }

        return container
    }

    private fun promptForHostMacAndPair(deviceId: Int) {
        val input = EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
        }
        AlertDialog.Builder(this)
            .setTitle("Pair to host")
            .setMessage("Enter the target host's Bluetooth MAC address.")
            .setView(input)
            .setPositiveButton("Pair") { _, _ ->
                val mac = input.text.toString().trim()
                if (mac.isNotEmpty()) service?.pairToHost(deviceId, mac)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
