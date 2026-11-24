package com.safad.bmsprobe

import android.content.Context

object Prefs {
    private const val NAME = "watch"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun target(c: Context): String = sp(c).getString("target", "20261217") ?: "20261217"
    fun setTarget(c: Context, v: String) = sp(c).edit().putString("target", v).apply()

    fun interval(c: Context): Int = sp(c).getInt("interval", 60)
    fun setInterval(c: Context, v: Int) = sp(c).edit().putInt("interval", v).apply()

    fun watchCodes(c: Context): Set<String> =
        sp(c).getStringSet("codes", setOf("PMCE", "SCKO")) ?: setOf("PMCE", "SCKO")

    fun setWatchCodes(c: Context, v: Set<String>) = sp(c).edit().putStringSet("codes", v).apply()

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
