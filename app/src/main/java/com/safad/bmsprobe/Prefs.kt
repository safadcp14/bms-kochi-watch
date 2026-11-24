package com.safad.bmsprobe

import android.content.Context

object Prefs {
    private const val NAME = "watch"

    const val DEFAULT_TARGET = "20261217"
    val DEFAULT_CODES = setOf("PMCE", "SCKO")
    val ALLOWED_CODES = setOf("PMCE", "SCKO", "EVMK")

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun target(c: Context): String {
        val v = sp(c).getString("target", DEFAULT_TARGET) ?: DEFAULT_TARGET
        return if (v.length == 8 && v.all { it.isDigit() }) v else DEFAULT_TARGET
    }

    fun setTarget(c: Context, v: String) = sp(c).edit().putString("target", v).apply()

    fun interval(c: Context): Int = sp(c).getInt("interval", 60)
    fun setInterval(c: Context, v: Int) = sp(c).edit().putInt("interval", v).apply()

    /**
     * Any of the three can be watched, but the control venue is opt-in only: 17 Dec is
     * already on sale there, so it fires immediately once enabled.
     */
    fun watchCodes(c: Context): Set<String> {
        val raw = sp(c).getStringSet("codes", DEFAULT_CODES) ?: DEFAULT_CODES
        val clean = raw.filter { it in ALLOWED_CODES }.toSet()
        return clean.ifEmpty { DEFAULT_CODES }
    }

    fun setWatchCodes(c: Context, v: Set<String>) {
        val clean = v.filter { it in ALLOWED_CODES }.toSet().ifEmpty { DEFAULT_CODES }
        sp(c).edit().putStringSet("codes", clean).apply()
    }

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("autostart", true)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()

    /** last time we screamed for this venue, so one opening doesn't ring for an hour */
    fun lastAlert(c: Context, code: String): Long = sp(c).getLong("alert_$code", 0L)
    fun setLastAlert(c: Context, code: String, t: Long) = sp(c).edit().putLong("alert_$code", t).apply()

    fun watchedVenues(c: Context): List<Venue> {
        val codes = watchCodes(c)
        return ALL_VENUES.filter { it.code in codes }
    }
}
