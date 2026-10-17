package com.daroyar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

object Notify {
    const val CH_ALARM = "dose_alarm_v1"
    const val CH_INFO = "dose_info_v1"
    const val CH_CARE = "care_alert_v1"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun alarmSound(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI

    fun createChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val alarm = NotificationChannel(CH_ALARM, "زنگ دارو", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "زنگ وقت خوردن دارو"
            setSound(
                alarmSound(),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 900, 500, 900, 500, 900)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val info = NotificationChannel(CH_INFO, "یادآوری‌ها", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "یادآوری داروهایی که تأیید نشده‌اند"
        }
        val care = NotificationChannel(CH_CARE, "هشدار داروی والدین", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "وقتی داروی پدر یا مادر تأیید نشود"
            enableVibration(true)
        }
        nm(c).createNotificationChannels(listOf(alarm, info, care))
    }

    fun slotId(date: String, time: String): Int = 100_000 + (("$date$time".hashCode()) and 0xffff)

    /** Broadcast PendingIntent to [AlarmReceiver] for a slot. */
    fun slotBroadcast(c: Context, kind: String, date: String, time: String): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION)
            .setData(AlarmReceiver.uri(kind, date, time))
        return PendingIntent.getBroadcast(
            c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** The ringing reminder: full screen over the lock screen + loud alarm sound. */
    fun showDoseAlarm(c: Context, date: String, time: String, lines: List<String>) {
        val full = Intent(c, AlarmActivity::class.java)
            .putExtra("date", date)
            .putExtra("time", time)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val fullPi = PendingIntent.getActivity(
            c, slotId(date, time), full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (date == "test") "آلارم آزمایشی ⏰" else "وقت داروست ⏰  " + Fa.time(time)
        val text = lines.joinToString("\n")
        val b = NotificationCompat.Builder(c, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(title)
            .setContentText(lines.joinToString("، "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, "✅ خوردم", slotBroadcast(c, "taken", date, time))
            .addAction(0, "⏰ ۱۰ دقیقه دیگه", slotBroadcast(c, "later", date, time))
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            b.setSound(alarmSound(), AudioManager.STREAM_ALARM)
            b.setVibrate(longArrayOf(0, 900, 500, 900, 500, 900))
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        post(c, slotId(date, time), n)
    }

    /** Quiet follow-up after the grace period: "not confirmed — tap if you took it". */
    fun showLateReminder(c: Context, date: String, time: String, lines: List<String>) {
        val open = PendingIntent.getActivity(
            c, slotId(date, time),
            Intent(c, ParentHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("داروی ساعت ${Fa.time(time)} تأیید نشد")
            .setContentText("اگر خوردید، «خوردم» را بزنید")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(lines.joinToString("\n") + "\nاگر خوردید، «خوردم» را بزنید.")
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "✅ خوردم", slotBroadcast(c, "taken", date, time))
            .build()
        post(c, slotId(date, time), n)
    }

    fun cancelSlot(c: Context, date: String, time: String) {
        try {
            nm(c).cancel(slotId(date, time))
        } catch (_: Throwable) {
        }
    }

    /** Alerts on the caregiver's phone. */
    fun care(c: Context, key: String, title: String, text: String) {
        val open = PendingIntent.getActivity(
            c, 7,
            Intent(c, CareHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, CH_CARE)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        post(c, 200_000 + (key.hashCode() and 0xfffff), n)
    }

    private fun post(c: Context, id: Int, n: Notification) {
        try {
            nm(c).notify(id, n)
        } catch (_: SecurityException) {
            // notifications permission missing; Diagnostics shows this
        }
    }
}
