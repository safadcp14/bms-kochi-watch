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

object Notifier {

    const val CH_ALARM = "alarm"
    const val CH_STATUS = "status"
    const val ID_SERVICE = 10
    const val ID_ALARM = 11

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Doomsday alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when 17 Dec shows appear"
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

    fun alarm(c: Context, venue: Venue, target: String, codes: List<String>) {
        val full = Intent(c, AlarmActivity::class.java).apply {
            putExtra("code", venue.code)
            putExtra("name", venue.name)
            putExtra("target", target)
            putExtra("url", venue.urlFor(target))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            c, 42, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            c, 43,
            Intent(c, WatchService::class.java).setAction(WatchService.ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val open = PendingIntent.getActivity(
            c, 44,
            Intent(Intent.ACTION_VIEW, Uri.parse(venue.urlFor(target))),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(c, CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("DOOMSDAY · ${venue.code}")
            .setContentText("${venue.name} just posted $target · ${codes.size} dates live")
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
            NotificationManagerCompat.from(c).notify(ID_ALARM, n)
        }
        runCatching { c.startActivity(full) }
    }

    fun cancelAlarm(c: Context) {
        runCatching { NotificationManagerCompat.from(c).cancel(ID_ALARM) }
    }

    fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 45,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
