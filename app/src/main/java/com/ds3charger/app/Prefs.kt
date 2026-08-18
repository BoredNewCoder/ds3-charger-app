package com.ds3charger.app

import android.content.Context

/** Shared SharedPreferences accessors - SettingsActivity writes, Ds3ChargerService reads. */
object Prefs {
    private const val FILE = "ds3_charger_prefs"
    private const val KEY_POLL_INTERVAL_MIN = "poll_interval_min"
    private const val KEY_CHARGE_ALERTS_ENABLED = "charge_alerts_enabled"
    private const val KEY_LOW_BATTERY_ALERTS_ENABLED = "low_battery_alerts_enabled"
    private const val KEY_ANALOG_TRIGGERS_ENABLED = "analog_triggers_enabled"
    const val DEFAULT_POLL_INTERVAL_MIN = 15

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getPollIntervalMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_POLL_INTERVAL_MIN, DEFAULT_POLL_INTERVAL_MIN)

    fun setPollIntervalMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_POLL_INTERVAL_MIN, minutes).apply()
    }

    // Default true - most people want to know their controller's done
    // charging without having to check the app.
    fun isChargeAlertsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CHARGE_ALERTS_ENABLED, true)

    fun setChargeAlertsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CHARGE_ALERTS_ENABLED, enabled).apply()
    }

    fun isLowBatteryAlertsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOW_BATTERY_ALERTS_ENABLED, true)

    fun setLowBatteryAlertsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOW_BATTERY_ALERTS_ENABLED, enabled).apply()
    }

    // Default false, unlike the alert toggles above - this starts a continuous ~20Hz USB poll
    // per connected controller while enabled (real battery/CPU cost), so it shouldn't be
    // forced on for users who don't care about analog L2/R2 triggers. Also needs Shizuku
    // installed+granted to do anything at all - see Ds3ChargerService.setupShizuku().
    fun isAnalogTriggersEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ANALOG_TRIGGERS_ENABLED, false)

    fun setAnalogTriggersEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ANALOG_TRIGGERS_ENABLED, enabled).apply()
    }
}
