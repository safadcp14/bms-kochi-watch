package com.safad.bmsprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Collections

data class AlertInfo(
    val venue: Venue,
    val targetDate: String,
    val movie: String?,
    val times: List<String>,
    val url: String,
    val key: String,
)

object Notifier {

    const val CH_ALARM = "alarm"
    const val CH_STATUS = "status"
    const val ID_SERVICE = 10
    const val ID_ALARM = 11

    private val active = Collections.synchronizedSet(mutableSetOf<Int>())

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Ticket alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when a watched date or movie appears"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 250, 600, 250)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Watcher status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet status of the background watcher"
                setShowBadge(false)
            }
        )
    }

    fun service(c: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(c, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(c))
            .build()

    fun alarm(c: Context, a: AlertInfo) {
        val id = ID_ALARM + (a.key.hashCode() and 0x3FF)
        active.add(id)

        val full = Intent(c, AlarmActivity::class.java).apply {
            putExtra("code", a.venue.code)
            putExtra("name", a.venue.name)
            putExtra("target", a.targetDate)
            putExtra("url", a.url)
            putExtra("movie", a.movie)
            putStringArrayListExtra("times", ArrayList(a.times))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            c, id, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            c, id + 1000,
            Intent(c, WatchService::class.java).setAction(WatchService.ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val open = PendingIntent.getActivity(
            c, id + 2000,
            Intent(Intent.ACTION_VIEW, Uri.parse(a.url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            a.movie != null -> "${a.movie} · ${a.venue.code}"
            else -> "DATE OPEN · ${a.venue.code}"
        }
        val text = when {
            a.movie != null && a.times.isNotEmpty() ->
                "${a.venue.name} · ${humanDateShort(a.targetDate)} · ${a.times.take(3).joinToString(", ")}"
            a.movie != null ->
                "${a.venue.name} · ${humanDateShort(a.targetDate)} · shows posted"
            else ->
                "${a.venue.name} just listed ${humanDateShort(a.targetDate)}"
        }

        val n = NotificationCompat.Builder(c, CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, "Open BookMyShow", open)
            .addAction(0, "Silence", stop)
            .build()

        runCatching {
            NotificationManagerCompat.from(c).notify(id, n)
        }
        runCatching { c.startActivity(full) }
    }

    fun cancelAlarms(c: Context) {
        val nm = NotificationManagerCompat.from(c)
        synchronized(active) {
            active.forEach { runCatching { nm.cancel(it) } }
            active.clear()
        }
        runCatching { nm.cancel(ID_ALARM) }
    }

    fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 45,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
