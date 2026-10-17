package com.daroyar.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone

/** Checks for the settings that silently break alarms on real phones. */
object Diag {
    fun notifOk(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(c).areNotificationsEnabled()
    }

    fun alarmChannelOk(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val ch = c.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(Notify.CH_ALARM) ?: return true
        return ch.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    fun exactOk(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun fullScreenOk(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun batteryOk(c: Context): Boolean {
        val pm = c.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    /** Old phones without tz updates still apply the abolished Iranian DST (+04:30). */
    fun timeZoneOk(): Boolean {
        val tz = TimeZone.getDefault()
        if (tz.id != "Asia/Tehran") return true
        return tz.getOffset(System.currentTimeMillis()) == 12_600_000
    }

    fun allOkParent(c: Context): Boolean =
        notifOk(c) && alarmChannelOk(c) && exactOk(c) && fullScreenOk(c) && batteryOk(c) && timeZoneOk()
}

/** Looks up the newest published release on GitHub. */
object Updater {
    class Release(val versionCode: Int, val name: String, val url: String)

    /** Blocking. Returns null when offline or nothing found. */
    fun latest(): Release? {
        return try {
            val conn = URL("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name")
            val vc = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
            }
            if (url == null) null else Release(vc, tag, url)
        } catch (t: Throwable) {
            null
        }
    }
}
