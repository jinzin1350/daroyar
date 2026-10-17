package com.daroyar.app

import org.json.JSONArray
import org.json.JSONObject

const val PENDING = "pending"
const val TAKEN = "taken"   // "خوردم"
const val SKIPPED = "skipped" // "نخوردم" (answered no)
const val MISSED = "missed"   // nobody answered within the grace period

/**
 * A medicine taken every day at [times] ("HH:mm").
 * [since]: when the schedule last changed; earlier dose slots never count.
 */
data class Med(
    val id: Long,
    val name: String,
    val dose: String,
    val times: List<String>,
    val since: Long,
    val active: Boolean = true
) {
    fun label(): String = if (dose.isBlank()) name else "$name — $dose"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("dose", dose)
        .put("times", JSONArray(times))
        .put("since", since)
        .put("active", active)

    companion object {
        fun fromJson(o: JSONObject): Med {
            val ta = o.optJSONArray("times") ?: JSONArray()
            return Med(
                id = o.optLong("id"),
                name = o.optString("name"),
                dose = o.optString("dose"),
                times = List(ta.length()) { ta.getString(it) }.sorted(),
                since = o.optLong("since", 0L),
                active = o.optBoolean("active", true)
            )
        }

        fun listFrom(arr: JSONArray?): List<Med> {
            if (arr == null) return emptyList()
            return List(arr.length()) { fromJson(arr.getJSONObject(it)) }
        }
    }
}

/** One medicine at one dose slot (date + time). */
class DoseEvent(
    val medId: Long,
    val medName: String,
    val dose: String,
    val date: String,
    val time: String,
    val due: Long,
    var status: String,
    var takenAt: Long = 0L,
    var missedAt: Long = 0L,
    var skippedAt: Long = 0L
) {
    val slot: String get() = "${date}T$time"

    fun label(): String = if (dose.isBlank()) medName else "$medName — $dose"

    fun toJson(): JSONObject = JSONObject()
        .put("m", medId)
        .put("n", medName)
        .put("q", dose)
        .put("d", date)
        .put("t", time)
        .put("due", due)
        .put("s", status)
        .put("ta", takenAt)
        .put("ma", missedAt)
        .put("sa", skippedAt)

    companion object {
        fun fromJson(o: JSONObject) = DoseEvent(
            medId = o.optLong("m"),
            medName = o.optString("n"),
            dose = o.optString("q"),
            date = o.optString("d"),
            time = o.optString("t"),
            due = o.optLong("due"),
            status = o.optString("s", PENDING),
            takenAt = o.optLong("ta"),
            missedAt = o.optLong("ma"),
            skippedAt = o.optLong("sa")
        )

        fun listFrom(arr: JSONArray?): MutableList<DoseEvent> {
            if (arr == null) return mutableListOf()
            return MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
        }
    }
}
