package com.safad.bmsprobe

import kotlinx.coroutines.flow.MutableStateFlow

data class Venue(
    val code: String,
    val name: String,
    val slug: String,
    val path: String,
) {
    val baseUrl: String get() = "https://in.bookmyshow.com/cinemas/$slug/$path/buytickets/$code"
    fun urlFor(date: String) = "$baseUrl/$date"
}

val ALL_VENUES = listOf(
    Venue("PMCE", "Padma Cinema", "kochi", "padma-cinema-kochi"),
    Venue("SCKO", "Sarita Cinema", "kochi", "sarita-cinema-rgb-laser-4k-3d-atmos-kochi"),
    Venue("EVMK", "EVM Dolby (control)", "kochi", "evm-dolby-cinema-fort-kochi"),
)

val CONTROL_URL = "https://in.bookmyshow.com/cinemas/kochi/evm-dolby-cinema-fort-kochi/buytickets/EVMK"

data class VenueState(
    val code: String,
    val name: String,
    val codes: List<String> = emptyList(),
    val status: String = "idle",
    val latencyMs: Long = 0,
    val lastAt: Long = 0,
    val hit: Boolean = false,
    val failures: Int = 0,
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

    fun resetVenues(list: List<Venue>) {
        venues.value = list.map { VenueState(it.code, it.name) }
    }

    fun update(code: String, block: (VenueState) -> VenueState) {
        venues.value = venues.value.map { if (it.code == code) block(it) else it }
    }
}
