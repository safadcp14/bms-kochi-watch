package com.safad.bmsprobe

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

class WatchService : Service() {

    companion object {
        const val ACTION_START = "com.safad.bmsprobe.START"
        const val ACTION_STOP = "com.safad.bmsprobe.STOP"
        const val ACTION_POLL_NOW = "com.safad.bmsprobe.POLL_NOW"
        const val ACTION_STOP_ALARM = "com.safad.bmsprobe.STOP_ALARM"
        const val EXTRA_FOREGROUND = "fg"
        private const val CLEAR_AFTER_MS = 6 * 3600_000L

        fun start(c: Context) {
            val i = Intent(c, WatchService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, WatchService::class.java).setAction(ACTION_STOP))
        }

        fun pollNow(c: Context) {
            ContextCompat.startForegroundService(
                c,
                Intent(c, WatchService::class.java).setAction(ACTION_POLL_NOW)
            )
        }
    }

    // Default, not Main: file history writes and the loop itself must never block the UI
    // thread. PollEngine.load() hops to Main itself for the WebView calls.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: PollEngine? = null
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var consecutiveBadCycles = 0
    private var degradedNotified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifier.ID_SERVICE,
            Notifier.service(this, "BMS watch is starting", "Booting the watcher"),
            if (android.os.Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        engine = PollEngine(this)
        WatchState.running.value = true
        loop = scope.launch { runLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALARM -> {
                Siren.stop(this)
                Notifier.cancelAlarms(this)
            }
            ACTION_POLL_NOW -> poke.trySend(Unit)
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val e = engine ?: return
        var venues = Prefs.watchedVenues(this)
        WatchState.resetVenues(venues)
        WatchState.intervalSec.value = Prefs.interval(this)
        WatchState.configVersion.value++

        // Warm-up: burn the Cloudflare challenge (if any) before the first real poll,
        // so the first reading is not the one that times out.
        venues.firstOrNull()?.let { v ->
            val target = Prefs.target(this)
            update("Warming up · ${v.code}", "Solving any browser challenge")
            e.load(
                v.urlFor(target),
                v.code,
                timeoutMs = 35_000,
                targetDate = target,
                movies = Prefs.movies(this),
            )
        }

        while (coroutineContext.isActive) {
            val started = System.currentTimeMillis()
            val target = Prefs.target(this)
            val movies = Prefs.movies(this)
            WatchState.targetDate.value = target
            venues = Prefs.watchedVenues(this)
            var good = 0

            for (v in venues) {
                if (!coroutineContext.isActive) break
                if (pollOne(v, target, movies)) good++
            }

            consecutiveBadCycles = if (good == 0) consecutiveBadCycles + 1 else 0
            if (consecutiveBadCycles >= 3 && !degradedNotified) {
                degradedNotified = true
                WatchState.degraded.value = true
                update("Watcher degraded", "3 cycles with no readable page — check the app")
            }
            if (consecutiveBadCycles == 0) {
                degradedNotified = false
                WatchState.degraded.value = false
            }

            val intervalMs = Prefs.interval(this) * 1000L
            val next = System.currentTimeMillis() + intervalMs
            WatchState.lastCycleAt.value = started
            WatchState.nextCycleAt.value = next
            val mode = if (movies.isEmpty()) "any movie" else "${movies.size} movie(s)"
            update(
                if (WatchState.degraded.value) "Watcher degraded" else "Watching ${venues.size} cinemas · $mode",
                "${venues.joinToString(" · ") { it.code }} · next in ${intervalMs / 1000}s"
            )

            val jitter = Random.nextLong(0, 12_000)
            withTimeoutOrNull(intervalMs + jitter) { poke.receive() }
        }
    }

    /** returns true if the page was readable */
    private suspend fun pollOne(v: Venue, target: String, movies: List<Movie>): Boolean {
        val e = engine ?: return false
        val wl = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bms:poll").apply { acquire(50_000) }
        }.getOrNull()

        val r = try {
            e.load(
                v.urlFor(target),
                v.code,
                timeoutMs = 45_000,
                targetDate = target,
                movies = movies,
            )
        } finally {
            runCatching { wl?.release() }
        }

        if (r.venueName.isNotBlank()) Prefs.renameVenue(this, v.code, r.venueName)

        val dateOpen = r.ok && r.codes.contains(target)
        val hits = if (!r.ok) emptyList() else matchHits(r, movies, dateOpen)
        val status = when {
            hits.isNotEmpty() -> "HIT"
            r.ok -> "ok"
            r.challenge -> "challenge"
            else -> r.error.ifBlank { "fail" }
        }

        WatchState.update(v.code) { s ->
            s.copy(
                codes = if (r.ok) r.codes else s.codes,
                status = status,
                latencyMs = r.elapsedMs,
                lastAt = System.currentTimeMillis(),
                hit = s.hit || hits.isNotEmpty(),
                failures = if (r.ok) 0 else s.failures + 1,
                dateOpen = if (r.ok) dateOpen else s.dateOpen,
                matches = if (r.ok) hits else s.matches,
            )
        }

        History.append(
            this,
            HistoryEntry(
                at = System.currentTimeMillis(),
                code = v.code,
                status = status,
                latencyMs = r.elapsedMs,
                codes = r.codes,
                hit = hits.isNotEmpty(),
                detail = when {
                    hits.isEmpty() && dateOpen -> "date listed"
                    hits.isEmpty() -> ""
                    else -> hits.joinToString(" · ") { "${it.title} x${it.times.size}" }
                },
            )
        )

        if (r.ok) {
            clearStaleAlerts(v, target, movies, hits)
            hits.forEach { maybeAlert(v, target, movies, it) }
        }
        return r.ok
    }

    /** Movie mode alerts only for watched films; empty movie list means the old date-only mode. */
    private fun matchHits(r: LoadResult, movies: List<Movie>, dateOpen: Boolean): List<Match> {
        if (movies.isEmpty()) {
            return if (dateOpen) listOf(Match(group = "", title = "Any movie", times = emptyList())) else emptyList()
        }
        val grouped = LinkedHashMap<String, MutableList<Show>>()
        val titles = HashMap<String, String>()
        for (s in r.shows) {
            val configured = movies.firstOrNull { it.group.isNotBlank() && it.group == s.group }
                ?: movies.firstOrNull { it.code == s.code }
                ?: movies.firstOrNull { it.titleKey() == s.title.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' } }
            val key = configured?.key ?: s.group
            grouped.getOrPut(key) { mutableListOf() }.add(s)
            titles[key] = configured?.title ?: s.title
        }
        return grouped.map { (key, list) ->
            Match(
                group = key,
                title = titles[key] ?: list.first().title,
                times = list.map { it.time }.filter { it.isNotBlank() }.distinct(),
                code = list.first().code,
            )
        }
    }

    private fun clearStaleAlerts(v: Venue, target: String, movies: List<Movie>, hits: List<Match>) {
        val now = System.currentTimeMillis()
        val hitKeys = hits.map { it.group }.toSet()
        val keys = if (movies.isEmpty()) listOf("") else movies.map { it.key }
        for (key in keys) {
            if (key in hitKeys) continue
            val alertKey = Prefs.alertKey(v.code, target, key)
            if (now - Prefs.alertAt(this, alertKey) > CLEAR_AFTER_MS) {
                Prefs.clearAlerted(this, alertKey)
            }
        }
    }

    private fun maybeAlert(v: Venue, target: String, movies: List<Movie>, hit: Match) {
        val key = Prefs.alertKey(v.code, target, hit.group)
        if (Prefs.isAlerted(this, key)) return
        Prefs.markAlerted(this, key)

        WatchState.alertText.value = "${v.name} · ${hit.title} · ${humanDateShort(target)}"
        Siren.start(this)
        Notifier.alarm(
            this,
            AlertInfo(
                venue = v,
                targetDate = target,
                movie = hit.title.takeIf { movies.isNotEmpty() },
                times = hit.times,
                url = v.urlFor(target),
                key = key,
            )
        )
    }

    private fun update(title: String, text: String) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                Notifier.ID_SERVICE,
                Notifier.service(this, title, text),
                if (android.os.Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        }
    }

    override fun onDestroy() {
        WatchState.running.value = false
        WatchState.nextCycleAt.value = 0
        scope.cancel()
        Siren.stop(this)
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
