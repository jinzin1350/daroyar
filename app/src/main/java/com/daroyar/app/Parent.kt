package com.daroyar.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Everything that happens on the parents' phone.
 *
 * Alarms are 100% local, so reminders work with no internet at all.
 * The internet is only used to tell the caregiver what happened.
 */
object Parent {
    const val GRACE_MIN = 30      // after this, an unconfirmed dose becomes "missed"
    const val RERING_MIN = 10     // ring again every 10 minutes until confirmed
    private const val KEEP_DAYS = 7
    private const val MIN = 60_000L

    private val lock = Any()

    // ------------------------------------------------------------ medicines

    fun meds(c: Context): List<Med> {
        val s = Prefs.sp(c).getString("meds", "[]") ?: "[]"
        return try {
            Med.listFrom(JSONArray(s))
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun saveMeds(c: Context, list: List<Med>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Prefs.sp(c).edit().putString("meds", arr.toString()).commit()
    }

    fun med(c: Context, id: Long): Med? = meds(c).firstOrNull { it.id == id }

    fun upsertMed(c: Context, med: Med) {
        synchronized(lock) {
            val list = meds(c).toMutableList()
            val i = list.indexOfFirst { it.id == med.id }
            if (i >= 0) list[i] = med else list.add(med)
            saveMeds(c, list)
        }
        scheduleNext(c)
        Sync.now(c)
    }

    fun deleteMed(c: Context, id: Long) {
        synchronized(lock) {
            saveMeds(c, meds(c).filter { it.id != id })
        }
        scheduleNext(c)
        Sync.now(c)
    }

    /** Distinct dose times of all active medicines, sorted. */
    fun allTimes(c: Context): List<String> =
        meds(c).filter { it.active }.flatMap { it.times }.distinct().sorted()

    /** Medicines due at this slot (ignores slots from before the schedule was set). */
    fun medsAt(c: Context, date: String, time: String): List<Med> {
        val due = Clock.epoch(date, time)
        return meds(c).filter { it.active && time in it.times && due >= it.since - MIN }
    }

    // ------------------------------------------------------------ dose events

    fun events(c: Context): MutableList<DoseEvent> {
        val s = Prefs.sp(c).getString("events", "[]") ?: "[]"
        return try {
            DoseEvent.listFrom(JSONArray(s))
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    private fun saveEvents(c: Context, list: List<DoseEvent>) {
        val oldest = Clock.addDays(Clock.today(), -KEEP_DAYS)
        val arr = JSONArray()
        list.filter { it.date >= oldest }.forEach { arr.put(it.toJson()) }
        Prefs.sp(c).edit().putString("events", arr.toString()).commit()
    }

    fun slotEvents(c: Context, date: String, time: String): List<DoseEvent> =
        events(c).filter { it.date == date && it.time == time }

    /** Creates "pending" events for every medicine due at this slot (if missing). */
    fun ensureSlot(c: Context, date: String, time: String): List<DoseEvent> = synchronized(lock) {
        val evs = events(c)
        val due = Clock.epoch(date, time)
        var changed = false
        for (m in medsAt(c, date, time)) {
            if (evs.none { it.medId == m.id && it.date == date && it.time == time }) {
                evs.add(DoseEvent(m.id, m.name, m.dose, date, time, due, PENDING))
                changed = true
            }
        }
        if (changed) saveEvents(c, evs)
        evs.filter { it.date == date && it.time == time }
    }

    fun markTaken(c: Context, date: String, time: String) {
        if (date == "test") {
            Notify.cancelSlot(c, date, time)
            return
        }
        synchronized(lock) {
            ensureSlot(c, date, time)
            val evs = events(c)
            val now = Clock.now()
            evs.filter { it.date == date && it.time == time && it.status != TAKEN }.forEach {
                it.status = TAKEN
                it.takenAt = now
            }
            saveEvents(c, evs)
        }
        Notify.cancelSlot(c, date, time)
        cancelSlotAlarms(c, date, time)
        Sync.now(c)
    }

    /** Called [GRACE_MIN] after the dose time. */
    fun markMissedIfPending(c: Context, date: String, time: String, notify: Boolean) {
        val lines: List<String> = synchronized(lock) {
            val evs = events(c)
            val pend = evs.filter { it.date == date && it.time == time && it.status == PENDING }
            if (pend.isEmpty()) return
            val now = Clock.now()
            pend.forEach {
                it.status = MISSED
                it.missedAt = now
            }
            saveEvents(c, evs)
            pend.map { it.label() }
        }
        cancelSlotAlarms(c, date, time)
        if (notify) Notify.showLateReminder(c, date, time, lines) else Notify.cancelSlot(c, date, time)
        Sync.now(c)
    }

    // ------------------------------------------------------------ alarm flow

    /** The dose time has come. */
    fun onDue(c: Context, date: String, time: String) {
        val evs = ensureSlot(c, date, time)
        val pend = evs.filter { it.status == PENDING }
        if (pend.isNotEmpty()) {
            Notify.showDoseAlarm(c, date, time, pend.map { it.label() })
            val due = Clock.epoch(date, time)
            setAt(c, due + RERING_MIN * MIN, Notify.slotBroadcast(c, "rering1", date, time))
            setAt(c, due + 2 * RERING_MIN * MIN, Notify.slotBroadcast(c, "rering2", date, time))
            setAt(c, due + GRACE_MIN * MIN, Notify.slotBroadcast(c, "missed", date, time))
        }
        scheduleNext(c)
    }

    /** Ring again if still not confirmed. */
    fun onRering(c: Context, date: String, time: String) {
        val pend = slotEvents(c, date, time).filter { it.status == PENDING }
        if (pend.isNotEmpty()) Notify.showDoseAlarm(c, date, time, pend.map { it.label() })
    }

    /** "Remind me in 10 minutes". */
    fun snooze(c: Context, date: String, time: String) {
        Notify.cancelSlot(c, date, time)
        if (date == "test") return
        setAt(c, Clock.now() + RERING_MIN * MIN, Notify.slotBroadcast(c, "snooze", date, time))
    }

    private fun cancelSlotAlarms(c: Context, date: String, time: String) {
        val am = c.getSystemService(AlarmManager::class.java)
        for (k in listOf("rering1", "rering2", "snooze", "missed")) {
            try {
                am.cancel(Notify.slotBroadcast(c, k, date, time))
            } catch (_: Throwable) {
            }
        }
    }

    private fun dueIntent(c: Context, date: String?, time: String?): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION)
            .setData(AlarmReceiver.uri("due", null, null))
        if (date != null && time != null) i.putExtra("date", date).putExtra("time", time)
        return PendingIntent.getBroadcast(
            c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next dose slot strictly after [after]. */
    fun nextSlot(c: Context, after: Long): Pair<String, String>? {
        val times = allTimes(c)
        if (times.isEmpty()) return null
        val today = Clock.today()
        for (d in 0..2) {
            val date = Clock.addDays(today, d)
            for (t in times) {
                if (Clock.epoch(date, t) > after) return date to t
            }
        }
        return null
    }

    /** Keeps exactly one "next dose" alarm armed. */
    fun scheduleNext(c: Context) {
        if (Prefs.role(c) != Prefs.PARENT) return
        val next = nextSlot(c, Clock.now() + 1000)
        if (next == null) {
            c.getSystemService(AlarmManager::class.java).cancel(dueIntent(c, null, null))
            return
        }
        setAt(c, Clock.epoch(next.first, next.second), dueIntent(c, next.first, next.second))
    }

    /**
     * Alarm-clock alarms: exact, wake the phone from deep sleep and allow
     * network right after — the most reliable kind Android offers.
     */
    fun setAt(c: Context, at: Long, pi: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                val show = PendingIntent.getActivity(
                    c, 2,
                    Intent(c, ParentHomeActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                return
            }
        } catch (_: SecurityException) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun scheduleTestAlarm(c: Context) {
        setAt(c, Clock.now() + 10_000, Notify.slotBroadcast(c, "test", "test", "test"))
    }

    /**
     * Repairs anything that went wrong while the phone was off or the app was
     * killed: creates missing events, marks overdue ones as missed, re-arms alarms.
     */
    fun selfHeal(c: Context, fromBoot: Boolean) {
        val now = Clock.now()
        val today = Clock.today()
        val times = allTimes(c)
        for (date in listOf(Clock.addDays(today, -1), today)) {
            for (t in times) {
                val due = Clock.epoch(date, t)
                if (due > now || now - due > 26 * 60 * MIN) continue
                val evs = ensureSlot(c, date, t)
                val pend = evs.filter { it.status == PENDING }
                if (pend.isEmpty()) continue
                if (now >= due + GRACE_MIN * MIN) {
                    markMissedIfPending(c, date, t, notify = date == today)
                } else {
                    setAt(c, due + GRACE_MIN * MIN, Notify.slotBroadcast(c, "missed", date, t))
                    if (fromBoot) Notify.showDoseAlarm(c, date, t, pend.map { it.label() })
                }
            }
        }
        scheduleNext(c)
    }

    // ------------------------------------------------------------ reporting

    fun stateJson(c: Context): String {
        val now = Clock.now()
        val tz = TimeZone.getDefault()
        val oldest = Clock.addDays(Clock.today(), -3)
        val meds = JSONArray()
        meds(c).forEach { meds.put(it.toJson()) }
        val evs = JSONArray()
        events(c).filter { it.date >= oldest }.forEach { evs.put(it.toJson()) }
        return JSONObject()
            .put("v", 1)
            .put("name", Prefs.deviceName(c))
            .put("sentAt", now)
            .put("tz", tz.id)
            .put("tzOff", tz.getOffset(now) / 60000)
            .put("grace", GRACE_MIN)
            .put("ver", BuildConfig.VERSION_NAME)
            .put("meds", meds)
            .put("events", evs)
            .put(
                "diag", JSONObject()
                    .put("notif", Diag.notifOk(c))
                    .put("exact", Diag.exactOk(c))
                    .put("fsi", Diag.fullScreenOk(c))
                    .put("battery", Diag.batteryOk(c))
                    .put("tz", Diag.timeZoneOk())
            )
            .toString()
    }

    /** Sends the encrypted state to the brokers. Blocking — call off the main thread. */
    fun publish(c: Context): Boolean {
        val code = Prefs.code(c)
        val payload = Crypto.encrypt(code, stateJson(c))
        val log = mutableListOf<String>()
        val ok = Cloud.publishRetained(Cloud.stateTopic(Crypto.topicId(code)), payload, log) > 0
        Prefs.recordSync(c, ok, log)
        return ok
    }
}
