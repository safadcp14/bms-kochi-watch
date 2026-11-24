package com.safad.bmsprobe

import android.content.Context

object Prefs {
    private const val NAME = "watch"

    const val DEFAULT_TARGET = "20261217"
    val DEFAULT_CODES = setOf("PMCE", "SCKO")

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun target(c: Context): String {
        val v = sp(c).getString("target", DEFAULT_TARGET) ?: DEFAULT_TARGET
        return if (v.length == 8 && v.all { it.isDigit() }) v else DEFAULT_TARGET
    }

    fun setTarget(c: Context, v: String) = sp(c).edit().putString("target", v).apply()

    fun interval(c: Context): Int = sp(c).getInt("interval", 60)
    fun setInterval(c: Context, v: Int) = sp(c).edit().putInt("interval", v).apply()

    /**
     * Only the two target screens are ever watchable. The control venue (EVMK) must never
     * end up here: 17 Dec is already on sale there, so watching it would fire a false alarm
     * the moment the service starts.
     */
    fun watchCodes(c: Context): Set<String> {
        val raw = sp(c).getStringSet("codes", DEFAULT_CODES) ?: DEFAULT_CODES
        val clean = raw.filter { it == "PMCE" || it == "SCKO" }.toSet()
        return clean.ifEmpty { DEFAULT_CODES }
    }

    fun setWatchCodes(c: Context, v: Set<String>) {
        val clean = v.filter { it == "PMCE" || it == "SCKO" }.toSet().ifEmpty { DEFAULT_CODES }
        sp(c).edit().putStringSet("codes", clean).apply()
    }

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("autostart", true)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()

    /** last time we screamed for this venue, so one opening doesn't ring for an hour */
    fun lastAlert(c: Context, code: String): Long = sp(c).getLong("alert_$code", 0L)
    fun setLastAlert(c: Context, code: String, t: Long) = sp(c).edit().putLong("alert_$code", t).apply()

    fun watchedVenues(c: Context): List<Venue> {
        val codes = watchCodes(c)
        return listOfNotNull(
            ALL_VENUES.firstOrNull { it.code == "PMCE" && "PMCE" in codes },
            ALL_VENUES.firstOrNull { it.code == "SCKO" && "SCKO" in codes },
        )
    }
}
