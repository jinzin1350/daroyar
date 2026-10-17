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
    val meds: List<Med> = Med.listFrom(json.optJSONArray("meds"))
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
            if (evs.any { it.status == MISSED }) return "❌"
            return if (now < d + grace * MIN) "⏳" else "❓"
        }
        if (medsAt(date, time).isEmpty()) return ""
        return if (d > now) "▫️" else "❓"
    }

    fun diagProblems(): List<String> {
        val p = mutableListOf<String>()
        if (!diag.optBoolean("notif", true)) p.add("• اجازهٔ اعلان خاموش است (زنگ نمی‌خورد!)")
        if (!diag.optBoolean("exact", true)) p.add("• آلارم دقیق مجاز نیست")
        if (!diag.optBoolean("fsi", true)) p.add("• نمایش تمام‌صفحه مجاز نیست")
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
        Prefs.sp(c).edit().remove("st_${d.topicId}").apply()
    }

    fun report(c: Context, d: Device): Report? {
        val s = Prefs.sp(c).getString("st_${d.topicId}", null) ?: return null
        return try {
            Report(JSONObject(s))
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ sync

    /** Fetches every paired phone's latest report and raises alerts. Blocking. */
    fun sync(c: Context) {
        val ds = devices(c)
        if (ds.isEmpty()) {
            Prefs.recordSync(c, false, listOf("هنوز گوشی‌ای اضافه نشده"))
            return
        }
        val log = mutableListOf<String>()
        val res = Cloud.fetchRetained(ds.map { Cloud.stateTopic(it.topicId) }, log)
        for (d in ds) {
            val prev = report(c, d)
            var best: JSONObject? = null
            for (p in res.messages[Cloud.stateTopic(d.topicId)].orEmpty()) {
                val s = Crypto.decrypt(d.code, p) ?: continue
                val o = try {
                    JSONObject(s)
                } catch (t: Throwable) {
                    continue
                }
                if (best == null || o.optLong("sentAt") > best.optLong("sentAt")) best = o
            }
            if (best != null && (prev == null || best.optLong("sentAt") > prev.sentAt)) {
                Prefs.sp(c).edit().putString("st_${d.topicId}", best.toString()).commit()
            }
            // Only judge silence when OUR side reached a broker.
            if (res.hostsReached > 0) evaluate(c, d, prev, report(c, d))
        }
        Prefs.recordSync(c, res.hostsReached > 0, log)
        scheduleCheck(c)
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

        // 1) doses the parent phone reported as missed / taken
        for ((slot, evs) in cur.events.groupBy { it.slot }) {
            val first = evs.first()
            if (first.due < d.pairedAt) continue
            val t = Fa.time(first.time)
            val missed = evs.filter { it.status == MISSED }
            if (missed.isNotEmpty()) {
                fire(
                    "miss|${d.topicId}|$slot",
                    "⚠️ $name: داروی ساعت $t تأیید نشد",
                    missed.joinToString("\n") { "• " + it.label() } +
                        "\nتا ${Fa.digits("${cur.grace}")} دقیقه بعد از زنگ، «خوردم» زده نشد."
                )
            }
            if (evs.all { it.status == TAKEN }) {
                val takenAt = evs.maxOf { it.takenAt }
                val wasAlerted = alerted.contains("miss|${d.topicId}|$slot") ||
                    alerted.contains("none|${d.topicId}|$slot")
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
