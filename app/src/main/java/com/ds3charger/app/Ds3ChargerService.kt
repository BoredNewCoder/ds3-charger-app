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
import android.os.SystemClock
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

    // Second, independent authenticity signal: the genuine SIXAXIS/DualShock 3's raw USB HID
    // Report Descriptor (standard GET_DESCRIPTOR, type 0x22, read-only - not the SET_REPORT
    // writes elsewhere in this file). Byte-exact, sourced from a real USBPcap capture
    // (docs.nefarius.at/research/SIXAXIS-native-HID-Report-Descriptor/, the firmware-native
    // variant, not the alternate SIXAXIS.SYS driver reinterpretation also shown on that page),
    // verified 148 bytes long. Unlike the reverted 0xF2 "MAC" (see 2026-08-13 project memory -
    // that field is a rewritable Bluetooth pairing target, not a serial, so a genuine re-paired
    // controller could legitimately fail it), a device's report descriptor is firmware-baked and
    // not user-alterable by any known method - no false-positive-from-normal-use risk. VID/PID
    // deliberately NOT used as a signal either: real ShanWan DS3 clones report the identical
    // Sony VID/PID (054C:0268), so it would only add false confidence, not real information.
    private val KNOWN_GENUINE_REPORT_DESCRIPTOR = byteArrayOf(
        0x05, 0x01, 0x09, 0x04, 0xA1.toByte(), 0x01, 0xA1.toByte(), 0x02, 0x85.toByte(), 0x01, 0x75, 0x08,
        0x95.toByte(), 0x01, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00, 0x81.toByte(), 0x03, 0x75, 0x01, 0x95.toByte(),
        0x13, 0x15, 0x00, 0x25, 0x01, 0x35, 0x00, 0x45, 0x01, 0x05, 0x09, 0x19,
        0x01, 0x29, 0x13, 0x81.toByte(), 0x02, 0x75, 0x01, 0x95.toByte(), 0x0D, 0x06, 0x00, 0xFF.toByte(),
        0x81.toByte(), 0x03, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00, 0x05, 0x01, 0x09, 0x01, 0xA1.toByte(),
        0x00, 0x75, 0x08, 0x95.toByte(), 0x04, 0x35, 0x00, 0x46, 0xFF.toByte(), 0x00, 0x09, 0x30,
        0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x81.toByte(), 0x02, 0xC0.toByte(), 0x05, 0x01, 0x75,
        0x08, 0x95.toByte(), 0x27, 0x09, 0x01, 0x81.toByte(), 0x02, 0x75, 0x08, 0x95.toByte(), 0x30, 0x09,
        0x01, 0x91.toByte(), 0x02, 0x75, 0x08, 0x95.toByte(), 0x30, 0x09, 0x01, 0xB1.toByte(), 0x02, 0xC0.toByte(),
        0xA1.toByte(), 0x02, 0x85.toByte(), 0x02, 0x75, 0x08, 0x95.toByte(), 0x30, 0x09, 0x01, 0xB1.toByte(), 0x02,
        0xC0.toByte(), 0xA1.toByte(), 0x02, 0x85.toByte(), 0xEE.toByte(), 0x75, 0x08, 0x95.toByte(), 0x30, 0x09, 0x01, 0xB1.toByte(),
        0x02, 0xC0.toByte(), 0xA1.toByte(), 0x02, 0x85.toByte(), 0xEF.toByte(), 0x75, 0x08, 0x95.toByte(), 0x30, 0x09, 0x01,
        0xB1.toByte(), 0x02, 0xC0.toByte(), 0xC0.toByte(),
    )
    private val HID_GET_DESCRIPTOR_BM_REQUEST_TYPE = 0x81  // IN, standard, interface
    private val HID_GET_DESCRIPTOR_B_REQUEST = 0x06  // GET_DESCRIPTOR
    private val HID_REPORT_DESCRIPTOR_W_VALUE = 0x2200  // type 0x22 (Report) << 8 | index 0
    private val HID_DESCRIPTOR_READ_BUFFER_SIZE = 256  // generous - actual length comes from the transfer's real return value, not this

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
        // Kept so a stalled-precharge full reconnect (see attemptFullPrechargeRetry below) can
        // re-resolve + re-open the physical device later, the same way retryChargeCommand does
        // for an initial connect failure - a captured UsbDevice's internal path reference can go
        // stale if the kernel re-enumerates the bus path in between, so always re-resolve by
        // deviceId/VID/PID at retry time rather than trusting this field directly for openDevice().
        var device: UsbDevice,
        var lastBatteryPct: Int = -1,
        var lastStatus: String = "",
        var consecutivePollFailures: Int = 0,
        // elapsedRealtime() of the first "Charging" (0xEE) reading seen since
        // this device attached; 0 = never seen charging yet. Used to hold off
        // trusting an early "full" byte - see FULL_CONFIRM_POLLS below.
        var chargingSinceMs: Long = 0L,
        // Consecutive polls byte 30 has reported "full" (0xEF). Debounces a
        // transient/early full reading before it's relayed as 100% / "Full".
        var fullReadingStreak: Int = 0,
        // elapsedRealtime() of the last attemptFullPrechargeRetry() call - throttles retries
        // (see FULL_PRECHARGE_RETRY_INTERVAL_MS) so this doesn't hammer a genuinely-full or
        // genuinely-fine controller every single poll.
        var lastFullReconnectAttemptMs: Long = 0L,
        // Bounded so a controller that's genuinely already full on connect (chargingSinceMs
        // legitimately stays 0 forever in that case too - see the "done" branch's own comment)
        // doesn't get reconnect-spammed forever. Reset to 0 the moment a real 0xEE ("actively
        // charging") reading is ever seen - see MAX_PRECHARGE_RETRIES's doc comment.
        var prechargeRetryCount: Int = 0,
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
    //
    // REVERTED 1_000 -> 30_000 (2026-09-11, same-night follow-up). The 2026-09-11 tightening
    // above (kept in git history, see commit 9d384fa) reasoned that a shorter poll gap would
    // reduce USB idle-suspend risk during a real charge - but pollBattery() does a FORCE
    // claimInterface -> read -> releaseInterface on every single poll (see pollBattery's own
    // comment: deliberate, so the interface isn't held exclusively and starved from other
    // consumers - real regression 2026-07-13 from an earlier version holding it continuously).
    // At the tightened 1s interval that force-claim/release cycle - which itself briefly steals
    // the interface from whatever else has it - was firing 30x more often than before. Live-
    // observed immediately after installing that version: 0.20A real charging current (external
    // ammeter) dropped to 0A after ~5 seconds - roughly 5 poll cycles at the new 1Hz rate. That
    // timing is far too short to be the battery finishing a real charge, and lines up with
    // "the poll's own claim/release cycle is disrupting the DS3's operational/charge-enabled
    // state" much better than with "USB autosuspend snuck in during a 30s gap" ever did. The
    // 1s tightening's own justification was explicitly speculative ("consistent with, but not
    // proven") - reverting rather than compounding one unproven theory on top of another.
    // Net: this app cannot fix the charging problem from software (established earlier the same
    // night: no write path to the device's charge circuit, no Android API to pin USB power
    // state without root) - the real fix is a USB hub upstream of the Shield's own weak ports,
    // full stop. Polling interval doesn't change whether current flows; keeping it at the
    // original, long-tested value is the safer default until/unless real evidence says otherwise.
    private val FAST_POLL_INTERVAL_MS = 30_000L

    // "Fully charged" gate. The DS3's charge controller (and clone boards
    // especially - this app targets a Shanwan clone) flips byte 30's low bit
    // to "done" (0xEF) noticeably before the pack is actually topped off: a
    // 2026-08-14 bench test read "100% / Full" on the Shield but only "High"
    // on a PC's battery tool seconds later, with no time to have drained. So
    // a bare 0xEF is not trusted on its own - it must (a) hold across this
    // many consecutive polls and (b) come after the controller has been
    // charging for at least MIN_CHARGE_BEFORE_FULL_MS. Until both clear, the
    // status stays "Charging (topping off)" rather than "Full". A controller
    // that already read full on its first poll (never charged, so
    // chargingSinceMs stays 0) skips the time gate but still needs the streak.
    private val FULL_CONFIRM_POLLS = 3
    private val MIN_CHARGE_BEFORE_FULL_MS = 25 * 60 * 1000L

    // Real, sourced finding (2026-09-11 live investigation): the DS3 uses a TI bqTINY-II charge
    // management IC. Per its documented behavior, a too-deeply-discharged cell gets a
    // precharge/trickle current under an internal safety timer; if the cell's voltage doesn't
    // cross a threshold within that window, the IC stops and asserts FAULT on its status pins -
    // needing a fresh power cycle to retry. Live-confirmed this matches what's actually happening
    // here: two consecutive polls 30s apart both read the SAME "done" byte (0xf1) spanning the
    // exact window an external ammeter showed real trickle current (0.03-0.20A) drop to 0 - the
    // byte never moves, so it can't be a live completion signal; it's almost certainly reporting
    // that FAULT state, not a genuine 100% charge, especially given it reads "done" within
    // seconds of every fresh connect (nowhere near enough time to actually finish charging a
    // dead cell). Also live-confirmed: every FRESH physical USB attach tonight produced a new
    // burst of real current - consistent with a fresh attach clearing the prior FAULT and
    // starting a new precharge attempt. No live signal exists to know exactly when the FAULT
    // fires (byte 30 doesn't change), so this can't be event-triggered - instead,
    // attemptFullPrechargeRetry() below periodically forces a full close+reopen+re-handshake
    // (the software equivalent of a real unplug/replug) while a device has been stuck reporting
    // "done" since before it ever showed a genuine chargingSinceMs (i.e. never confirmed actively
    // charging in the first place) - giving a deeply-discharged cell repeated fresh precharge
    // attempts instead of the one attempt a passive connection gets.
    private val FULL_PRECHARGE_RETRY_INTERVAL_MS = 15_000L

    // Caps attemptFullPrechargeRetry() at 20 attempts (20 * 15s = ~5 minutes of real retrying)
    // before giving up and letting the normal "done" logic just report Full/topping-off as
    // usual - without this, a controller that's genuinely already full the moment it's plugged
    // in (chargingSinceMs legitimately stays 0 in that case too, same as the stuck-FAULT case -
    // the two are indistinguishable from byte 30 alone) would get reconnect-spammed forever.
    // 5 minutes is generous enough to actually nurse a deeply-discharged cell past the bqTINY-II's
    // precharge threshold if repeated fresh attempts can do that at all, without churning
    // uselessly on a healthy controller past a reasonable point.
    private val MAX_PRECHARGE_RETRIES = 20

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
                anyCharging = snapshot.any { it.lastStatus == "Charging" || it.lastStatus == "Charging (topping off)" }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // See MainActivity's onCreate comment: Android launches the activity directly (not a
        // broadcast) when this app is the registered default handler for the DS3's
        // device_filter, so that's the only reliable place a live attach event's UsbDevice
        // ever reaches this app -- forwarded here via the service intent's extra.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            device?.let { checkAndRequestDevice(it) }
        }
        return START_STICKY
    }

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
        bgPost { sendChargeCommandAttempt(device, attempt = 1, reservationId = device.deviceId) }
    }

    // reservationId is the deviceId checkAndRequestDevice originally reserved in
    // pendingDeviceIds - kept separate from `device.deviceId` because a retry may pass
    // in a freshly re-resolved UsbDevice (see retryChargeCommand) whose own deviceId can
    // differ from the original if the kernel re-enumerated the physical device under a
    // new bus path in between attempts. Always clear the RESERVATION id, not whatever
    // the current device object happens to report.
    // Steps 2+3 of sixaxis_set_operational_usb() (0xF5 GET_REPORT + interrupt-OUT kick) plus the
    // post-kick verification read. Extracted 2026-09-11 so pollBattery's re-arm path (see its
    // `else` branch below) can replay exactly this sequence on an already-open, already-tracked
    // connection - not just at initial connect time. Real cause found+confirmed live this same
    // night: `com.nvidia.bluetooth.ps3usbpairer`, an NVIDIA Shield SYSTEM app, has its own
    // registered `android.hardware.usb.action.USB_DEVICE_ATTACHED` receiver (confirmed via
    // `dumpsys package com.nvidia.bluetooth.ps3usbpairer` - not stale, not theoretical) that
    // fires on the SAME broadcast this app's own usbReceiver listens for, and independently
    // claims/releases the SAME USB interface to attempt its own Bluetooth-pairing flow. This was
    // already flagged as "real contributing churn, not just theoretical" back on 2026-08-18
    // (see retryChargeCommand's doc comment) for connect-time failures; this re-arm path is the
    // same fix applied to a controller that already connected successfully and later got
    // knocked back out of operational mode by that same competing claim. Interface must already
    // be claimed by the caller - this function does NOT claim/release it, so it composes inside
    // either sendChargeCommandAttempt's or pollBattery's own claim/release bracket.
    private fun sendOperationalKickAndVerify(connection: UsbDeviceConnection, intf: UsbInterface): Boolean {
        // Kernel comment: "some compatible controllers... need another query plus a USB
        // interrupt to get operational." Real-world consequence found 2026-08-13: a controller
        // could read HID input reports fine (so the app showed a plausible battery status)
        // while charging never actually engaged, because the controller was never fully brought
        // into operational mode. Non-fatal if this GET_REPORT fails (most controllers don't
        // strictly need it) - logged, doesn't block the rest of the sequence.
        val buf2 = ByteArray(8)
        val result2 = connection.controlTransfer(0xA1, 0x01, 0x03F5, intf.id, buf2, buf2.size, 5000)
        if (result2 < 0) {
            Log.w("Ds3Charger", "operational step 2 (0xF5) failed, result=$result2 - continuing anyway")
        } else {
            Log.d("Ds3Charger", "operational step 2 (0xF5) OK, result=$result2 bytes=${buf2.take(8)}")
        }

        // "another query plus a USB interrupt" - this is the interrupt-OUT write hid-sony.c
        // flags as required for SHANWAN/compatible (clone) boards to actually go operational -
        // without it a clone reads input reports fine but its charge circuit never engages.
        val opKickEp = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        if (opKickEp != null) {
            val kickResult = connection.bulkTransfer(opKickEp, ByteArray(1), 1, 2000)
            Log.d("Ds3Charger", "operational step 3 (interrupt-OUT kick) result=$kickResult")
        } else {
            Log.w("Ds3Charger", "operational step 3 skipped - no interrupt-OUT endpoint on interface")
        }

        // Confirm the handshake actually engaged the charge circuit. Give the controller a
        // moment, then read the battery byte - while USB-connected it should report
        // charging/full (>=0xEE), not a 0-5 "on battery" index.
        Thread.sleep(500)
        val verifyBuf = ByteArray(INPUT_REPORT_SIZE)
        val verifyResult = connection.controlTransfer(
            0xA1, 0x01, (0x01 shl 8) or INPUT_REPORT_ID, intf.id, verifyBuf, verifyBuf.size, 2000
        )
        return verifyResult > BATTERY_BYTE_OFFSET &&
            (verifyBuf[BATTERY_BYTE_OFFSET].toInt() and 0xFF) >= 0xEE
    }

    private fun sendChargeCommandAttempt(device: UsbDevice, attempt: Int, reservationId: Int) {
        if (devices.containsKey(device.deviceId)) return  // already tracked by an earlier attempt

        val connection: UsbDeviceConnection? = usbManager.openDevice(device)
        if (connection == null) {
            retryChargeCommand(device, attempt, reservationId, "openDevice failed")
            return
        }
        val intf = device.getInterface(0)
        // Soft-claim first, force only if that fails: force=true kernel-detaches whatever HID driver
        // currently owns the interface, and Android has no API to re-attach it afterwards. For a real
        // DS3 the OS gamepad driver is holding it, so this still force-claims in practice - but if the
        // interface is genuinely free (no driver bound) we don't needlessly evict anything.
        if (!connection.claimInterface(intf, false) && !connection.claimInterface(intf, true)) {
            connection.close()
            retryChargeCommand(device, attempt, reservationId, "claimInterface failed")
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
            retryChargeCommand(device, attempt, reservationId, "controlTransfer failed (result=$result)")
            return
        }

        // Steps 2+3 of sixaxis_set_operational_usb() + the confirm-read - see
        // sendOperationalKickAndVerify's doc comment for the full detail and why it's factored
        // out (pollBattery's re-arm path below replays the same sequence).
        val chargingConfirmed = sendOperationalKickAndVerify(connection, intf)
        if (!chargingConfirmed && attempt < MAX_CHARGE_COMMAND_ATTEMPTS) {
            connection.releaseInterface(intf)
            connection.close()
            retryChargeCommand(device, attempt, reservationId,
                "operational sequence sent but controller still reports on-battery")
            return
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
        val state = DeviceState(connection, intf, infoLine, device.deviceId, device)
        // Charging couldn't be confirmed after every attempt - say so honestly instead of letting the
        // first pollBattery paint a normal-looking status over a controller that isn't charging.
        if (!chargingConfirmed) state.lastStatus = "USB connected - charging not confirmed"
        synchronized(devices) {
            devices[device.deviceId] = state
            pendingDeviceIds.remove(reservationId)
        }
        refreshUi()

        // REAL BUG found+fixed 2026-09-11 (live investigation, not guessed): pollRunnable is a
        // single shared timer loop started once in onCreate() and rescheduled only from its own
        // `finally` block - a freshly-connected device does NOT reset or trigger it. Its interval
        // defaults to Prefs.DEFAULT_POLL_INTERVAL_MIN = **15 minutes** (FAST_POLL_INTERVAL_MS only
        // ever applies once anyCharging is already true from a PRIOR tick's result - a bootstrap
        // gap, this device has never been polled yet). Confirmed live: a controller was observed
        // dropping current within seconds of connecting, but zero pollBattery calls (and so zero
        // chances for the re-arm logic above to ever run) had fired in over a minute since attach -
        // the global loop simply hadn't ticked yet. This app could sit blind to a fast charge-drop
        // for up to 15 real minutes on a fresh connect, by design, without anyone noticing. Fix:
        // poll THIS device immediately (seeds lastStatus/chargingSinceMs right away instead of
        // leaving them at their just-constructed defaults) and bump the shared loop's next tick up
        // to FAST_POLL_INTERVAL_MS regardless of what was left on its previous countdown, so every
        // controller gets watched closely for the first stretch after it connects, not just once
        // it happens to already be mid-"Charging" from an earlier successful poll.
        try {
            pollBattery(state)
        } catch (e: Throwable) {
            Log.e("Ds3Charger", "immediate post-connect pollBattery failed for deviceId=${device.deviceId}: ${e.message}", e)
        }
        bgHandler.removeCallbacks(pollRunnable)
        bgHandler.postDelayed(pollRunnable, FAST_POLL_INTERVAL_MS)
        refreshUi()
    }

    // A transient claim/transfer failure (device still enumerating, briefly
    // busy right after plug-in, etc) used to permanently drop the device
    // until physical replug - USB_DEVICE_ATTACHED only fires once per plug
    // event. Retry a few times with a short delay before giving up.
    //
    // Real bug found+fixed 2026-08-18 (live logcat, not guessed): this used to reuse the
    // SAME UsbDevice object on every retry - captured once, back at the original
    // ACTION_USB_DEVICE_ATTACHED event. Confirmed live: usbManager.openDevice() throws
    // `IllegalArgumentException: device /dev/bus/usb/NNN/MMM does not exist or is
    // restricted` on every single retry when the kernel has re-enumerated the physical
    // device under a different bus path in between attempts (observed correlating with
    // com.nvidia.bluetooth.ps3usbpairer, an NVIDIA Shield system app, also
    // claiming+releasing the same USB interface on every attach - real contributing
    // churn, not just theoretical). A stale UsbDevice's internal path reference never
    // becomes valid again, so every retry using it was guaranteed to fail identically no
    // matter how many attempts were budgeted - explains why some attaches worked (no
    // unlucky re-enumeration that time) and others silently died every time. Fixed by
    // re-resolving the CURRENT live UsbDevice from usbManager.deviceList on every retry
    // instead of trusting the captured reference.
    private fun retryChargeCommand(device: UsbDevice, attempt: Int, reservationId: Int, reason: String) {
        if (attempt >= MAX_CHARGE_COMMAND_ATTEMPTS) {
            Log.w("Ds3Charger", "charge attempt $attempt/$MAX_CHARGE_COMMAND_ATTEMPTS (reservationId=$reservationId) failed: $reason - giving up")
            synchronized(devices) { pendingDeviceIds.remove(reservationId) }
            return
        }
        Log.w("Ds3Charger", "charge attempt $attempt/$MAX_CHARGE_COMMAND_ATTEMPTS (reservationId=$reservationId) failed: $reason - retrying")
        // Linear backoff (500ms, 1000ms, 1500ms...) - some hubs are slower
        // to finish enumerating than a flat delay accounts for.
        bgPostDelayed(CHARGE_COMMAND_RETRY_BASE_DELAY_MS * attempt) {
            val fresh = resolveCurrentDevice(device.deviceId, device.vendorId, device.productId)
            if (fresh == null) {
                Log.w("Ds3Charger", "charge retry (reservationId=$reservationId): device no longer present, giving up")
                synchronized(devices) { pendingDeviceIds.remove(reservationId) }
                return@bgPostDelayed
            }
            sendChargeCommandAttempt(fresh, attempt + 1, reservationId)
        }
    }

    // Re-resolves a live UsbDevice from the CURRENT device list rather than trusting a
    // UsbDevice object captured at some earlier point in time - see retryChargeCommand's
    // comment for why a stale reference guarantees repeated openDevice() failures. Tries
    // the original deviceId first (the common case, id stable across the retry), falls
    // back to a VID/PID match (covers real re-enumeration where the id itself changed).
    // Returns null only when the physical device is genuinely gone.
    private fun resolveCurrentDevice(originalDeviceId: Int, vendorId: Int, productId: Int): UsbDevice? {
        val current = usbManager.deviceList.values
        return current.firstOrNull { it.deviceId == originalDeviceId }
            ?: current.firstOrNull { it.vendorId == vendorId && it.productId == productId }
    }

    // Forces a REAL full reconnect - close the connection, re-resolve the live UsbDevice (its
    // bus path can change across a close, same reasoning as retryChargeCommand), re-open, re-run
    // the FULL operational handshake from scratch (step 1's 0xF2 GET_REPORT +
    // sendOperationalKickAndVerify's steps 2+3+verify) - not just the lightweight claim/read/
    // release pollBattery does every tick. See FULL_PRECHARGE_RETRY_INTERVAL_MS's doc comment
    // for why: this is the software equivalent of a real unplug/replug, which is the one thing
    // that's repeatedly, empirically correlated with a fresh burst of real charging current
    // tonight - the DS3's own charge IC (bqTINY-II) needs a real power-cycle to clear a prior
    // precharge-safety-timeout FAULT and start a new attempt. On success, swaps the live
    // connection/interface into `state` so every caller (including the NEXT poll) uses the
    // fresh one; the old connection is always closed either way, never leaked.
    private fun attemptFullPrechargeRetry(state: DeviceState): Boolean {
        Log.i("Ds3Charger", "deviceId=${state.deviceId} attempting full precharge retry (close+reopen+re-handshake)")
        val oldConnection = state.connection
        try { oldConnection.close() } catch (e: Exception) { /* already dead, fine */ }

        val fresh = resolveCurrentDevice(state.deviceId, state.device.vendorId, state.device.productId)
        if (fresh == null) {
            Log.w("Ds3Charger", "deviceId=${state.deviceId} precharge retry: device no longer present")
            return false
        }
        val newConnection = usbManager.openDevice(fresh)
        if (newConnection == null) {
            Log.w("Ds3Charger", "deviceId=${state.deviceId} precharge retry: openDevice failed")
            return false
        }
        val newIntf = fresh.getInterface(0)
        if (!newConnection.claimInterface(newIntf, false) && !newConnection.claimInterface(newIntf, true)) {
            newConnection.close()
            Log.w("Ds3Charger", "deviceId=${state.deviceId} precharge retry: claimInterface failed")
            return false
        }
        // Step 1 of sixaxis_set_operational_usb() - same 0xF2 GET_REPORT sendChargeCommandAttempt
        // uses on a genuinely fresh connect.
        val buf1 = ByteArray(17)
        val step1Result = newConnection.controlTransfer(0xA1, 0x01, 0x03F2, newIntf.id, buf1, buf1.size, 5000)
        if (step1Result < 0) {
            newConnection.releaseInterface(newIntf)
            newConnection.close()
            Log.w("Ds3Charger", "deviceId=${state.deviceId} precharge retry: step 1 (0xF2) failed, result=$step1Result")
            return false
        }
        val reconnected = sendOperationalKickAndVerify(newConnection, newIntf)
        newConnection.releaseInterface(newIntf)

        // Adopt the new connection/device regardless of whether reconnected==true (a fresh
        // connection is still strictly better than the closed one it's replacing) - only bail
        // out to a fully-dead state if openDevice/claimInterface/step1 above already returned.
        state.connection = newConnection
        state.intf = newIntf
        state.device = fresh
        // "reconnected" only means the handshake got a >=0xEE reply post-kick - 0xF1 (stuck/fault)
        // satisfies that too, so this is NOT proof the retry produced a real charge. Only a
        // later raw==0xEE poll (low bit clear) is real confirmation.
        Log.i("Ds3Charger", "deviceId=${state.deviceId} precharge retry ${if (reconnected) "back in operational mode" else "reconnected but handshake verify failed"}")
        return reconnected
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
        Log.d("Ds3Charger", "deviceId=${state.deviceId} battery byte 30 = 0x%02x".format(raw))
        // Real hardware limit, not a parsing gap: while actively charging the DS3's own
        // charge controller owns this byte and only ever reports "still charging" vs
        // "full" - no live percentage is transmitted (confirmed against both the Linux
        // hid-sony.c driver and DsHidMini's DS3_RAW_INPUT_REPORT struct). -1 marks "no
        // real percentage available" so the UI can show "Charging..." honestly instead
        // of a fake number, rather than reusing the unrelated Full=100 value.
        val (pct, status) = when {
            raw >= 0xee -> {
                if (raw and 0x01 != 1) {
                    // Low bit clear (0xEE) = still charging. No live % on the wire. A real
                    // active-charging confirmation - the precharge-FAULT retry loop below is
                    // done, this device is genuinely charging normally now.
                    if (state.chargingSinceMs == 0L) state.chargingSinceMs = SystemClock.elapsedRealtime()
                    state.fullReadingStreak = 0
                    state.prechargeRetryCount = 0
                    -1 to "Charging"
                } else {
                    // Low bit set (0xEF/0xF1) = "done charging" per the naive kernel-driver
                    // interpretation - but see FULL_PRECHARGE_RETRY_INTERVAL_MS's doc comment:
                    // live-confirmed this reads far too early to be a genuine full charge on a
                    // deeply-discharged cell, and is very likely actually the bqTINY-II charge
                    // IC's FAULT/safety-timeout state.
                    //
                    // Don't relay a bare 0xEF/0xF1 as Full until it has held for
                    // FULL_CONFIRM_POLLS in a row AND the controller has been charging at least
                    // MIN_CHARGE_BEFORE_FULL_MS - see those constants for why an early 0xEF on
                    // this hardware lies.
                    state.fullReadingStreak++
                    val chargedLongEnough = state.chargingSinceMs == 0L ||
                        SystemClock.elapsedRealtime() - state.chargingSinceMs >= MIN_CHARGE_BEFORE_FULL_MS
                    val confirmedFull = state.fullReadingStreak >= FULL_CONFIRM_POLLS && chargedLongEnough

                    // Live-confirmed 2026-09-11: one real 0xEE reading (setting chargingSinceMs)
                    // used to permanently disable this retry, on the theory that a real charge
                    // had started and would finish on its own. Real log showed that's false - the
                    // reading can revert to stuck 0xF1 seconds later, and neither this retry nor
                    // the re-arm branch below could fire again (re-arm needs raw<0xEE, which
                    // never happened - it went straight from 0xEE back to 0xF1). So retry any
                    // time we're NOT yet confirmed Full, not just before the first 0xEE - bounded
                    // by MAX_PRECHARGE_RETRIES so a controller that's genuinely full doesn't get
                    // reconnect-spammed forever once confirmedFull is true.
                    if (!confirmedFull && state.prechargeRetryCount < MAX_PRECHARGE_RETRIES) {
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - state.lastFullReconnectAttemptMs >= FULL_PRECHARGE_RETRY_INTERVAL_MS) {
                            state.lastFullReconnectAttemptMs = nowMs
                            state.prechargeRetryCount++
                            Log.w("Ds3Charger", "deviceId=${state.deviceId} stuck reporting 'done' (raw=0x%02x) - precharge retry ${state.prechargeRetryCount}/$MAX_PRECHARGE_RETRIES".format(raw))
                            attemptFullPrechargeRetry(state)
                        }
                    }

                    if (confirmedFull) {
                        100 to "Full"
                    } else {
                        -1 to "Charging (topping off)"
                    }
                }
            }
            else -> {
                // Re-arm: this controller previously read >=0xEE (a real USB-charging/full
                // report) and has now dropped back to an on-battery index while STILL
                // USB-tracked - a genuine unplug removes it from `devices` entirely via
                // ACTION_USB_DEVICE_DETACHED (see usbReceiver above), so this can only mean
                // something knocked it OUT of operational mode while the physical connection
                // stayed up. See sendOperationalKickAndVerify's doc comment for the confirmed
                // real cause (com.nvidia.bluetooth.ps3usbpairer's own USB_DEVICE_ATTACHED
                // receiver competing for the same interface). Re-send just the kick+verify half
                // of the handshake - cheap, and doesn't need a full reconnect/reopen.
                val wasOperational = state.chargingSinceMs != 0L ||
                    state.lastStatus == "Charging" || state.lastStatus == "Charging (topping off)" || state.lastStatus == "Full"
                state.chargingSinceMs = 0L
                state.fullReadingStreak = 0
                if (wasOperational) {
                    Log.w("Ds3Charger", "deviceId=${state.deviceId} dropped out of operational mode while USB-tracked (raw=0x%02x) - re-arming".format(raw))
                    state.connection.claimInterface(state.intf, true)
                    val reArmed = try {
                        sendOperationalKickAndVerify(state.connection, state.intf)
                    } finally {
                        state.connection.releaseInterface(state.intf)
                    }
                    Log.i("Ds3Charger", "deviceId=${state.deviceId} re-arm ${if (reArmed) "succeeded" else "failed"}")
                }
                SIXAXIS_BATTERY_CAPACITY[minOf(raw, 5)] to "On battery (not charging)"
            }
        }
        // Fire the charge-complete alert on the Charging->Full edge only -
        // that's the one real transition the hardware exposes while plugged
        // in (see the class-level comment: no live % while charging, just
        // Charging vs Full). Guarded on the previous status specifically
        // (not "status changed") so a fresh poll of an already-Full
        // controller, or Full->unplugged, never fires one.
        if (status == "Full" &&
            (state.lastStatus == "Charging" || state.lastStatus == "Charging (topping off)") &&
            Prefs.isChargeAlertsEnabled(this)
        ) {
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

        val adcResult = analyzeAuthCheck(samples)
        val descriptorStatus = readReportDescriptorStatus(state)
        val result = combineAuthResults(adcResult, descriptorStatus)
        // Persisted on the DeviceState so it survives an Activity
        // rebind/reopen without re-running the check - buildDeviceCards()
        // reads it back into authLine every refresh.
        state.authCheckResult = result
        mainHandler.post { listener?.onAuthCheckDone(deviceId, result) }
    }

    // Single read-only GET_DESCRIPTOR control transfer (not part of the ADC sampling loop
    // above - this doesn't need repeated samples, the descriptor doesn't change).
    // Returns null if the read fails or the length is implausible (0 bytes, or way over the
    // genuine 148) - treated as "unavailable", never as a clone verdict, so a controller that
    // doesn't answer this cleanly (or a transient USB hiccup) can't produce a false "fake"
    // result the way the reverted MAC check once did.
    private fun readReportDescriptorStatus(state: DeviceState): Pair<String, String>? {
        return try {
            state.connection.claimInterface(state.intf, true)
            val buf = ByteArray(HID_DESCRIPTOR_READ_BUFFER_SIZE)
            val result = state.connection.controlTransfer(
                HID_GET_DESCRIPTOR_BM_REQUEST_TYPE, HID_GET_DESCRIPTOR_B_REQUEST,
                HID_REPORT_DESCRIPTOR_W_VALUE, state.intf.id, buf, buf.size, 2000
            )
            state.connection.releaseInterface(state.intf)
            if (result <= 0 || result > HID_DESCRIPTOR_READ_BUFFER_SIZE) return null
            val actual = buf.copyOf(result)
            if (actual.contentEquals(KNOWN_GENUINE_REPORT_DESCRIPTOR)) {
                "genuine" to "Report descriptor: byte-exact match to genuine Sony SIXAXIS/DS3 (148 bytes)."
            } else {
                "differs" to "Report descriptor: differs from genuine Sony (${result} bytes vs expected 148, or content mismatch) - possible clone or unusual firmware."
            }
        } catch (e: Exception) {
            Log.w("Ds3Charger", "readReportDescriptorStatus failed: ${e.message}")
            null
        }
    }

    // Combines the ADC-quantization verdict (stick/button smoothness, from analyzeAuthCheck)
    // with the report-descriptor verdict (readReportDescriptorStatus) into one result. Two
    // independent signals agreeing is stronger evidence than either alone; disagreeing is
    // reported honestly as inconclusive rather than picking a winner - same "don't overclaim"
    // discipline as the rest of this app's authenticity-check history.
    private fun combineAuthResults(adc: AuthCheckResult, descriptor: Pair<String, String>?): AuthCheckResult {
        if (descriptor == null) return adc  // unavailable - fall back to ADC-only, current behavior
        val (descStatus, descDetail) = descriptor
        val adcGenuine = adc.verdict.startsWith("Smooth response")
        val adcClone = adc.verdict.startsWith("Stepped response")

        if (!adcGenuine && !adcClone) {
            // ADC was inconclusive (not enough movement) - report the descriptor result on
            // its own rather than trying to combine with a non-verdict.
            val label = if (descStatus == "genuine") "Genuine (report descriptor)" else "Differs from genuine (report descriptor)"
            return AuthCheckResult(label, "$descDetail\n(ADC check inconclusive this run - not enough movement, try again for a second signal.)")
        }

        val descGenuine = descStatus == "genuine"
        return if (adcGenuine == descGenuine) {
            val label = if (adcGenuine) "Genuine (2/2 signals agree)" else "Likely clone (2/2 signals agree)"
            AuthCheckResult(label, "${adc.detail}\n$descDetail")
        } else {
            AuthCheckResult("Mixed signals - inconclusive", "ADC check: ${adc.verdict} - ${adc.detail}\n$descDetail")
        }
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

    // Real fix (2026-08-17): used to skip Bluetooth polling ENTIRELY whenever any USB
    // controller was tracked, on the assumption there'd only ever be one physical DS3 in
    // play. That's wrong for a real multi-controller setup: one DS3 wired + a second,
    // genuinely different DS3 connected wirelessly at the same time would mean the wireless
    // one's battery never got tracked at all. There's no cheap way to prove two same-model
    // DS3s across different transports are/aren't the same physical unit from this app's
    // available APIs (no shared MAC/serial readable from both the USB and Bluetooth-input
    // paths), so this can't be made perfect -- but it can be made strictly better: only
    // suppress as many Bluetooth DS3 entries as there are wired ones (covering the real,
    // already-fixed case of a wired controller's own Bluetooth ghost-reconnect), and track
    // anything beyond that count as a real second controller. Stable ordering (sorted by
    // descriptor) so which specific entry gets suppressed doesn't flap between polls.
    private fun pollBluetoothControllers() {
        val usbCount = devices.size
        val im = getSystemService(Context.INPUT_SERVICE) as InputManager
        val ds3BtDevices = mutableListOf<android.view.InputDevice>()
        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            if (dev.vendorId == SONY_VENDOR_ID && dev.productId == DS3_PRODUCT_ID) ds3BtDevices.add(dev)
        }
        ds3BtDevices.sortBy { it.descriptor }
        val trackedNow: List<android.view.InputDevice> =
            if (usbCount >= ds3BtDevices.size) emptyList() else ds3BtDevices.drop(usbCount)

        val seen = mutableSetOf<String>()
        for (dev in trackedNow) {
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
        if (pct == -1) "[$time] $status..." else "[$time] Battery: $pct%  ($status)"

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
