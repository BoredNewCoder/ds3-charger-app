package com.ds3charger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts the charger service right at boot, so a DS3 that's already
 * plugged in when the Shield powers on gets picked up automatically.
 * USB_DEVICE_ATTACHED only fires for a NEW plug-in event, never for a
 * device that was already attached before boot - without this receiver,
 * that case only got caught if the user happened to open the app
 * manually. Ds3ChargerService.onCreate() already scans currently
 * attached devices, so starting it here is all that's needed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val svcIntent = Intent(context, Ds3ChargerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }
}
