package com.daroyar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * All our own alarms and notification buttons land here.
 * The intent data looks like daroyar://<kind>/<date>/<time>.
 */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.daroyar.app.ALARM"

        fun uri(kind: String, date: String?, time: String?): Uri {
            val b = Uri.Builder().scheme("daroyar").authority(kind)
            if (date != null) b.appendPath(date)
            if (time != null) b.appendPath(time)
            return b.build()
        }
    }

    override fun onReceive(c: Context, intent: Intent) {
        val data = intent.data ?: return
        val kind = data.authority ?: return
        val segs = data.pathSegments
        val date = intent.getStringExtra("date") ?: segs.getOrNull(0)
        val time = intent.getStringExtra("time") ?: segs.getOrNull(1)
        try {
            when (kind) {
                "due" -> if (date != null && time != null) Parent.onDue(c, date, time) else Parent.scheduleNext(c)
                "rering1", "rering2", "snooze" -> if (date != null && time != null) Parent.onRering(c, date, time)
                "missed" -> if (date != null && time != null) Parent.markMissedIfPending(c, date, time, notify = true)
                "taken" -> if (date != null && time != null) Parent.markTaken(c, date, time)
                "later" -> if (date != null && time != null) Parent.snooze(c, date, time)
                "test" -> Notify.showDoseAlarm(c, "test", "test", listOf("این یک آلارم آزمایشی است", "اگر صدا و صفحه را می‌بینید، همه‌چیز درست است ✅"))
                "care" -> Sync.now(c)
            }
        } catch (t: Throwable) {
            android.util.Log.e("Daroyar", "alarm $kind failed", t)
        }
    }
}

/** Re-arms everything after a reboot, an app update or a clock change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        try {
            when (Prefs.role(c)) {
                Prefs.PARENT -> {
                    Sync.schedulePeriodic(c)
                    Parent.selfHeal(c, fromBoot = boot)
                    Sync.now(c)
                }
                Prefs.CARE -> {
                    Sync.schedulePeriodic(c)
                    Care.scheduleCheck(c)
                    Sync.now(c)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("Daroyar", "boot handling failed", t)
        }
    }
}
