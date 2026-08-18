package com.ds3charger.app

import android.util.Log

private const val TAG = "Ds3TriggerInjector"

/**
 * Instantiated by Shizuku via reflection in a process it spawns under the ADB shell UID (no-arg
 * constructor required - Shizuku's contract). That UID is the only one on this device confirmed
 * able to open /dev/uinput (same real constraint the sibling 8bitdo-xbox-bridge project already
 * hit and solved this exact way). Only handles L2/R2 analog trigger axes - the DS3's existing
 * digital buttons/sticks already work fine through Android's normal HID input path, and its
 * rumble goes through Ds3ChargerService's own real USB output-report path, not this class.
 */
class TriggerInjectorService : ITriggerInjector.Stub() {

    private external fun nativeOpenUinput(deviceId: Int, name: String): Int
    private external fun nativeCloseUinput(fd: Int)
    private external fun nativeSendTriggers(fd: Int, l2: Int, r2: Int)

    companion object {
        init {
            System.loadLibrary("ds3trigger")
        }
    }

    // deviceId -> uinput fd. Small map, not a fixed-size array - this app doesn't have a
    // pre-established player-slot concept the way GipBridge does, and deviceId values aren't
    // guaranteed small/contiguous.
    private val fds = java.util.Collections.synchronizedMap(mutableMapOf<Int, Int>())

    // Same real killStaleSiblings lesson as the sibling GipBridge project: Shizuku ties this
    // process's lifecycle to a clean unbind from the host app, but an abnormal host exit
    // (force-stop, OOM kill) skips that, orphaning this process. Kill any same-UID leftover
    // :trigger processes before opening a fresh device, so a respawned service doesn't end up
    // racing a zombie sibling for the same /dev/uinput slot.
    private fun killStaleSiblings() {
        val myPid = android.os.Process.myPid()
        runCatching {
            val proc = ProcessBuilder(
                "sh", "-c",
                "for p in \$(pidof com.ds3charger.app:trigger); do " +
                    "[ \"\$p\" != \"$myPid\" ] && kill -9 \"\$p\"; done",
            ).redirectErrorStream(true).start()
            proc.waitFor()
        }.onFailure { Log.e(TAG, "killStaleSiblings failed: ${it.message}") }
    }

    init {
        killStaleSiblings()
    }

    // Name must NOT contain "Virtual" - see ds3_trigger_uinput.c's doc comment for the real
    // reason (RetroArch's Android input driver mislabels it as "SHIELD Virtual Controller").
    override fun openDevice(deviceId: Int, name: String): Boolean {
        if (fds.containsKey(deviceId)) { Log.d(TAG, "openDevice: deviceId=$deviceId already open, reusing"); return true }
        val fd = runCatching { nativeOpenUinput(deviceId, name) }.getOrElse { -1 }
        if (fd >= 0) fds[deviceId] = fd
        Log.d(TAG, "trigger uinput device for deviceId=$deviceId ('$name') fd=$fd")
        return fd >= 0
    }

    override fun closeDevice(deviceId: Int) {
        val fd = fds.remove(deviceId) ?: return
        nativeCloseUinput(fd)
    }

    override fun sendTriggers(deviceId: Int, l2: Int, r2: Int) {
        val fd = fds[deviceId] ?: return
        nativeSendTriggers(fd, l2.coerceIn(0, 255), r2.coerceIn(0, 255))
    }

    override fun destroy() {
        synchronized(fds) {
            for (fd in fds.values) nativeCloseUinput(fd)
            fds.clear()
        }
    }
}
