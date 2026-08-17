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
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
    // descriptor. Bytes 6-9 are left stick X/Y and right stick X/Y; bytes
    // 14-25 are 12 analog pressure values (D-pad x4, L2/R2/L1/R1,
    // Triangle/Circle/Cross/Square), 0=released to 255=fully pressed.
    // Pressure-byte offsets corrected 2026-08-13 (was 13-24, off by one) -
    // cross-checked against DsHidMini's real, actively-maintained driver
    // source (github.com/nefarius/DsHidMini, include/DsHidMini/Ds3Types.h,
    // DS3_RAW_INPUT_REPORT struct - counting its fields byte-by-byte lands
    // Pressure at offset 14 and BatteryStatus at offset 30, confirming
    // BATTERY_BYTE_OFFSET above was always right but the original
    // eleccelerator.com/wiki-sourced pressure range was one byte early.
    // Stick offsets (6-9) and BatteryStatus (30) agree across both sources.
    // Genuine Sony hardware uses a real (~10-bit) ADC on these; cheap clone
    // boards commonly upscale a coarser (4-bit/8-bit) ADC to fit the 8-bit
    // report field, so a slow sweep only ever lands on a few widely, evenly
    // spaced values instead of many close ones. Not cryptographic proof - a
    // worn/aged pot on genuine hardware could look ambiguous too - but a
    // real, sourced, immutable-hardware signal, unlike the rewritable
    // Bluetooth pairing MAC the first version of this check used (reverted
    // 2026-08-13 - see project memory for why that was wrong).
    private val AUTH_CHECK_STICK_OFFSETS = intArrayOf(6, 7, 8, 9)
    private val AUTH_CHECK_PRESSURE_OFFSETS = (14..25).toList().toIntArray()
    private val AUTH_CHECK_LABELS = mapOf(
        6 to "Left stick X", 7 to "Left stick Y", 8 to "Right stick X", 9 to "Right stick Y",
        14 to "D-pad Left", 15 to "D-pad Down", 16 to "D-pad Right", 17 to "D-pad Up",
        18 to "L2", 19 to "R2", 20 to "L1", 21 to "R1",
        22 to "Triangle", 23 to "Circle", 24 to "Cross", 25 to "Square",
    )
    private val AUTH_CHECK_DURATION_MS = 6000L
    private val AUTH_CHECK_SAMPLE_INTERVAL_MS = 50L
    // Gate out channels the user didn't actually move enough to judge fairly.
    private val AUTH_CHECK_MIN_RANGE = 40
    private val AUTH_CHECK_MIN_DISTINCT = 5
    // A gap this size or larger between consecutive OBSERVED values on the
    // channel with the most movement is the coarse-ADC tell.
    private val AUTH_CHECK_COARSE_GAP = 8

    // Rumble test. Real Linux kernel struct (drivers/hid/hid-sony.c,
    // sixaxis_send_output_report + struct sixaxis_rumble/sixaxis_output_report):
    // 36-byte OUTPUT report, ID 0x01, sent via SET_REPORT (bmRequestType 0x21,
    // bRequest 0x09, wValue 0x0201 = report type Output(2)<<8 | report id 1).
    // Byte 0 = report id. Bytes 1-5 = rumble{padding, right_duration
    // (0xff=forever until told otherwise), right_motor_on(0/1, small motor),
    // left_duration(0xff=forever), left_motor_force(0-255, large motor)}.
    // Bytes 6-9 = padding. Byte 10 = LED bitmap. Bytes 11-35 = four 5-byte LED
    // blink configs + one reserved slot - copied verbatim from the kernel's
    // own default_report array so LED state is left exactly as the real
    // driver would, only the rumble bytes are touched for this test.
    private val SIXAXIS_OUTPUT_DEFAULT = byteArrayOf(
        0x01,
        0x01, 0xff.toByte(), 0x00, 0xff.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00,
        0xff.toByte(), 0x27, 0x10, 0x00, 0x32,
        0xff.toByte(), 0x27, 0x10, 0x00, 0x32,
        0xff.toByte(), 0x27, 0x10, 0x00, 0x32,
        0xff.toByte(), 0x27, 0x10, 0x00, 0x32,
        0x00, 0x00, 0x00, 0x00, 0x00,
    )
    // Max force - a steady burst and a pulsed pattern were both barely
    // perceptible in a live test at max force, hence the per-motor isolation
    // diagnostic in testRumble() below.
    private val RUMBLE_TEST_FORCE = 0xFF.toByte()

    private class DeviceState(
        var connection: UsbDeviceConnection,
        var intf: UsbInterface,
        var infoLine: String,
        var deviceId: Int,
        var lastBatteryPct: Int = -1,
        var lastStatus: String = "",
        var consecutivePollFailures: Int = 0,
        var authCheckResult: AuthCheckResult? = null,
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
        fun onDevicesUpdate(cards: List<DeviceCardInfo>) {}
        fun onAuthCheckProgress(deviceId: Int, secondsLeft: Int) {}
        fun onAuthCheckDone(deviceId: Int, result: AuthCheckResult) {}
    }

    data class AuthCheckResult(val verdict: String, val detail: String)

    // One per connected controller (USB or Bluetooth) - lets MainActivity
    // render a distinct card per device instead of one joined text blob,
    // and wire per-device Check Authenticity / Test Rumble buttons.
    // deviceId is only meaningful (non-null) for USB devices, since that's
    // the only kind startAuthenticityCheck/testRumble can act on - a
    // Bluetooth-only entry can't run either (UsbManager has no visibility
    // into it), so its card just omits those buttons on the UI side.
    data class DeviceCardInfo(
        val deviceId: Int?,
        val title: String,
        val detail: String,
        val batteryLine: String,
        val authLine: String?,
    )

    fun setListener(l: Listener?) {
        listener = l
        l?.onStatusUpdate(buildStatusText())
        l?.onDevicesUpdate(buildDeviceCards())
    }

    inner class LocalBinder : Binder() {
        fun getService(): Ds3ChargerService = this@Ds3ChargerService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // Real bug found+fixed 2026-08-17: none of this service's raw USB control-transfer calls
    // (pollBattery, runAuthenticityCheck, sendChargeCommandAttempt, testRumble, pairToHost)
    // were wrapped in try/catch, all running on the same single bgHandler thread. An uncaught
    // exception on a HandlerThread's Looper is fatal to the whole app process by default (no
    // custom UncaughtExceptionHandler here) -- one bad controlTransfer on ANY single tracked
    // controller could crash the entire app for every controller. Worse: even in a hypothetical
    // world where the process survived, pollRunnable never reached its own postDelayed
    // reschedule call if anything above it threw, so polling would silently stop forever for
    // every controller, not just the one that failed. bgPost/bgPostDelayed below catch
    // Throwable at every post site instead of letting anything propagate to the Looper.
    private fun bgPost(block: () -> Unit) {
        bgHandler.post { runCatching(block).onFailure { Log.e("Ds3Charger", "bg task failed: ${it.message}", it) } }
    }
    private fun bgPostDelayed(delayMs: Long, block: () -> Unit) {
        bgHandler.postDelayed(
            { runCatching(block).onFailure { Log.e("Ds3Charger", "bg task failed: ${it.message}", it) } },
            delayMs,
        )
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            // Reschedule is in `finally` so a bug in any single step (a bad connection, a
            // Bluetooth API quirk) can never permanently stop the poll loop for the rest of
            // the app's lifetime -- see the class-level comment on bgPost/bgPostDelayed above.
            var anyCharging = false
            try {
                // Copy under lock, then poll outside it - pollBattery's blocking
                // controlTransfer calls shouldn't hold the map lock the whole time.
                val snapshot = synchronized(devices) { devices.values.toList() }
                // Per-device try/catch: one dead/racing connection shouldn't skip polling the
                // rest of the batch this tick.
                for (state in snapshot) {
                    try {
                        pollBattery(state)
                    } catch (e: Throwable) {
                        Log.e("Ds3Charger", "pollBattery failed for deviceId=${state.deviceId}: ${e.message}", e)
                    }
                }
                anyCharging = snapshot.any { it.lastStatus == "Charging" }
                pollBluetoothControllers()
                // Single refresh after the whole batch - pollBattery used to call
                // this per-device, so N controllers meant N notify()/listener
                // calls per tick instead of 1.
                refreshUi()
            } catch (e: Throwable) {
                Log.e("Ds3Charger", "pollRunnable tick failed: ${e.message}", e)
            } finally {
                // Read fresh from Prefs every tick (not cached) so a change
                // made in SettingsActivity takes effect on the very next poll,
                // no service restart/rebind needed.
                val baseIntervalMs = Prefs.getPollIntervalMinutes(this@Ds3ChargerService) * 60 * 1000L
                val intervalMs = if (anyCharging) minOf(FAST_POLL_INTERVAL_MS, baseIntervalMs) else baseIntervalMs
                bgHandler.postDelayed(this, intervalMs)
            }
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
        bgPost { sendChargeCommandAttempt(device, attempt = 1) }
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

        if (result < 0) {
            connection.releaseInterface(intf)
            connection.close()
            retryChargeCommand(device, attempt, "controlTransfer failed (result=$result)")
            return
        }

        // Step 2 of sixaxis_set_operational_usb() - GET_REPORT feature report
        // 0xF5, 8-byte buffer. THIS APP WAS MISSING THIS ENTIRELY until now -
        // only step 1 above was ever implemented. Kernel comment: "some
        // compatible controllers... need another query plus a USB interrupt
        // to get operational." Real-world consequence found 2026-08-13: a
        // controller could read HID input reports fine (so the app showed a
        // plausible battery status) while charging never actually engaged,
        // because the controller was never fully brought into operational
        // mode. Non-fatal if it fails (most controllers don't strictly need
        // it) - logged, doesn't block the connection.
        val buf2 = ByteArray(8)
        val result2 = connection.controlTransfer(0xA1, 0x01, 0x03F5, intf.id, buf2, buf2.size, 5000)
        if (result2 < 0) {
            Log.w("Ds3Charger", "operational step 2 (0xF5) failed, result=$result2 - continuing anyway")
        } else {
            Log.d("Ds3Charger", "operational step 2 (0xF5) OK, result=$result2 bytes=${buf2.take(8)}")
        }

        // Release right away - holding it exclusively blocks any other
        // consumer (the game, the OS's own USB-HID path) for as long as
        // this service runs. Real regression found 2026-07-13 from an
        // earlier version of this app holding it continuously.
        connection.releaseInterface(intf)
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
        bgPostDelayed(CHARGE_COMMAND_RETRY_BASE_DELAY_MS * attempt) {
            sendChargeCommandAttempt(device, attempt + 1)
        }
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
        // Real hardware limit, not a parsing gap: while actively charging the DS3's own
        // charge controller owns this byte and only ever reports "still charging" vs
        // "full" - no live percentage is transmitted (confirmed against both the Linux
        // hid-sony.c driver and DsHidMini's DS3_RAW_INPUT_REPORT struct). -1 marks "no
        // real percentage available" so the UI can show "Charging..." honestly instead
        // of a fake number, rather than reusing the unrelated Full=100 value.
        val (pct, status) = when {
            raw >= 0xee -> (if (raw and 0x01 == 1) 100 else -1) to (if (raw and 0x01 == 1) "Full" else "Charging")
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

    // Runs on the caller's thread if called directly - always call via
    // bgHandler.post from outside this class (MainActivity does).
    fun startAuthenticityCheck(deviceId: Int) {
        val state = devices[deviceId]
        if (state == null) {
            mainHandler.post { listener?.onAuthCheckDone(deviceId, AuthCheckResult("Not connected", "Plug in the controller first.")) }
            return
        }
        bgPost { runAuthenticityCheck(deviceId, state) }
    }

    // Blocks bgHandler for the check's duration (same thread pollBattery/
    // sendChargeCommand already use for their own blocking control transfers)
    // - other tracked controllers' polling is delayed for these few seconds,
    // acceptable since this is a rare, short, user-initiated one-off action,
    // not continuous work.
    private fun runAuthenticityCheck(deviceId: Int, state: DeviceState) {
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
                mainHandler.post { listener?.onAuthCheckProgress(deviceId, secondsLeft) }
            }
            state.connection.claimInterface(state.intf, true)
            val buf = ByteArray(INPUT_REPORT_SIZE)
            val result = state.connection.controlTransfer(
                0xA1, 0x01, (0x01 shl 8) or INPUT_REPORT_ID, state.intf.id, buf, buf.size, 500
            )
            state.connection.releaseInterface(state.intf)
            // Highest offset now read is 25 (Square) - need at least 26 bytes
            // for buf[25] to be real data, not an unwritten zero.
            if (result > 25) {
                for (off in allOffsets) samples.getValue(off).add(buf[off].toInt() and 0xFF)
            }
            Thread.sleep(AUTH_CHECK_SAMPLE_INTERVAL_MS)
        }

        val result = analyzeAuthCheck(samples)
        // Persisted on the DeviceState so it survives an Activity
        // rebind/reopen without re-running the check - buildDeviceCards()
        // reads it back into authLine every refresh.
        state.authCheckResult = result
        mainHandler.post { listener?.onAuthCheckDone(deviceId, result) }
    }

    // Continuous 1s rumble, motors alternating (each output report is a full
    // state snapshot, so switching straight from one motor's report to the
    // other's turns the first off and the second on in the same command -
    // no gap). Always ends on an explicit off - right/left duration in the
    // report is 0xff ("forever") so skipping it would leave it buzzing.
    private val RUMBLE_SEGMENT_MS = 125L
    private val RUMBLE_SEGMENTS = 8  // 8 * 125ms = 1000ms

    fun testRumble(deviceId: Int) {
        val state = devices[deviceId]
        if (state == null) {
            Log.w("Ds3Charger", "testRumble: no tracked device for id=$deviceId")
            return
        }
        bgPost {
            val rightOnly = SIXAXIS_OUTPUT_DEFAULT.copyOf().apply { this[3] = 1 }
            val leftOnly = SIXAXIS_OUTPUT_DEFAULT.copyOf().apply { this[5] = RUMBLE_TEST_FORCE }
            for (i in 0 until RUMBLE_SEGMENTS) {
                sendOutputReport(state, if (i % 2 == 0) rightOnly else leftOnly)
                Thread.sleep(RUMBLE_SEGMENT_MS)
            }
            sendOutputReport(state, SIXAXIS_OUTPUT_DEFAULT)
        }
    }

    // Writes the DS3's stored Bluetooth "master" address - the host it will
    // try to reconnect to wirelessly. Real DS3 pairing is USB-write-driven,
    // not a self-contained discoverable Bluetooth mode - this is exactly
    // what a real PS3 does automatically on first USB connect, and what
    // PC pairing tools (SixaxisPairTool etc) do manually. Sourced from
    // Android's own historical bluez sixpair.c (set_master_bdaddr):
    // SET_REPORT, feature report 0xF5, 8-byte message
    // [0x01, 0x00, mac0, mac1, mac2, mac3, mac4, mac5] - mac in NATURAL
    // order here, unlike the 0xF2 read path (extractMacFromF2, since
    // removed) which was byte-reversed - these are two independently
    // defined report layouts, not required to share byte order.
    fun pairToHost(deviceId: Int, hostMac: String) {
        val state = devices[deviceId]
        if (state == null) {
            Log.w("Ds3Charger", "pairToHost: no tracked device for id=$deviceId")
            return
        }
        val macBytes = try {
            hostMac.split(":").map { it.toInt(16).toByte() }
        } catch (e: Exception) { null }
        if (macBytes == null || macBytes.size != 6) {
            Log.w("Ds3Charger", "pairToHost: bad MAC format: $hostMac")
            return
        }
        val msg = ByteArray(8)
        msg[0] = 0x01
        msg[1] = 0x00
        for (i in 0..5) msg[2 + i] = macBytes[i]
        bgPost {
            state.connection.claimInterface(state.intf, true)
            val result = state.connection.controlTransfer(0x21, 0x09, 0x03f5, state.intf.id, msg, msg.size, 5000)
            state.connection.releaseInterface(state.intf)
            Log.d("Ds3Charger", "pairToHost: mac=$hostMac result=$result")
        }
    }

    // Real kernel quirk (hid-sony.c): some DS3-compatible boards (flagged
    // SHANWAN_GAMEPAD in the driver) silently accept the SET_REPORT control
    // transfer for output reports but never actually act on it - they need
    // the report sent to the device's own interrupt OUT endpoint instead.
    // The DS3's descriptor exposes one (endpoint 0x02, per the HID Report
    // Descriptor fetched from eleccelerator.com/wiki DualShock_3). Sending
    // both is harmless (redundant duplicate command at worst) and covers
    // whichever path this specific board actually honors.
    private fun findOutputEndpoint(intf: UsbInterface): UsbEndpoint? {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_OUT) return ep
        }
        return null
    }

    private fun sendOutputReport(state: DeviceState, report: ByteArray) {
        state.connection.claimInterface(state.intf, true)
        val ctrlResult = state.connection.controlTransfer(0x21, 0x09, 0x0201, state.intf.id, report, report.size, 2000)
        val ep = findOutputEndpoint(state.intf)
        val bulkResult = ep?.let { state.connection.bulkTransfer(it, report, report.size, 2000) }
        state.connection.releaseInterface(state.intf)
        Log.d("Ds3Charger", "sendOutputReport: ctrl=$ctrlResult ep=${ep?.address} bulk=$bulkResult bytes=${report.take(6)}")
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

    // pct == -1 means "actively charging, hardware reports no real percentage" -
    // show that honestly instead of a fake/stale number (see the comment at the
    // battery-byte decode site for why this happens).
    private fun formatBatteryLine(time: String, pct: Int, status: String): String =
        if (pct == -1) "[$time] Charging..." else "[$time] Battery: $pct%  ($status)"

    private fun buildStatusText(): String {
        val snapshot = synchronized(devices) { devices.values.toList() }
        val btSnapshot = synchronized(btDevices) { btDevices.values.toList() }
        if (snapshot.isEmpty() && btSnapshot.isEmpty()) {
            return "No DualShock 3 connected.\nPlug it in (this app will auto-launch), or leave this open and plug it in now."
        }
        val time = timeFmt.format(java.util.Date())
        val usbText = snapshot.map { s ->
            "${s.infoLine}\n${formatBatteryLine(time, s.lastBatteryPct, s.lastStatus)}"
        }
        val btText = btSnapshot.map { s ->
            "${s.name} (Bluetooth)\n[$time] Battery: ${s.lastBatteryPct}%  (wireless - not charging)"
        }
        return (usbText + btText).joinToString("\n\n")
    }

    private fun buildDeviceCards(): List<DeviceCardInfo> {
        val snapshot = synchronized(devices) { devices.values.toList() }
        val btSnapshot = synchronized(btDevices) { btDevices.values.toList() }
        val time = timeFmt.format(java.util.Date())
        val usbCards = snapshot.map { s ->
            val lines = s.infoLine.split("\n")
            DeviceCardInfo(
                deviceId = s.deviceId,
                title = lines.getOrElse(0) { "Sony PLAYSTATION(R)3 Controller" }.removePrefix("Device: "),
                detail = (lines.getOrElse(1) { "" } + "  (USB)").trim(),
                batteryLine = formatBatteryLine(time, s.lastBatteryPct, s.lastStatus),
                authLine = s.authCheckResult?.let { "${it.verdict}\n${it.detail}" },
            )
        }
        val btCards = btSnapshot.map { s ->
            DeviceCardInfo(
                deviceId = null,
                title = s.name,
                detail = "(Bluetooth / wireless)",
                batteryLine = "[$time] Battery: ${s.lastBatteryPct}%  (wireless - not charging)",
                authLine = null,
            )
        }
        return usbCards + btCards
    }

    private fun refreshUi() {
        listener?.onStatusUpdate(buildStatusText())
        listener?.onDevicesUpdate(buildDeviceCards())
        updateNotification()
    }

    private fun updateNotification() {
        val snapshot = synchronized(devices) { devices.values.toList() }
        val btSnapshot = synchronized(btDevices) { btDevices.values.toList() }
        val text = if (snapshot.isEmpty() && btSnapshot.isEmpty()) {
            "No controller connected"
        } else {
            val usbParts = snapshot.map { if (it.lastBatteryPct == -1) it.lastStatus else "${it.lastBatteryPct}% ${it.lastStatus}" }
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
