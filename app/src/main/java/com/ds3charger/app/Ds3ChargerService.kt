package com.ds3charger.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Foreground service: owns all USB/HID work for every currently-plugged-in
 * DualShock 3 (moved here from MainActivity so it keeps running - and the
 * persistent notification keeps showing live battery % - after the user
 * leaves the app's screen, not just while it's the visible activity).
 *
 * Multi-controller: keyed by UsbDevice.deviceId (a stable per-connection-
 * instance int Android assigns while a physical USB device stays attached),
 * so plugging in more than one DS3 via a hub tracks/charges/polls each
 * independently instead of only ever handling the first one found.
 */
class Ds3ChargerService : Service() {

    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.ds3charger.app.USB_PERMISSION"

    private val SONY_VENDOR_ID = 0x054C
    private val DS3_PRODUCT_ID = 0x0268

    // Battery reporting, verified against the real Linux kernel source
    // (drivers/hid/hid-sony.c, sixaxis_parse_report()): byte 30 of the
    // standard 49-byte USB input report (HID report ID 0x01) carries
    // charge state. >=0xee means charging/full (no live level while
    // charging - the controller doesn't report it), else it's a 0-5 index
    // into a fixed table, NOT a raw percentage.
    private val INPUT_REPORT_ID = 0x01
    private val INPUT_REPORT_SIZE = 49
    private val BATTERY_BYTE_OFFSET = 30
    private val SIXAXIS_BATTERY_CAPACITY = intArrayOf(0, 1, 25, 50, 75, 100)

    // LED output report, verified against hid-sony.c's sixaxis_send_output_
    // report()/struct sixaxis_output_report - same layout used before for
    // the (reverted) LED chase, kept here for a single ONE-SHOT "fully
    // charged" indicator instead: set once when a device transitions to
    // Full, not a repeating animation, so it doesn't fight the game/OS's
    // own LED state the way a continuous cycle would.
    private val LED_DEFAULT_REPORT = intArrayOf(
        0x01,
        0x01, 0xff, 0x00, 0xff, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00,
        0xff, 0x27, 0x10, 0x00, 0x32,
        0xff, 0x27, 0x10, 0x00, 0x32,
        0xff, 0x27, 0x10, 0x00, 0x32,
        0xff, 0x27, 0x10, 0x00, 0x32,
        0x00, 0x00, 0x00, 0x00, 0x00
    ).map { it.toByte() }.toByteArray()
    private val LED_BITMAP_OFFSET = 10
    private val LED_FULL_CHARGE_BIT = 0x10  // LED4, arbitrary but distinct from typical player-1/2/3 slots

    private class DeviceState(
        var connection: UsbDeviceConnection,
        var intf: UsbInterface,
        var infoLine: String,
        var lastBatteryPct: Int = -1,
        var lastStatus: String = "",
        var fullLedSet: Boolean = false,
    )

    // synchronizedMap since bgHandler (poll/charge-command work) and the main
    // thread (USB_DEVICE_DETACHED) both mutate this.
    private val devices = java.util.Collections.synchronizedMap(mutableMapOf<Int, DeviceState>())

    // All blocking USB control transfers (open/claim/controlTransfer/release)
    // run here, never on the main thread - controlTransfer blocks for up to
    // its timeout (2-5s), and with multiple DS3s on a hub the old code's
    // main-thread Handler.forEach{ pollBattery } serialized N*2000ms+ of
    // blocking calls right on the UI thread. Real jank/ANR risk on Shield.
    private val bgThread = HandlerThread("Ds3ChargerPoll").apply { start() }
    private val bgHandler = Handler(bgThread.looper)

    private val MAX_CHARGE_COMMAND_ATTEMPTS = 3
    private val CHARGE_COMMAND_RETRY_DELAY_MS = 500L

    // While a device is actively "Charging" it's closing in on Full - poll
    // fast so the one-shot full-charge LED/notification react quickly,
    // regardless of the user's base interval setting. Once it hits Full or
    // sits "On battery", drop back to the base interval - no urgency there.
    private val FAST_POLL_INTERVAL_MS = 30_000L

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var listener: Listener? = null

    interface Listener {
        fun onStatusUpdate(text: String)
    }

    fun setListener(l: Listener?) {
        listener = l
        l?.onStatusUpdate(buildStatusText())
    }

    inner class LocalBinder : Binder() {
        fun getService(): Ds3ChargerService = this@Ds3ChargerService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val pollRunnable = object : Runnable {
        override fun run() {
            // Copy under lock, then poll outside it - pollBattery's blocking
            // controlTransfer calls shouldn't hold the map lock the whole time.
            val snapshot = synchronized(devices) { devices.values.toList() }
            snapshot.forEach { pollBattery(it) }
            // Read fresh from Prefs every tick (not cached) so a change
            // made in SettingsActivity takes effect on the very next poll,
            // no service restart/rebind needed.
            val baseIntervalMs = Prefs.getPollIntervalMinutes(this@Ds3ChargerService) * 60 * 1000L
            val anyCharging = snapshot.any { it.lastStatus == "Charging" }
            val intervalMs = if (anyCharging) minOf(FAST_POLL_INTERVAL_MS, baseIntervalMs) else baseIntervalMs
            bgHandler.postDelayed(this, intervalMs)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        @Suppress("DEPRECATION")
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { sendChargeCommand(it) }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let { checkAndRequestDevice(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        devices.remove(it.deviceId)?.connection?.close()
                        refreshUi()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Watching for a DualShock 3..."))

        // Covers the case where the DS3 was already plugged in before this
        // service started (e.g. service restarted by the OS, or the app
        // was launched manually while it was already connected) - the
        // USB_DEVICE_ATTACHED broadcast only fires on a NEW plug-in event.
        usbManager.deviceList.values
            .filter { it.vendorId == SONY_VENDOR_ID && it.productId == DS3_PRODUCT_ID }
            .forEach { checkAndRequestDevice(it) }

        bgHandler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun checkAndRequestDevice(device: UsbDevice) {
        if (device.vendorId != SONY_VENDOR_ID || device.productId != DS3_PRODUCT_ID) return
        if (devices.containsKey(device.deviceId)) return  // already tracked
        if (usbManager.hasPermission(device)) {
            sendChargeCommand(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, device.deviceId, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun sendChargeCommand(device: UsbDevice) {
        // Enqueue onto the dedicated USB thread - callers (usbReceiver,
        // onCreate's initial scan) run on the main thread and must not block
        // on the control transfer below.
        bgHandler.post { sendChargeCommandAttempt(device, attempt = 1) }
    }

    private fun sendChargeCommandAttempt(device: UsbDevice, attempt: Int) {
        if (devices.containsKey(device.deviceId)) return  // already tracked by an earlier attempt

        val connection: UsbDeviceConnection? = usbManager.openDevice(device)
        if (connection == null) {
            retryChargeCommand(device, attempt, "openDevice failed")
            return
        }
        val intf = device.getInterface(0)
        if (!connection.claimInterface(intf, true)) {
            connection.close()
            retryChargeCommand(device, attempt, "claimInterface failed")
            return
        }

        // HID GET_REPORT, Feature report 0xF2, 17-byte buffer - matches
        // hid-sony.c's sixaxis_set_operational_usb() step 1 exactly (see
        // MainActivity's original comment history for the full byte-level
        // citation): bmRequestType 0xA1, bRequest 0x01, wValue 0x03F2.
        val buf = ByteArray(17)
        val result = connection.controlTransfer(0xA1, 0x01, 0x03F2, intf.id, buf, buf.size, 5000)

        // Release right away - holding it exclusively blocks any other
        // consumer (the game, the OS's own USB-HID path) for as long as
        // this service runs. Real regression found 2026-07-13 from an
        // earlier version of this app holding it continuously.
        connection.releaseInterface(intf)

        if (result < 0) {
            connection.close()
            retryChargeCommand(device, attempt, "controlTransfer failed (result=$result)")
            return
        }
        val name = try {
            "${device.manufacturerName ?: "Sony"} ${device.productName ?: "PLAYSTATION(R)3 Controller"}"
        } catch (e: Exception) { "Sony PLAYSTATION(R)3 Controller" }
        val infoLine = "Device: $name\nVID=0x${device.vendorId.toString(16)} " +
            "PID=0x${device.productId.toString(16)}  Interfaces=${device.interfaceCount}"
        devices[device.deviceId] = DeviceState(connection, intf, infoLine)
        refreshUi()
    }

    // A transient claim/transfer failure (device still enumerating, briefly
    // busy right after plug-in, etc) used to permanently drop the device
    // until physical replug - USB_DEVICE_ATTACHED only fires once per plug
    // event. Retry a few times with a short delay before giving up.
    private fun retryChargeCommand(device: UsbDevice, attempt: Int, reason: String) {
        if (attempt >= MAX_CHARGE_COMMAND_ATTEMPTS) return
        bgHandler.postDelayed(
            { sendChargeCommandAttempt(device, attempt + 1) },
            CHARGE_COMMAND_RETRY_DELAY_MS
        )
    }

    private fun pollBattery(state: DeviceState) {
        // Claim -> read -> release EVERY poll (not held continuously) - see
        // sendChargeCommand()'s note above, same reasoning applies here.
        state.connection.claimInterface(state.intf, true)
        val buf = ByteArray(INPUT_REPORT_SIZE)
        val result = state.connection.controlTransfer(
            0xA1, 0x01, (0x01 shl 8) or INPUT_REPORT_ID, state.intf.id, buf, buf.size, 2000
        )
        state.connection.releaseInterface(state.intf)

        if (result <= BATTERY_BYTE_OFFSET) {
            state.lastStatus = "Battery: unavailable (short report)"
            refreshUi()
            return
        }
        val raw = buf[BATTERY_BYTE_OFFSET].toInt() and 0xFF
        val (pct, status) = when {
            raw >= 0xee -> 100 to (if (raw and 0x01 == 1) "Full" else "Charging")
            else -> SIXAXIS_BATTERY_CAPACITY[minOf(raw, 5)] to "On battery (not charging)"
        }
        state.lastBatteryPct = pct
        state.lastStatus = status

        // One-shot "fully charged" LED - set ONCE on the Charging->Full
        // transition, not repeated every poll and not an animation, so it
        // can't fight the game/OS's own LED state the way the earlier
        // (reverted) continuous chase did.
        if (status == "Full" && !state.fullLedSet) {
            if (Prefs.isLedEnabled(this)) setLed(state, LED_FULL_CHARGE_BIT)
            state.fullLedSet = true
        } else if (status != "Full") {
            state.fullLedSet = false
        }
        refreshUi()
    }

    private fun setLed(state: DeviceState, bitmap: Int) {
        val buf = LED_DEFAULT_REPORT.copyOf()
        buf[LED_BITMAP_OFFSET] = bitmap.toByte()
        state.connection.claimInterface(state.intf, true)
        // bmRequestType 0x21 = Class | Interface | Host-to-Device
        // bRequest      0x09 = SET_REPORT
        // wValue      0x0201 = ReportType Output(2) << 8 | ReportID 0x01
        state.connection.controlTransfer(0x21, 0x09, 0x0201, state.intf.id, buf, buf.size, 1000)
        state.connection.releaseInterface(state.intf)
    }

    private fun buildStatusText(): String {
        val snapshot = synchronized(devices) { devices.values.toList() }
        if (snapshot.isEmpty()) {
            return "No DualShock 3 connected.\nPlug it in (this app will auto-launch), or leave this open and plug it in now."
        }
        val time = timeFmt.format(java.util.Date())
        return snapshot.joinToString("\n\n") { s ->
            "${s.infoLine}\n[$time] Battery: ${s.lastBatteryPct}%  (${s.lastStatus})"
        }
    }

    private fun refreshUi() {
        listener?.onStatusUpdate(buildStatusText())
        updateNotification()
    }

    private fun updateNotification() {
        val snapshot = synchronized(devices) { devices.values.toList() }
        val text = if (snapshot.isEmpty()) {
            "No controller connected"
        } else {
            snapshot.joinToString("  |  ") { "${it.lastBatteryPct}% ${it.lastStatus}" }
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DS3 Charger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DS3 Charger status", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Live battery % while a DualShock 3 is charging" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bgHandler.removeCallbacksAndMessages(null)
        bgThread.quitSafely()
        synchronized(devices) {
            devices.values.forEach { it.connection.close() }
            devices.clear()
        }
        unregisterReceiver(usbReceiver)
    }

    companion object {
        private const val CHANNEL_ID = "ds3_charger_status"
        private const val NOTIF_ID = 1
    }
}
