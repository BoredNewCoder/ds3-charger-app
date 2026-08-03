package com.ds3charger.app

import android.content.Context

/** Shared SharedPreferences accessors - SettingsActivity writes, Ds3ChargerService reads. */
object Prefs {
    private const val FILE = "ds3_charger_prefs"
    private const val KEY_POLL_INTERVAL_MIN = "poll_interval_min"
    const val DEFAULT_POLL_INTERVAL_MIN = 15

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getPollIntervalMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_POLL_INTERVAL_MIN, DEFAULT_POLL_INTERVAL_MIN)

    fun setPollIntervalMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_POLL_INTERVAL_MIN, minutes).apply()
    }
}
