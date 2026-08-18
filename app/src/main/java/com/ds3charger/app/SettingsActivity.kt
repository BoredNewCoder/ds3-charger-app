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
    private lateinit var lowBatteryAlertsToggle: Button
    private lateinit var lowBatteryAlertsStatus: TextView
    private lateinit var analogTriggersToggle: Button
    private lateinit var analogTriggersStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        intervalStatus = findViewById(R.id.intervalStatus)
        chargeAlertsToggle = findViewById(R.id.chargeAlertsToggle)
        chargeAlertsStatus = findViewById(R.id.chargeAlertsStatus)
        lowBatteryAlertsToggle = findViewById(R.id.lowBatteryAlertsToggle)
        lowBatteryAlertsStatus = findViewById(R.id.lowBatteryAlertsStatus)
        analogTriggersToggle = findViewById(R.id.analogTriggersToggle)
        analogTriggersStatus = findViewById(R.id.analogTriggersStatus)

        findViewById<Button>(R.id.interval1).setOnClickListener { setInterval(1) }
        findViewById<Button>(R.id.interval5).setOnClickListener { setInterval(5) }
        findViewById<Button>(R.id.interval15).setOnClickListener { setInterval(15) }
        findViewById<Button>(R.id.interval30).setOnClickListener { setInterval(30) }
        chargeAlertsToggle.setOnClickListener {
            Prefs.setChargeAlertsEnabled(this, !Prefs.isChargeAlertsEnabled(this))
            refreshUi()
        }
        lowBatteryAlertsToggle.setOnClickListener {
            Prefs.setLowBatteryAlertsEnabled(this, !Prefs.isLowBatteryAlertsEnabled(this))
            refreshUi()
        }
        analogTriggersToggle.setOnClickListener {
            Prefs.setAnalogTriggersEnabled(this, !Prefs.isAnalogTriggersEnabled(this))
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

        val lowBattEnabled = Prefs.isLowBatteryAlertsEnabled(this)
        lowBatteryAlertsToggle.text = if (lowBattEnabled) "Turn off" else "Turn on"
        lowBatteryAlertsStatus.text = if (lowBattEnabled)
            "Currently: on - alerts when a controller (on battery) drops to 25%"
        else
            "Currently: off"

        val triggersEnabled = Prefs.isAnalogTriggersEnabled(this)
        analogTriggersToggle.text = if (triggersEnabled) "Turn off" else "Turn on"
        analogTriggersStatus.text = if (triggersEnabled)
            "Currently: on - L2/R2 pressure sent as real analog triggers (needs Shizuku granted)"
        else
            "Currently: off"
    }
}
