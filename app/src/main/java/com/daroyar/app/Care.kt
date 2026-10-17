package com.daroyar.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** A parent phone paired with this caregiver phone. */
data class Device(val code: String, val label: String, val pairedAt: Long) {
    val topicId: String get() = Crypto.topicId(code)

    fun toJson(): JSONObject = JSONObject().put("code", code).put("label", label).put("at", pairedAt)

    companion object {
        fun fromJson(o: JSONObject) =
            Device(o.optString("code"), o.optString("label"), o.optLong("at"))
    }
}

/** The last report received from a parent phone. */
class Report(val json: JSONObject) {
    private val MIN = 60_000L
    val sentAt: Long = json.optLong("sentAt")
    val zone: TimeZone = Clock.zoneOfOffset(json.optInt("tzOff", 210))
    val grace: Int = json.optInt("grace", Parent.GRACE_MIN)
    val version: String = json.optString("ver")
    val cfgRev: Long = json.optLong("cfgRev", 0L)
    val medsJson: JSONArray = json.optJSONArray("meds") ?: JSONArray()
    val meds: List<Med> = Med.listFrom(medsJson)
    val events: List<DoseEvent> = DoseEvent.listFrom(json.optJSONArray("events"))
    private val diag: JSONObject = json.optJSONObject("diag") ?: JSONObject()

    fun today(): String = Clock.today(zone)
    fun times(): List<String> = meds.filter { it.active }.flatMap { it.times }.distinct().sorted()
    fun due(date: String, time: String): Long = Clock.epoch(date, time, zone)

    fun medsAt(date: String, time: String): List<Med> {
        val d = due(date, time)
        return meds.filter { it.active && time in it.times && d >= it.since - MIN }
    }

    /** One symbol per dose slot for display. */
    fun symbol(date: String, time: String): String {
        val now = Clock.now()
        val evs = events.filter { it.date == date && it.time == time }
        val d = due(date, time)
        if (evs.isNotEmpty()) {
            if (evs.all { it.status == TAKEN }) return "✅"
            if (evs.any { it.status == SKIPPED }) return "❌"
            if (evs.any { it.status == MISSED }) return "⚠️"
            return if (now < d + grace * MIN) "⏳" else "❓"
        }
        if (medsAt(date, time).isEmpty()) return ""
        return if (d > now) "▫️" else "❓"
    }

    fun diagProblems(): List<String> {
        val p = mutableListOf<String>()
        if (!diag.optBoolean("notif", true)) p.add("• اجازهٔ اعلان خاموش است")
        if (!diag.optBoolean("exact", true)) p.add("• آلارم دقیق مجاز نیست")
        if (!diag.optBoolean("fsi", true)) p.add("• نمایش تمام‌صفحه مجاز نیست")
        if (!diag.optBoolean("overlay", true)) p.add("• «نمایش روی برنامه‌های دیگر» مجاز نیست")
        if (!diag.optBoolean("battery", true)) p.add("• بهینه‌سازی باتری روی برنامه فعال است")
        if (!diag.optBoolean("tz", true)) p.add("• ساعت یا منطقهٔ زمانی گوشی مشکل دارد")
        return p
    }

    fun todayLine(): String {
        val today = today()
        val parts = times().mapNotNull { t ->
            val s = symbol(today, t)
            if (s.isEmpty()) null else "${Fa.time(t)} $s"
        }
        return if (parts.isEmpty()) "امروز دارویی ثبت نشده" else parts.joinToString("   ")
    }
}

/** Everything that happens on the caregiver's phone. */
object Care {
    private const val MIN = 60_000L

    // ------------------------------------------------------------ devices

    fun devices(c: Context): List<Device> {
        val s = Prefs.sp(c).getString("devices", "[]") ?: "[]"
        return try {
            val arr = JSONArray(s)
            List(arr.length()) { Device.fromJson(arr.getJSONObject(it)) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun saveDevices(c: Context, list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Prefs.sp(c).edit().putString("devices", arr.toString()).commit()
    }

    fun device(c: Context, topicId: String): Device? = devices(c).firstOrNull { it.topicId == topicId }

    fun addDevice(c: Context, code: String, label: String): Device {
        val norm = Crypto.normalize(code)
        val list = devices(c).filter { it.code != norm }.toMutableList()
        val d = Device(norm, label.trim().ifEmpty { "مامان" }, Clock.now())
        list.add(d)
        saveDevices(c, list)
        return d
    }

    fun removeDevice(c: Context, d: Device) {
        saveDevices(c, devices(c).filter { it.code != d.code })
        Prefs.sp(c).edit().remove("st_${d.topicId}").remove("cfg_${d.topicId}").apply()
    }

    fun report(c: Context, d: Device): Report? {
        val s = Prefs.sp(c).getString("st_${d.topicId}", null) ?: return null
        return try {
            Report(JSONObject(s))
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ medicine list (config)

    /** The newest medicine list this phone knows for [d] ({rev, meds}). */
    fun localConfig(c: Context, d: Device): JSONObject? {
        val s = Prefs.sp(c).getString("cfg_${d.topicId}", null) ?: return null
        return try {
            JSONObject(s)
        } catch (t: Throwable) {
            null
        }
    }

    private fun saveLocalConfig(c: Context, d: Device, o: JSONObject) {
        Prefs.sp(c).edit().putString("cfg_${d.topicId}", o.toString()).commit()
    }

    private fun rev(o: JSONObject?): Long = o?.optLong("rev", -1L) ?: -1L

    /** Medicines to show and edit: newest of what we sent and what the phone reports. */
    fun meds(c: Context, d: Device): List<Med> {
        val local = localConfig(c, d)
        val r = report(c, d)
        if (r != null && r.cfgRev > rev(local)) return r.meds
        return Med.listFrom(local?.optJSONArray("meds"))
    }

    /** True while our latest changes have not reached the parent phone yet. */
    fun pendingDelivery(c: Context, d: Device): Boolean {
        val local = localConfig(c, d) ?: return false
        val r = report(c, d) ?: return true
        return r.cfgRev < rev(local)
    }

    private fun writeMeds(c: Context, d: Device, meds: List<Med>) {
        val arr = JSONArray()
        meds.forEach { arr.put(it.toJson()) }
        val newRev = maxOf(Clock.now(), rev(localConfig(c, d)) + 1, (report(c, d)?.cfgRev ?: 0L) + 1)
        saveLocalConfig(c, d, JSONObject().put("v", 1).put("rev", newRev).put("meds", arr))
    }

    fun upsertMed(c: Context, d: Device, med: Med) {
        val list = meds(c, d).toMutableList()
        val i = list.indexOfFirst { it.id == med.id }
        if (i >= 0) list[i] = med else list.add(med)
        writeMeds(c, d, list)
        Sync.now(c)
    }

    fun deleteMed(c: Context, d: Device, id: Long) {
        writeMeds(c, d, meds(c, d).filter { it.id != id })
        Sync.now(c)
    }

    // ------------------------------------------------------------ sync

    /**
     * One round trip: read every paired phone's report and medicine list from
     * every reachable broker, push our newer medicine lists where a broker has
     * an older one (other children may edit too: newest revision wins), and
     * raise alerts. Blocking.
     */
    fun sync(c: Context) {
        val ds = devices(c)
        if (ds.isEmpty()) {
            Prefs.recordSync(c, false, listOf("هنوز گوشی‌ای اضافه نشده"))
            return
        }
        val log = mutableListOf<String>()
        val conns = Cloud.connectAll(log)
        try {
            val topics = ds.flatMap { listOf(Cloud.stateTopic(it.topicId), Cloud.configTopic(it.topicId)) }
            val got = Cloud.readRetainedAll(conns, topics, 4_000)
            for (d in ds) {
                val stTopic = Cloud.stateTopic(d.topicId)
                val cfgTopic = Cloud.configTopic(d.topicId)
                val prev = report(c, d)

                // newest report
                var best: JSONObject? = null
                for (m in got) {
                    val o = open(d, m[stTopic]) ?: continue
                    val b = best
                    if (b == null || o.optLong("sentAt") > b.optLong("sentAt")) best = o
                }
                val fresh = best
                if (fresh != null && (prev == null || fresh.optLong("sentAt") > prev.sentAt)) {
                    Prefs.sp(c).edit().putString("st_${d.topicId}", fresh.toString()).commit()
                }

                // newest medicine list among: ours, the brokers', the phone's own
                val remote = got.map { m -> open(d, m[cfgTopic]) }
                var newest: JSONObject? = localConfig(c, d)
                for (o in remote) {
                    if (o != null && rev(o) > rev(newest)) newest = o
                }
                val rep = report(c, d)
                if (rep != null && rep.cfgRev > 0 && rep.cfgRev > rev(newest)) {
                    newest = JSONObject().put("v", 1).put("rev", rep.cfgRev).put("meds", rep.medsJson)
                }
                val chosen = newest
                if (chosen != null) {
                    if (rev(chosen) > rev(localConfig(c, d))) saveLocalConfig(c, d, chosen)
                    val payload = Crypto.encrypt(d.code, chosen.toString())
                    for (i in conns.indices) {
                        if (rev(remote.getOrNull(i)) < rev(chosen)) {
                            Cloud.publish(conns[i], cfgTopic, payload, log)
                        }
                    }
                }

                // Only judge silence when OUR side reached a broker.
                if (conns.isNotEmpty()) evaluate(c, d, prev, report(c, d))
            }
            Prefs.recordSync(c, conns.isNotEmpty(), log)
        } finally {
            Cloud.closeAll(conns)
        }
        scheduleCheck(c)
    }

    private fun open(d: Device, payload: ByteArray?): JSONObject? {
        if (payload == null) return null
        val s = Crypto.decrypt(d.code, payload) ?: return null
        return try {
            JSONObject(s)
        } catch (t: Throwable) {
            null
        }
    }

    private fun alertedSet(c: Context): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val arr = JSONArray(Prefs.sp(c).getString("alerted", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (_: Throwable) {
        }
        return out
    }

    private fun saveAlerted(c: Context, set: LinkedHashSet<String>) {
        val keep = set.toList().takeLast(500)
        Prefs.sp(c).edit().putString("alerted", JSONArray(keep).toString()).apply()
    }

    private fun evaluate(c: Context, d: Device, prev: Report?, cur: Report?) {
        if (cur == null) return
        val now = Clock.now()
        val name = d.label
        val alerted = alertedSet(c)
        fun fire(key: String, title: String, text: String) {
            if (alerted.add(key)) Notify.care(c, key, title, text)
        }

        // 1) answers and missed doses reported by the parent phone
        for ((slot, evs) in cur.events.groupBy { it.slot }) {
            val first = evs.first()
            if (first.due < d.pairedAt) continue
            val t = Fa.time(first.time)
            val skipped = evs.filter { it.status == SKIPPED }
            if (skipped.isNotEmpty()) {
                fire(
                    "skip|${d.topicId}|$slot",
                    "❌ $name زد «نخوردم»",
                    "داروی ساعت $t:\n" + skipped.joinToString("\n") { "• " + it.label() } +
                        "\n(جواب داده شد ساعت ${Fa.clock(skipped.maxOf { it.skippedAt }, cur.zone)})"
                )
            }
            val missed = evs.filter { it.status == MISSED }
            if (missed.isNotEmpty()) {
                fire(
                    "miss|${d.topicId}|$slot",
                    "⚠️ $name: داروی ساعت $t بی‌جواب ماند",
                    missed.joinToString("\n") { "• " + it.label() } +
                        "\nتا ${Fa.digits("${cur.grace}")} دقیقه بعد از زنگ، هیچ دکمه‌ای زده نشد."
                )
            }
            if (evs.all { it.status == TAKEN }) {
                val takenAt = evs.maxOf { it.takenAt }
                val wasAlerted = alerted.contains("miss|${d.topicId}|$slot") ||
                    alerted.contains("none|${d.topicId}|$slot") ||
                    alerted.contains("skip|${d.topicId}|$slot")
                if (wasAlerted) {
                    fire(
                        "late|${d.topicId}|$slot",
                        "✅ $name: داروی ساعت $t خورده شد",
                        "دیرتر تأیید شد، ساعت ${Fa.clock(takenAt, cur.zone)}."
                    )
                } else if (Prefs.notifyTaken(c) && now - takenAt < 12 * 60 * MIN) {
                    fire(
                        "ok|${d.topicId}|$slot",
                        "✅ $name داروی ساعت $t را خورد",
                        evs.joinToString("\n") { "• " + it.label() }
                    )
                }
            }
        }

        // 2) dead-man switch: a dose is overdue and nothing at all is known about it
        val today = cur.today()
        for (date in listOf(Clock.addDays(today, -1), today)) {
            for (time in cur.times()) {
                val due = cur.due(date, time)
                if (due < d.pairedAt || due > now) continue
                if (now < due + (cur.grace + 30) * MIN || now - due > 20 * 60 * MIN) continue
                if (cur.medsAt(date, time).isEmpty()) continue
                val evs = cur.events.filter { it.date == date && it.time == time }
                if (evs.isNotEmpty() && evs.none { it.status == PENDING }) continue
                fire(
                    "none|${d.topicId}|${date}T$time",
                    "❓ $name: از گوشی خبری نرسیده",
                    "داروی ساعت ${Fa.time(time)} هنوز تأیید نشده و آخرین خبر از گوشی ${Fa.ago(cur.sentAt)} بوده. " +
                        "ممکن است گوشی خاموش باشد یا اینترنت قطع باشد."
                )
            }
        }

        // 3) long silence
        val silentHours = (now - cur.sentAt) / (60 * MIN)
        if (silentHours >= 6) {
            fire(
                "silent|${d.topicId}|${cur.sentAt}",
                "📵 $name: ${Fa.digits("$silentHours")} ساعت است خبری نیست",
                "ممکن است گوشی خاموش باشد، شارژش تمام شده باشد یا اینترنت قطع باشد. " +
                    "زنگ دارو روی خود گوشی بدون اینترنت هم کار می‌کند."
            )
        }

        // 4) back online after a long gap
        if (prev != null && cur.sentAt - prev.sentAt >= 6 * 60 * MIN) {
            fire("back|${d.topicId}|${cur.sentAt}", "🔄 $name دوباره وصل شد", "امروز: " + cur.todayLine())
        }

        // 5) settings on the parent phone that will break the alarm
        val problems = cur.diagProblems()
        if (problems.isNotEmpty()) {
            fire(
                "diag|${d.topicId}|${problems.joinToString("")}",
                "🛠 $name: تنظیمات گوشی ناقص است",
                problems.joinToString("\n") + "\nروی گوشی، برنامه را باز کنند و «بررسی تنظیمات» را بزنند."
            )
        }
        saveAlerted(c, alerted)
    }

    // ------------------------------------------------------------ timely checks

    private fun checkIntent(c: Context): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION)
            .setData(AlarmReceiver.uri("care", null, null))
        return PendingIntent.getBroadcast(
            c, 3, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Wakes this phone right when a dose would be judged (missed, or no news). */
    fun scheduleCheck(c: Context) {
        if (Prefs.role(c) != Prefs.CARE) return
        val now = Clock.now()
        var next = Long.MAX_VALUE
        for (d in devices(c)) {
            val r = report(c, d) ?: continue
            val today = r.today()
            for (date in listOf(today, Clock.addDays(today, 1))) {
                for (t in r.times()) {
                    val due = r.due(date, t)
                    for (cand in longArrayOf(due + (r.grace + 2) * MIN, due + (r.grace + 32) * MIN)) {
                        if (cand > now + 30_000 && cand < next) next = cand
                    }
                }
            }
        }
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = checkIntent(c)
        if (next == Long.MAX_VALUE) {
            am.cancel(pi)
            return
        }
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                return
            }
        } catch (_: SecurityException) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }
}
