package com.ds3charger.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * Simple TV-remote-friendly settings screen - preset interval buttons
 * instead of a text field (no on-screen keyboard needed), backed by the
 * same SharedPreferences Ds3ChargerService reads from (see Prefs object).
 * Changes take effect on the SERVICE's next poll cycle, not instantly -
 * it re-reads prefs at the top of every pollRunnable tick rather than
 * caching them, so no restart/rebind is needed for a change to apply.
 */
class SettingsActivity : Activity() {

    private lateinit var intervalStatus: TextView
    private lateinit var chargeAlertsToggle: Button
    private lateinit var chargeAlertsStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        intervalStatus = findViewById(R.id.intervalStatus)
        chargeAlertsToggle = findViewById(R.id.chargeAlertsToggle)
        chargeAlertsStatus = findViewById(R.id.chargeAlertsStatus)

        findViewById<Button>(R.id.interval1).setOnClickListener { setInterval(1) }
        findViewById<Button>(R.id.interval5).setOnClickListener { setInterval(5) }
        findViewById<Button>(R.id.interval15).setOnClickListener { setInterval(15) }
        findViewById<Button>(R.id.interval30).setOnClickListener { setInterval(30) }
        chargeAlertsToggle.setOnClickListener {
            Prefs.setChargeAlertsEnabled(this, !Prefs.isChargeAlertsEnabled(this))
            refreshUi()
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        refreshUi()
    }

    private fun setInterval(minutes: Int) {
        Prefs.setPollIntervalMinutes(this, minutes)
        refreshUi()
    }

    private fun refreshUi() {
        intervalStatus.text = "Currently: every ${Prefs.getPollIntervalMinutes(this)} min"
        val enabled = Prefs.isChargeAlertsEnabled(this)
        chargeAlertsToggle.text = if (enabled) "Turn off" else "Turn on"
        chargeAlertsStatus.text = if (enabled)
            "Currently: on - alerts when a controller finishes charging"
        else
            "Currently: off"
    }
}
