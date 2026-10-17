package com.daroyar.app

import android.content.Context
import android.content.SharedPreferences

/** Small key/value settings shared by both roles. */
object Prefs {
    const val PARENT = "parent"
    const val CARE = "care"

    fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("daroyar", Context.MODE_PRIVATE)

    fun role(c: Context): String? = sp(c).getString("role", null)

    fun setRole(c: Context, role: String?) {
        sp(c).edit().putString("role", role).commit()
    }

    fun deviceName(c: Context): String = sp(c).getString("name", null) ?: "مامان"

    fun setDeviceName(c: Context, name: String) {
        sp(c).edit().putString("name", name.trim().ifEmpty { "مامان" }).commit()
    }

    /** Pairing code of this (parent) phone. Created once. */
    fun code(c: Context): String {
        val existing = sp(c).getString("code", null)
        if (existing != null && Crypto.isValid(existing)) return Crypto.normalize(existing)
        val fresh = Crypto.newCode()
        sp(c).edit().putString("code", fresh).commit()
        return fresh
    }

    fun newCode(c: Context): String {
        val fresh = Crypto.newCode()
        sp(c).edit().putString("code", fresh).commit()
        return fresh
    }

    // ---- sync bookkeeping (both roles) ----
    fun syncAttemptAt(c: Context) = sp(c).getLong("sync_try", 0L)
    fun syncOkAt(c: Context) = sp(c).getLong("sync_ok", 0L)
    fun syncLog(c: Context) = sp(c).getString("sync_log", "") ?: ""

    fun recordSync(c: Context, ok: Boolean, log: List<String>) {
        val now = System.currentTimeMillis()
        val e = sp(c).edit().putLong("sync_try", now).putString("sync_log", log.joinToString("\n"))
        if (ok) e.putLong("sync_ok", now)
        e.apply()
    }

    // ---- caregiver options ----
    fun notifyTaken(c: Context) = sp(c).getBoolean("notify_taken", false)

    fun setNotifyTaken(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("notify_taken", v).apply()
    }
}
