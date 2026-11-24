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
            Notifier.service(this, "Doomsday watch is starting", "Booting the watcher"),
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
                Notifier.cancelAlarm(this)
            }
            ACTION_POLL_NOW -> poke.trySend(Unit)
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val e = engine ?: return
        val venues = Prefs.watchedVenues(this)
        WatchState.resetVenues(venues)
        WatchState.intervalSec.value = Prefs.interval(this)

        // Warm-up: burn the Cloudflare challenge (if any) before the first real poll,
        // so the first reading is not the one that times out.
        venues.firstOrNull()?.let { v ->
            update("Warming up · ${v.code}", "Solving any browser challenge")
            e.load(v.baseUrl, v.code, timeoutMs = 35_000)
        }

        while (coroutineContext.isActive) {
            val started = System.currentTimeMillis()
            val target = Prefs.target(this)
            WatchState.targetDate.value = target
            val list = Prefs.watchedVenues(this)
            var good = 0

            for (v in list) {
                if (!coroutineContext.isActive) break
                if (pollOne(v, target)) good++
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
            update(
                if (WatchState.degraded.value) "Watcher degraded" else "Watching ${list.size} venues",
                "${list.joinToString(" · ") { it.code }} · next in ${intervalMs / 1000}s"
            )

            val jitter = Random.nextLong(0, 12_000)
            withTimeoutOrNull(intervalMs + jitter) { poke.receive() }
        }
    }

    /** returns true if the page was readable */
    private suspend fun pollOne(v: Venue, target: String): Boolean {
        val e = engine ?: return false
        val wl = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bms:poll").apply { acquire(50_000) }
        }.getOrNull()

        val r = try {
            e.load(v.baseUrl, v.code, timeoutMs = 45_000)
        } finally {
            runCatching { wl?.release() }
        }

        val hit = r.ok && r.codes.contains(target)
        val status = when {
            hit -> "HIT"
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
                hit = s.hit || hit,
                failures = if (r.ok) 0 else s.failures + 1,
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
                hit = hit,
            )
        )

        if (hit) maybeAlert(v, target, r.codes)
        return r.ok
    }

    private fun maybeAlert(v: Venue, target: String, codes: List<String>) {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastAlert(this, v.code) < 6 * 3600_000L) return
        Prefs.setLastAlert(this, v.code, now)
        WatchState.alertText.value = "${v.name} · ${codes.joinToString(" ")}"
        Siren.start(this)
        Notifier.alarm(this, v, target, codes)
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
