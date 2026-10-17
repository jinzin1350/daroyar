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
 * The medicine list comes from the children's phones (config). Alarms are
 * 100% local, so reminders work with no internet at all; the internet is only
 * used to receive the list and to report back what happened.
 */
object Parent {
    const val GRACE_MIN = 30      // after this, an unanswered dose becomes "missed"
    const val RERING_MIN = 10     // ring again every 10 minutes until answered
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

    /** Revision of the medicine list on this phone (newest wins everywhere). */
    fun cfgRev(c: Context): Long = Prefs.sp(c).getLong("cfg_rev", 0L)

    private fun bumpRev(c: Context) {
        Prefs.sp(c).edit().putLong("cfg_rev", maxOf(Clock.now(), cfgRev(c) + 1)).commit()
    }

    /** Local editing (advanced; normally the children edit remotely). */
    fun upsertMed(c: Context, med: Med) {
        synchronized(lock) {
            val list = meds(c).toMutableList()
            val i = list.indexOfFirst { it.id == med.id }
            if (i >= 0) list[i] = med else list.add(med)
            saveMeds(c, list)
            bumpRev(c)
        }
        scheduleNext(c)
        Sync.now(c)
    }

    fun deleteMed(c: Context, id: Long) {
        synchronized(lock) {
            saveMeds(c, meds(c).filter { it.id != id })
            bumpRev(c)
        }
        scheduleNext(c)
        Sync.now(c)
    }

    /** Applies a medicine list sent by a child's phone if it is newer. */
    fun applyConfig(c: Context, o: JSONObject): Boolean {
        val rev = o.optLong("rev", 0L)
        if (rev <= cfgRev(c)) return false
        val meds = Med.listFrom(o.optJSONArray("meds"))
        synchronized(lock) {
            saveMeds(c, meds)
            Prefs.sp(c).edit().putLong("cfg_rev", rev).commit()
            // Forget unanswered doses that are no longer on the schedule.
            val evs = events(c)
            val keep = evs.filter { e ->
                e.status != PENDING || meds.any { it.active && it.id == e.medId && e.time in it.times }
            }
            if (keep.size != evs.size) saveEvents(c, keep)
        }
        val ringing = AlarmService.ringingSlot
        if (ringing != null) {
            val parts = ringing.split("|")
            if (parts.size == 2 && slotEvents(c, parts[0], parts[1]).none { it.status == PENDING }) {
                AlarmService.stop(c)
            }
        }
        selfHeal(c, ringPending = true)
        return true
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

    /** «خوردم» */
    fun markTaken(c: Context, date: String, time: String) {
        if (date == "test") {
            endTest(c)
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
        finishSlot(c, date, time)
        Sync.now(c)
    }

    /** «نخوردم» — reported to the children right away. */
    fun markSkipped(c: Context, date: String, time: String) {
        if (date == "test") {
            endTest(c)
            return
        }
        synchronized(lock) {
            ensureSlot(c, date, time)
            val evs = events(c)
            val now = Clock.now()
            evs.filter { it.date == date && it.time == time && it.status != TAKEN && it.status != SKIPPED }
                .forEach {
                    it.status = SKIPPED
                    it.skippedAt = now
                }
            saveEvents(c, evs)
        }
        finishSlot(c, date, time)
        Sync.now(c)
    }

    private fun endTest(c: Context) {
        AlarmService.stopFor(c, "test", "test")
        Notify.cancelSlot(c, "test", "test")
    }

    private fun finishSlot(c: Context, date: String, time: String) {
        AlarmService.stopFor(c, date, time)
        Notify.cancelSlot(c, date, time)
        cancelSlotAlarms(c, date, time)
    }

    /** Called [GRACE_MIN] after the dose time if nobody answered. */
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
        AlarmService.stopFor(c, date, time)
        cancelSlotAlarms(c, date, time)
        if (notify) {
            Notify.showReminder(c, date, time, lines, "داروی ساعت ${Fa.time(time)} تأیید نشد — خوردید؟")
        } else {
            Notify.cancelSlot(c, date, time)
        }
        Sync.now(c)
    }

    // ------------------------------------------------------------ alarm flow

    /** Starts ringing. Only call from an exact-alarm callback. */
    fun ring(c: Context, date: String, time: String) {
        val lines = if (date == "test") listOf("این یک آلارم آزمایشی است")
        else slotEvents(c, date, time).filter { it.status == PENDING || it.status == MISSED }.map { it.label() }
        if (lines.isEmpty()) return
        if (!AlarmService.start(c, date, time)) Notify.showDoseAlarm(c, date, time, lines)
    }

    /** The dose time has come. */
    fun onDue(c: Context, date: String, time: String) {
        val evs = ensureSlot(c, date, time)
        if (evs.any { it.status == PENDING }) {
            ring(c, date, time)
            val due = Clock.epoch(date, time)
            setAt(c, due + RERING_MIN * MIN, Notify.slotBroadcast(c, "rering1", date, time))
            setAt(c, due + 2 * RERING_MIN * MIN, Notify.slotBroadcast(c, "rering2", date, time))
            setAt(c, due + GRACE_MIN * MIN, Notify.slotBroadcast(c, "missed", date, time))
        }
        scheduleNext(c)
        Sync.now(c)
    }

    /** Ring again if still not answered. */
    fun onRering(c: Context, date: String, time: String) {
        if (slotEvents(c, date, time).any { it.status == PENDING }) ring(c, date, time)
    }

    private fun cancelSlotAlarms(c: Context, date: String, time: String) {
        val am = c.getSystemService(AlarmManager::class.java)
        for (k in listOf("rering1", "rering2", "ringnow", "missed")) {
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
     * Alarm-clock alarms: exact, wake the phone from deep sleep, and allowed to
     * start the ringing service — the most reliable kind Android offers.
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
     * killed: creates missing events, marks overdue ones as missed, re-arms
     * alarms. With [ringPending], a dose that is due right now rings (through
     * an alarm a few seconds later, which Android lets start the ringing).
     */
    fun selfHeal(c: Context, ringPending: Boolean) {
        val now = Clock.now()
        val today = Clock.today()
        val times = allTimes(c)
        for (date in listOf(Clock.addDays(today, -1), today)) {
            for (t in times) {
                val due = Clock.epoch(date, t)
                if (due > now || now - due > 26 * 60 * MIN) continue
                val evs = ensureSlot(c, date, t)
                if (evs.none { it.status == PENDING }) continue
                if (now >= due + GRACE_MIN * MIN) {
                    markMissedIfPending(c, date, t, notify = date == today)
                } else {
                    setAt(c, due + GRACE_MIN * MIN, Notify.slotBroadcast(c, "missed", date, t))
                    if (ringPending && AlarmService.ringingSlot != "$date|$t") {
                        setAt(c, now + 3_000, Notify.slotBroadcast(c, "ringnow", date, t))
                    }
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
            .put("cfgRev", cfgRev(c))
            .put("meds", meds)
            .put("events", evs)
            .put(
                "diag", JSONObject()
                    .put("notif", Diag.notifOk(c))
                    .put("exact", Diag.exactOk(c))
                    .put("fsi", Diag.fullScreenOk(c))
                    .put("overlay", Diag.overlayOk(c))
                    .put("battery", Diag.batteryOk(c))
                    .put("tz", Diag.timeZoneOk())
            )
            .toString()
    }

    /**
     * One round trip with the brokers: take the newest medicine list the
     * children published, then report this phone's state. Blocking.
     */
    fun sync(c: Context): Boolean {
        val code = Prefs.code(c)
        val tid = Crypto.topicId(code)
        val log = mutableListOf<String>()
        val conns = Cloud.connectAll(log)
        if (conns.isEmpty()) {
            Prefs.recordSync(c, false, log)
            return false
        }
        try {
            val cfgTopic = Cloud.configTopic(tid)
            var best: JSONObject? = null
            for (m in Cloud.readRetainedAll(conns, listOf(cfgTopic), 3_500)) {
                val p = m[cfgTopic] ?: continue
                val s = Crypto.decrypt(code, p) ?: continue
                val o = try {
                    JSONObject(s)
                } catch (t: Throwable) {
                    continue
                }
                val b = best
                if (b == null || o.optLong("rev") > b.optLong("rev")) best = o
            }
            val newest = best
            if (newest != null && applyConfig(c, newest)) log.add("✅ فهرست داروی تازه از فرزند رسید")

            val payload = Crypto.encrypt(code, stateJson(c))
            var ok = 0
            for (conn in conns) {
                if (Cloud.publish(conn, Cloud.stateTopic(tid), payload, log)) ok++
            }
            Prefs.recordSync(c, ok > 0, log)
            return ok > 0
        } finally {
            Cloud.closeAll(conns)
        }
    }
}
