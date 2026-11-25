package com.safad.bmsprobe

import kotlinx.coroutines.flow.MutableStateFlow

data class Venue(
    val code: String,
    val name: String,
    val region: String,
    val slug: String,
) {
    val baseUrl: String get() = "https://in.bookmyshow.com/cinemas/$region/$slug/buytickets/$code"
    fun urlFor(date: String) = "$baseUrl/$date"
}

/**
 * A BookMyShow movie. [code] is the language/format event (ET…), [group] is the
 * movie-level id (EG…). Venue pages expose both, and the group is what lets one
 * watch cover every language/format of the same film.
 */
data class Movie(
    val code: String,
    val group: String = "",
    val title: String,
    val slug: String = "",
    val region: String = "kochi",
) {
    val key: String get() = group.ifBlank { code }
    fun url(): String =
        "https://in.bookmyshow.com/movies/$region/${slug.ifBlank { "x" }}/$code"

    /** fallback matcher when the group could not be resolved */
    fun titleKey(): String = title.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
}

/** Seeded so an upgrade keeps watching exactly what v0.5 watched. */
val SEED_VENUES = listOf(
    Venue("PMCE", "Padma Cinema", "kochi", "padma-cinema-kochi"),
    Venue("SCKO", "Sarita Cinema", "kochi", "sarita-cinema-rgb-laser-4k-3d-atmos-kochi"),
    Venue("EVMK", "EVM Dolby Cinema", "kochi", "evm-dolby-cinema-fort-kochi"),
)

data class Match(
    val group: String,
    val title: String,
    val times: List<String>,
    val code: String = "",
)

data class VenueState(
    val code: String,
    val name: String,
    val codes: List<String> = emptyList(),
    val status: String = "idle",
    val latencyMs: Long = 0,
    val lastAt: Long = 0,
    val hit: Boolean = false,
    val failures: Int = 0,
    val dateOpen: Boolean = false,
    val matches: List<Match> = emptyList(),
)

object WatchState {
    val running = MutableStateFlow(false)
    val venues = MutableStateFlow<List<VenueState>>(emptyList())
    val targetDate = MutableStateFlow("20261217")
    val intervalSec = MutableStateFlow(60)
    val lastCycleAt = MutableStateFlow(0L)
    val nextCycleAt = MutableStateFlow(0L)
    val degraded = MutableStateFlow(false)
    val alertText = MutableStateFlow("")

    /** bumped after a config save so the header/subtitle recomposes */
    val configVersion = MutableStateFlow(0)

    fun resetVenues(list: List<Venue>) {
        venues.value = list.map { VenueState(it.code, it.name) }
    }

    fun update(code: String, block: (VenueState) -> VenueState) {
        venues.value = venues.value.map { if (it.code == code) block(it) else it }
    }
}

fun humanDate(yyyymmdd: String): String = runCatching {
    val parser = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
    val out = java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.US)
    out.format(parser.parse(yyyymmdd)!!)
}.getOrDefault(yyyymmdd)

fun humanDateShort(yyyymmdd: String): String = runCatching {
    val parser = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
    val out = java.text.SimpleDateFormat("d MMM", java.util.Locale.US)
    out.format(parser.parse(yyyymmdd)!!)
}.getOrDefault(yyyymmdd)
