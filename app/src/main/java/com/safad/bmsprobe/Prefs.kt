package com.safad.bmsprobe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    // ------------------------------------------------------------------ venues

    fun venues(c: Context): List<Venue> {
        val raw = sp(c).getString("venues_json", null)
        val parsed = parseVenues(raw)
        if (parsed.isNotEmpty()) return parsed

        // v0.5 and earlier stored only a set of codes. Keep exactly that selection.
        val old = sp(c).getStringSet("codes", DEFAULT_CODES) ?: DEFAULT_CODES
        val migrated = SEED_VENUES.filter { it.code in old }.ifEmpty {
            SEED_VENUES.filter { it.code in DEFAULT_CODES }
        }
        setVenues(c, migrated)
        return migrated
    }

    fun setVenues(c: Context, list: List<Venue>) {
        val clean = list
            .filter { validCode(it.code) && validPart(it.region) && validPart(it.slug) }
            .distinctBy { it.code }
            .ifEmpty { SEED_VENUES.filter { it.code in DEFAULT_CODES } }
        val arr = JSONArray()
        clean.forEach { v ->
            arr.put(
                JSONObject()
                    .put("code", v.code)
                    .put("name", v.name)
                    .put("region", v.region)
                    .put("slug", v.slug)
            )
        }
        sp(c).edit().putString("venues_json", arr.toString()).apply()
    }

    fun watchedVenues(c: Context): List<Venue> = venues(c)

    fun renameVenue(c: Context, code: String, name: String) {
        if (name.isBlank()) return
        val list = venues(c)
        if (list.none { it.code == code && it.name == name }) {
            setVenues(c, list.map { if (it.code == code) it.copy(name = name) else it })
        }
    }

    // ------------------------------------------------------------------ movies

    /** Empty means "any movie": alert as soon as the date opens. */
    fun movies(c: Context): List<Movie> = parseMovies(sp(c).getString("movies_json", null))

    fun setMovies(c: Context, list: List<Movie>) {
        val clean = list
            .filter { validCode(it.code) }
            .map {
                it.copy(
                    code = it.code.uppercase(),
                    group = it.group.uppercase(),
                    title = it.title.ifBlank { it.code },
                    region = it.region.ifBlank { "kochi" },
                )
            }
            .distinctBy { it.key }
        val arr = JSONArray()
        clean.forEach { m ->
            arr.put(
                JSONObject()
                    .put("code", m.code)
                    .put("group", m.group)
                    .put("title", m.title)
                    .put("slug", m.slug)
                    .put("region", m.region)
            )
        }
        sp(c).edit().putString("movies_json", arr.toString()).apply()
    }

    // ------------------------------------------------------------------ alerts

    /** One persistent flag per cinema + date + movie, so a service restart does not re-scream. */
    fun alertKey(venue: String, date: String, group: String): String =
        "${venue}_${date}_${group.ifBlank { "DATE" }}"

    fun isAlerted(c: Context, key: String): Boolean = sp(c).getBoolean("seen_$key", false)

    fun alertAt(c: Context, key: String): Long = sp(c).getLong("seen_at_$key", 0L)

    fun markAlerted(c: Context, key: String) {
        sp(c).edit()
            .putBoolean("seen_$key", true)
            .putLong("seen_at_$key", System.currentTimeMillis())
            .apply()
    }

    fun clearAlerted(c: Context, key: String) {
        sp(c).edit().remove("seen_$key").remove("seen_at_$key").apply()
    }

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("autostart", true)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()

    // ------------------------------------------------------------------ json

    private fun parseVenues(raw: String?): List<Venue> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val code = o.optString("code").uppercase()
                    val region = o.optString("region").lowercase()
                    val slug = o.optString("slug").lowercase()
                    if (!validCode(code) || !validPart(region) || !validPart(slug)) continue
                    val name = o.optString("name").ifBlank { code }
                    add(Venue(code, name, region, slug))
                }
            }.distinctBy { it.code }
        }.getOrDefault(emptyList())
    }

    private fun parseMovies(raw: String?): List<Movie> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val code = o.optString("code").uppercase()
                    if (!validCode(code)) continue
                    add(
                        Movie(
                            code = code,
                            group = o.optString("group").uppercase(),
                            title = o.optString("title").ifBlank { code },
                            slug = o.optString("slug").lowercase(),
                            region = o.optString("region").ifBlank { "kochi" }.lowercase(),
                        )
                    )
                }
            }.distinctBy { it.key }
        }.getOrDefault(emptyList())
    }

    private fun validCode(s: String) = s.length in 3..12 && s.all { it.isLetterOrDigit() }
    private fun validPart(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' }
}
