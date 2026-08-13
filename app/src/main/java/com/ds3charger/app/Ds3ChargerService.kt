package com.ds3charger.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothManager
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
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

    // 25, not some rounder number like 20 - it's the nearest tier the
    // hardware can actually report (see SIXAXIS_BATTERY_CAPACITY above),
    // so the alert fires on a real reading instead of an unreachable value.
    private val LOW_BATTERY_THRESHOLD_PCT = 25

    // Authenticity check: same 49-byte input report as the battery byte above,
    // but the stick/button offsets aren't custom-parsed by hid-sony.c (unlike
    // battery/motion, which the kernel driver hand-decodes) - sticks/buttons
    // are standard HID, generically mapped from the device's own report
    // descriptor. Sourced from the DS3's actual HID Report Descriptor
    // (eleccelerator.com/wiki DualShock_3, archived snapshot fetched
    // 2026-08-13 after the live page was Cloudflare-blocked): bytes 6-9 are
    // left stick X/Y and right stick X/Y; bytes 13-24 are 12 analog pressure
    // values (D-pad x4, L2/R2/L1/R1, Triangle/Circle/Cross/Square), 0=released
    // to 255=fully pressed. Genuine Sony hardware uses a real (~10-bit) ADC on
    // these; cheap clone boards commonly upscale a coarser (4-bit/8-bit) ADC
    // to fit the 8-bit report field, so a slow sweep only ever lands on a few
    // widely, evenly spaced values instead of many close ones. Not
    // cryptographic proof - a worn/aged pot on genuine hardware could look
    // ambiguous too - but a real, sourced, immutable-hardware signal, unlike
    // the rewritable Bluetooth pairing MAC the first version of this check
    // used (reverted 2026-08-13 - see project memory for why that was wrong).
    private val AUTH_CHECK_STICK_OFFSETS = intArrayOf(6, 7, 8, 9)
    private val AUTH_CHECK_PRESSURE_OFFSETS = (13..24).toList().toIntArray()
    private val AUTH_CHECK_LABELS = mapOf(
        6 to "Left stick X", 7 to "Left stick Y", 8 to "Right stick X", 9 to "Right stick Y",
        13 to "D-pad Left", 14 to "D-pad Down", 15 to "D-pad Right", 16 to "D-pad Up",
        17 to "L2", 18 to "R2", 19 to "L1", 20 to "R1",
        21 to "Triangle", 22 to "Circle", 23 to "Cross", 24 to "Square",
    )
    private val AUTH_CHECK_DURATION_MS = 6000L
    private val AUTH_CHECK_SAMPLE_INTERVAL_MS = 50L
    // Gate out channels the user didn't actually move enough to judge fairly.
    private val AUTH_CHECK_MIN_RANGE = 40
    private val AUTH_CHECK_MIN_DISTINCT = 5
    // A gap this size or larger between consecutive OBSERVED values on the
    // channel with the most movement is the coarse-ADC tell.
    private val AUTH_CHECK_COARSE_GAP = 8

    private class DeviceState(
        var connection: UsbDeviceConnection,
        var intf: UsbInterface,
        var infoLine: String,
        var deviceId: Int,
        var lastBatteryPct: Int = -1,
        var lastStatus: String = "",
        var consecutivePollFailures: Int = 0,
    )

    // A dead/gone connection just sits reporting "unavailable" forever
    // otherwise, holding the interface claim and never getting evicted.
    private val MAX_POLL_FAILURES = 3

    // Battery over Bluetooth (unplugged, wireless use - the actual common
    // case, as opposed to USB-connected-but-not-charging). Read via
    // Android's own InputDevice battery API (real public API since Android
    // 12/API 31, sourced from the same kernel power_supply node the DS3's
    // hid-sony driver already exposes) - completely separate code path
    // from the USB control-transfer polling above, since UsbManager has no
    // visibility into a Bluetooth-only connection at all.
    private class BtDeviceState(val descriptor: String, val name: String, var lastBatteryPct: Int = -1)
    private val btDevices = java.util.Collections.synchronizedMap(mutableMapOf<String, BtDeviceState>())

    // synchronizedMap since bgHandler (poll/charge-command work) and the main
    // thread (USB_DEVICE_DETACHED) both mutate this.
    private val devices = java.util.Collections.synchronizedMap(mutableMapOf<Int, DeviceState>())

    // Reserved deviceIds that have a charge-command attempt in flight but
    // haven't landed in `devices` yet (claiming the interface + the control
    // transfer below can take up to ~5s). Without this, a second
    // USB_DEVICE_ATTACHED for the same device - a replug bounce, or a hub
    // renumbering during that window - would pass checkAndRequestDevice's
    // "already tracked" check twice and kick off a duplicate permission
    // request + claimInterface race on the same physical device. Always
    // mutate this together with `devices` under the same `devices` lock.
    private val pendingDeviceIds = mutableSetOf<Int>()

    // All blocking USB control transfers (open/claim/controlTransfer/release)
    // run here, never on the main thread - controlTransfer blocks for up to
    // its timeout (2-5s), and with multiple DS3s on a hub the old code's
    // main-thread Handler.forEach{ pollBattery } serialized N*2000ms+ of
    // blocking calls right on the UI thread. Real jank/ANR risk on Shield.
    private val bgThread = HandlerThread("Ds3ChargerPoll").apply { start() }
    private val bgHandler = Handler(bgThread.looper)

    private val MAX_CHARGE_COMMAND_ATTEMPTS = 5
    private val CHARGE_COMMAND_RETRY_BASE_DELAY_MS = 500L

    // While a device is actively "Charging" it's closing in on Full - poll
    // fast so the notification reacts quickly, regardless of the user's base
    // interval setting. Once it hits Full or sits "On battery", drop back to
    // the base interval - no urgency there.
    private val FAST_POLL_INTERVAL_MS = 30_000L

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var listener: Listener? = null
    private var lastNotifiedText: String? = null
    // Only for posting auth-check callbacks back to the UI thread - bgHandler
    // (where the check's sampling loop runs) is a background HandlerThread.
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Listener {
        fun onStatusUpdate(text: String)
        fun onAuthCheckProgress(secondsLeft: Int) {}
        fun onAuthCheckDone(result: AuthCheckResult) {}
    }

    data class AuthCheckResult(val verdict: String, val detail: String)

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
            pollBluetoothControllers()
            // Single refresh after the whole batch - pollBattery used to call
            // this per-device, so N controllers meant N notify()/listener
            // calls per tick instead of 1.
            refreshUi()
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
                        } else {
                            // Denied - release the reservation checkAndRequestDevice
                            // took, otherwise this deviceId is stuck "pending"
                            // forever with no attempt ever landing in `devices`.
                            device?.let { synchronized(devices) { pendingDeviceIds.remove(it.deviceId) } }
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
                        synchronized(devices) {
                            devices.remove(it.deviceId)?.connection?.close()
                            pendingDeviceIds.remove(it.deviceId)
                        }
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
        // Atomically check-and-reserve: closes the TOCTOU window where a
        // second ATTACHED broadcast for the same device lands before the
        // first attempt's claimInterface/controlTransfer (up to ~5s) has
        // added it to `devices`.
        synchronized(devices) {
            if (devices.containsKey(device.deviceId)) return  // already tracked
            if (!pendingDeviceIds.add(device.deviceId)) return  // already pending
        }
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
        synchronized(devices) {
            devices[device.deviceId] = DeviceState(connection, intf, infoLine, device.deviceId)
            pendingDeviceIds.remove(device.deviceId)
        }
        refreshUi()
    }

    // A transient claim/transfer failure (device still enumerating, briefly
    // busy right after plug-in, etc) used to permanently drop the device
    // until physical replug - USB_DEVICE_ATTACHED only fires once per plug
    // event. Retry a few times with a short delay before giving up.
    private fun retryChargeCommand(device: UsbDevice, attempt: Int, reason: String) {
        if (attempt >= MAX_CHARGE_COMMAND_ATTEMPTS) {
            synchronized(devices) { pendingDeviceIds.remove(device.deviceId) }
            return
        }
        // Linear backoff (500ms, 1000ms, 1500ms...) - some hubs are slower
        // to finish enumerating than a flat delay accounts for.
        bgHandler.postDelayed(
            { sendChargeCommandAttempt(device, attempt + 1) },
            CHARGE_COMMAND_RETRY_BASE_DELAY_MS * attempt
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
            state.consecutivePollFailures++
            if (state.consecutivePollFailures >= MAX_POLL_FAILURES) {
                // Connection's dead (device gone but DETACHED hasn't/won't
                // fire, e.g. a hub power fault) - stop holding the claim on
                // it forever, let a future re-plug get a clean attempt.
                synchronized(devices) {
                    devices.remove(state.deviceId)
                    pendingDeviceIds.remove(state.deviceId)
                }
                state.connection.close()
                return
            }
            state.lastStatus = "Battery: unavailable (short report)"
            return
        }
        state.consecutivePollFailures = 0
        val raw = buf[BATTERY_BYTE_OFFSET].toInt() and 0xFF
        val (pct, status) = when {
            raw >= 0xee -> 100 to (if (raw and 0x01 == 1) "Full" else "Charging")
            else -> SIXAXIS_BATTERY_CAPACITY[minOf(raw, 5)] to "On battery (not charging)"
        }
        // Fire the charge-complete alert on the Charging->Full edge only -
        // that's the one real transition the hardware exposes while plugged
        // in (see the class-level comment: no live % while charging, just
        // Charging vs Full). Guarded on the previous status specifically
        // (not "status changed") so a fresh poll of an already-Full
        // controller, or Full->unplugged, never fires one.
        if (status == "Full" && state.lastStatus == "Charging" && Prefs.isChargeAlertsEnabled(this)) {
            sendChargeCompleteAlert(state)
        }
        // Edge-triggered the same way as the charge-complete alert (real
        // previous reading required, not just "below threshold") so it
        // fires once on the way down, not every poll while it sits low,
        // and never on the very first poll of a freshly-connected device.
        if (status == "On battery (not charging)" && Prefs.isLowBatteryAlertsEnabled(this) &&
            state.lastBatteryPct != -1 && state.lastBatteryPct > LOW_BATTERY_THRESHOLD_PCT &&
            pct <= LOW_BATTERY_THRESHOLD_PCT
        ) {
            sendLowBatteryAlert(state, pct)
        }
        state.lastBatteryPct = pct
        state.lastStatus = status
    }

    // Returns the first USB-connected DS3's deviceId, or null if none - this
    // screen only ever drives one check at a time, matching MainActivity's
    // existing single-status-blob UX (it doesn't offer per-device selection
    // anywhere else either).
    fun firstConnectedDeviceId(): Int? = synchronized(devices) { devices.keys.firstOrNull() }

    // Runs on the caller's thread if called directly - always call via
    // bgHandler.post from outside this class (MainActivity does).
    fun startAuthenticityCheck(deviceId: Int) {
        val state = devices[deviceId]
        if (state == null) {
            listener?.onAuthCheckDone(AuthCheckResult("Not connected", "Plug in the controller first."))
            return
        }
        bgHandler.post { runAuthenticityCheck(state) }
    }

    // Blocks bgHandler for the check's duration (same thread pollBattery/
    // sendChargeCommand already use for their own blocking control transfers)
    // - other tracked controllers' polling is delayed for these few seconds,
    // acceptable since this is a rare, short, user-initiated one-off action,
    // not continuous work.
    private fun runAuthenticityCheck(state: DeviceState) {
        val allOffsets = AUTH_CHECK_STICK_OFFSETS + AUTH_CHECK_PRESSURE_OFFSETS
        val samples = allOffsets.associateWith { mutableListOf<Int>() }

        val startTime = System.currentTimeMillis()
        val endTime = startTime + AUTH_CHECK_DURATION_MS
        var lastSecondReported = -1

        while (System.currentTimeMillis() < endTime) {
            val elapsedMs = System.currentTimeMillis() - startTime
            val secondsLeft = ((AUTH_CHECK_DURATION_MS - elapsedMs) / 1000).toInt() + 1
            if (secondsLeft != lastSecondReported) {
                lastSecondReported = secondsLeft
                mainHandler.post { listener?.onAuthCheckProgress(secondsLeft) }
            }
            state.connection.claimInterface(state.intf, true)
            val buf = ByteArray(INPUT_REPORT_SIZE)
            val result = state.connection.controlTransfer(
                0xA1, 0x01, (0x01 shl 8) or INPUT_REPORT_ID, state.intf.id, buf, buf.size, 500
            )
            state.connection.releaseInterface(state.intf)
            if (result > 24) {
                for (off in allOffsets) samples.getValue(off).add(buf[off].toInt() and 0xFF)
            }
            Thread.sleep(AUTH_CHECK_SAMPLE_INTERVAL_MS)
        }

        val result = analyzeAuthCheck(samples)
        mainHandler.post { listener?.onAuthCheckDone(result) }
    }

    private fun analyzeAuthCheck(samples: Map<Int, List<Int>>): AuthCheckResult {
        var bestOffset = -1
        var bestRange = 0
        var bestDistinct = 0
        var bestMinGap = 0

        for ((offset, values) in samples) {
            val distinct = values.toSortedSet()
            val range = (distinct.maxOrNull() ?: 0) - (distinct.minOrNull() ?: 0)
            if (range < AUTH_CHECK_MIN_RANGE || distinct.size < AUTH_CHECK_MIN_DISTINCT) continue
            val minGap = distinct.toList().zipWithNext { a, b -> b - a }.minOrNull() ?: 0
            if (range > bestRange) {
                bestRange = range
                bestOffset = offset
                bestDistinct = distinct.size
                bestMinGap = minGap
            }
        }

        if (bestOffset == -1) {
            return AuthCheckResult(
                "Not enough movement",
                "Move a stick through its full range, or press a button gradually (not just tap it), while the check runs."
            )
        }

        val label = AUTH_CHECK_LABELS[bestOffset] ?: "channel"
        return if (bestMinGap >= AUTH_CHECK_COARSE_GAP) {
            AuthCheckResult(
                "Stepped response - clone-typical",
                "$label: only $bestDistinct distinct values across a range of $bestRange, smallest gap $bestMinGap. " +
                    "A coarse, evenly-spaced jump like this matches a low-resolution ADC upscaled to fit the report."
            )
        } else {
            AuthCheckResult(
                "Smooth response - genuine-consistent",
                "$label: $bestDistinct distinct values across a range of $bestRange, smallest gap $bestMinGap. " +
                    "Fine-grained variation like this is consistent with genuine Sony hardware."
            )
        }
    }

    private fun sendChargeCompleteAlert(state: DeviceState) {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("DS3 fully charged")
            .setContentText(state.infoLine.substringBefore("\n"))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Unique per-device alert ID (distinct from the shared ongoing
        // NOTIF_ID) so a second controller finishing doesn't overwrite/
        // dismiss the first one's alert before the user sees it.
        nm.notify(ALERT_NOTIF_ID_BASE + state.deviceId, notif)
    }

    private fun sendLowBatteryAlert(state: DeviceState, pct: Int) {
        sendLowBatteryAlertFor(state.infoLine.substringBefore("\n"), pct, LOW_BATTERY_NOTIF_ID_BASE + state.deviceId)
    }

    private fun sendLowBatteryAlertFor(deviceDescription: String, pct: Int, notifId: Int) {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("DS3 battery low ($pct%)")
            .setContentText(deviceDescription)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Separate ID range from the charge-complete alerts (ALERT_NOTIF_ID_BASE)
        // so both can be posted/visible independently per device.
        nm.notify(notifId, notif)
    }

    // Skips entirely while any USB-connected controller is tracked - that
    // path already gives richer, faster, real-time battery data for the
    // common single-controller case, so there's no need to double-track
    // the same physical unit over two different code paths. A controller
    // that's genuinely wireless-only (never plugged in this session) is
    // unaffected by this check.
    private fun pollBluetoothControllers() {
        if (devices.isNotEmpty()) return
        val im = getSystemService(Context.INPUT_SERVICE) as InputManager
        val seen = mutableSetOf<String>()
        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            if (dev.vendorId != SONY_VENDOR_ID || dev.productId != DS3_PRODUCT_ID) continue
            seen.add(dev.descriptor)
            val pct = readBluetoothBatteryPct(dev) ?: continue
            val state = btDevices.getOrPut(dev.descriptor) { BtDeviceState(dev.descriptor, dev.name) }
            if (Prefs.isLowBatteryAlertsEnabled(this) &&
                state.lastBatteryPct != -1 && state.lastBatteryPct > LOW_BATTERY_THRESHOLD_PCT &&
                pct <= LOW_BATTERY_THRESHOLD_PCT
            ) {
                sendLowBatteryAlertFor(state.name, pct, LOW_BATTERY_NOTIF_ID_BASE + dev.id)
            }
            state.lastBatteryPct = pct
        }
        // Drop entries for controllers that disconnected, so a later
        // reconnect starts fresh (no stale baseline for the edge check).
        btDevices.keys.retainAll(seen)
    }

    // Prefers the real public API (InputDevice.getBatteryState(), Android
    // 12/API 31+). Falls back to BluetoothDevice.getBatteryLevel() - a
    // long-standing but @hide, undocumented AOSP method, not in the public
    // SDK - for older OS versions like this Shield's Android 11, since
    // there's no public alternative there at all. Matched to the input
    // device by name (InputDevice has no MAC to match on directly).
    // Unofficial, so wrapped defensively: any failure here just means no
    // Bluetooth battery reading this poll, not a crash.
    private fun readBluetoothBatteryPct(dev: android.view.InputDevice): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val battery = dev.batteryState
            if (battery != null && battery.isPresent && !battery.capacity.isNaN()) {
                return (battery.capacity * 100).toInt().coerceIn(0, 100)
            }
            return null
        }
        return try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
            val adapter = btManager.adapter ?: return null
            val match = adapter.bondedDevices?.firstOrNull { it.name == dev.name } ?: return null
            val method = match.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(match) as? Int ?: return null
            if (level < 0 || level > 100) null else level
        } catch (e: Exception) {
            null
        }
    }

    private fun buildStatusText(): String {
        val snapshot = synchronized(devices) { devices.values.toList() }
        val btSnapshot = synchronized(btDevices) { btDevices.values.toList() }
        if (snapshot.isEmpty() && btSnapshot.isEmpty()) {
            return "No DualShock 3 connected.\nPlug it in (this app will auto-launch), or leave this open and plug it in now."
        }
        val time = timeFmt.format(java.util.Date())
        val usbText = snapshot.map { s ->
            "${s.infoLine}\n[$time] Battery: ${s.lastBatteryPct}%  (${s.lastStatus})"
        }
        val btText = btSnapshot.map { s ->
            "${s.name} (Bluetooth)\n[$time] Battery: ${s.lastBatteryPct}%  (wireless - not charging)"
        }
        return (usbText + btText).joinToString("\n\n")
    }

    private fun refreshUi() {
        listener?.onStatusUpdate(buildStatusText())
        updateNotification()
    }

    private fun updateNotification() {
        val snapshot = synchronized(devices) { devices.values.toList() }
        val btSnapshot = synchronized(btDevices) { btDevices.values.toList() }
        val text = if (snapshot.isEmpty() && btSnapshot.isEmpty()) {
            "No controller connected"
        } else {
            val usbParts = snapshot.map { "${it.lastBatteryPct}% ${it.lastStatus}" }
            val btParts = btSnapshot.map { "${it.lastBatteryPct}% wireless" }
            (usbParts + btParts).joinToString("  |  ")
        }
        // Skip the notify() call entirely when nothing changed since last
        // tick - every poll used to rewrite the notification unconditionally.
        if (text == lastNotifiedText) return
        lastNotifiedText = text
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "DS3 Charger status", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Live battery % while a DualShock 3 is charging" }
            )
            // Separate, higher-importance channel so charge-complete alerts
            // actually pop/sound instead of silently updating like the
            // ongoing status notification above (IMPORTANCE_LOW never alerts).
            nm.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID, "DS3 Charger alerts", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Alerts when a DualShock 3 finishes charging or runs low" }
            )
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
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered (service killed mid-onCreate before the
            // registerReceiver call) - nothing to unregister.
        }
    }

    companion object {
        private const val CHANNEL_ID = "ds3_charger_status"
        private const val ALERT_CHANNEL_ID = "ds3_charger_alerts"
        private const val NOTIF_ID = 1
        // Base for per-device alert notification IDs (see sendChargeCompleteAlert) -
        // deviceId is a small positive int in practice, offsetting well clear of NOTIF_ID.
        private const val ALERT_NOTIF_ID_BASE = 1000
        private const val LOW_BATTERY_NOTIF_ID_BASE = 2000
    }
}
