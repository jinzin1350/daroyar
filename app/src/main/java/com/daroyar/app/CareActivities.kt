package com.daroyar.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/** Caregiver's main screen: one card per parent phone. */
class CareHomeActivity : AppCompatActivity() {
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && !Diag.notifOk(this)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (Care.devices(this).isNotEmpty() && Clock.now() - Prefs.syncAttemptAt(this) > 60_000) refresh()
    }

    private fun refresh() {
        if (syncing) return
        syncing = true
        render()
        Ui.background(this, { Care.sync(this) }) {
            syncing = false
            render()
        }
    }

    private fun render() {
        val col = Ui.page(this, "دارویار — گزارش‌ها", back = false)
        val devices = Care.devices(this)

        if (!Diag.notifOk(this) || !Diag.batteryOk(this) || !Diag.exactOk(this)) {
            val warn = Ui.card(this, Ui.RED)
            warn.addView(Ui.text(this, "⚠️ برای گرفتن هشدار سر وقت، تنظیمات را کامل کنید", 17f, Ui.RED, bold = true))
            warn.addView(Ui.button(this, "بررسی تنظیمات", Ui.RED, sp = 18f, height = 52) {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            })
            col.addView(warn)
        }

        if (devices.isEmpty()) {
            col.addView(
                Ui.text(
                    this,
                    "هنوز گوشی مامان یا بابا را اضافه نکرده‌اید.\n" +
                        "روی گوشی آن‌ها: «اتصال به فرزند» ← «فرستادن کد». بعد اینجا کد را وارد کنید.",
                    18f, Ui.MUTED, top = 18
                )
            )
        }

        for (d in devices) {
            val r = Care.report(this, d)
            val silent = r == null || Clock.now() - r.sentAt > 3 * 60 * 60_000L
            val card = Ui.card(this, if (silent) Ui.AMBER else Ui.PRIMARY)
            card.addView(Ui.text(this, "👤 " + d.label, 22f, bold = true, top = 2))
            if (r == null) {
                card.addView(Ui.text(this, "هنوز گزارشی نرسیده. گوشی آن‌ها باید یک بار به اینترنت وصل شود.", 16f, Ui.AMBER))
            } else {
                card.addView(
                    Ui.text(this, "آخرین خبر: " + Fa.ago(r.sentAt), 16f, if (silent) Ui.AMBER else Ui.MUTED, top = 2)
                )
                card.addView(Ui.text(this, "امروز:  " + r.todayLine(), 19f, top = 6))
                val probs = r.diagProblems()
                if (probs.isNotEmpty()) {
                    card.addView(Ui.text(this, "🛠 تنظیمات گوشی ناقص است", 15f, Ui.RED, bold = true))
                }
            }
            card.setOnClickListener {
                startActivity(Intent(this, CareDeviceActivity::class.java).putExtra("topic", d.topicId))
            }
            col.addView(card)
        }

        col.addView(Ui.button(this, "➕ اضافه کردن گوشی مامان/بابا", Ui.GREEN) { addDialog() })
        if (devices.isNotEmpty()) {
            col.addView(Ui.button(this, if (syncing) "در حال دریافت…" else "🔄 به‌روزرسانی", Ui.PRIMARY) { refresh() })
        }

        col.addView(CheckBox(this).apply {
            text = "هر بار دارو خورده شد هم خبرم کن"
            textSize = 17f
            setTextColor(Ui.TEXT)
            isChecked = Prefs.notifyTaken(this@CareHomeActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setNotifyTaken(this@CareHomeActivity, v) }
            setPadding(0, Ui.dp(context, 14), 0, 0)
        })

        col.addView(Ui.button(this, "🛠 بررسی تنظیمات", Ui.GRAY_BTN, Ui.TEXT, sp = 18f, height = 54) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        })

        val ok = Prefs.syncOkAt(this)
        if (ok > 0) {
            col.addView(Ui.text(this, "آخرین دریافت موفق: " + Fa.ago(ok), 14f, Ui.MUTED, center = true, top = 16))
        }
    }

    private fun addDialog() {
        val name = Ui.input(this, "اسم (مثلاً: مامان)", "")
        val code = Ui.input(this, "کد اتصال", "", ltr = true)
        try {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
            val txt = clip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (txt != null) Crypto.extract(txt)?.let { code.setText(Crypto.pretty(it)) }
        } catch (_: Throwable) {
        }
        AlertDialog.Builder(this)
            .setTitle("اضافه کردن گوشی")
            .setMessage("کدی را که از گوشی آن‌ها فرستاده شده اینجا بچسبانید.")
            .setView(Ui.box(this, name, code))
            .setPositiveButton("اضافه کن") { _, _ ->
                val c = Crypto.extract(code.text.toString())
                if (c == null) {
                    Ui.toast(this, "کد درست نیست. باید ۲۰ حرف و عدد باشد.")
                } else {
                    Care.addDevice(this, c, name.text.toString())
                    Ui.toast(this, "اضافه شد. در حال دریافت گزارش…")
                    refresh()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}

/** Details of one parent phone: settings problems, medicines, recent doses. */
class CareDeviceActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        val d = Care.device(this, intent.getStringExtra("topic") ?: "")
        if (d == null) {
            finish()
            return
        }
        val col = Ui.page(this, d.label)
        val r = Care.report(this, d)
        if (r == null) {
            col.addView(Ui.text(this, "هنوز گزارشی از این گوشی نرسیده.", 18f, Ui.MUTED, top = 16))
        } else {
            val info = Ui.card(this)
            info.addView(Ui.text(this, "آخرین خبر: " + Fa.ago(r.sentAt), 17f, top = 2))
            info.addView(Ui.text(this, "ساعت الان آنجا: " + Fa.clock(Clock.now(), r.zone), 17f, Ui.MUTED, top = 2))
            if (r.version.isNotBlank()) info.addView(Ui.text(this, "نسخهٔ برنامه: " + Fa.digits(r.version), 15f, Ui.MUTED, top = 2))
            val probs = r.diagProblems()
            if (probs.isNotEmpty()) {
                info.addView(Ui.text(this, "🛠 تنظیمات ناقص روی گوشی:", 17f, Ui.RED, bold = true))
                info.addView(Ui.text(this, probs.joinToString("\n"), 16f, Ui.RED, top = 2))
            }
            col.addView(info)

            col.addView(Ui.text(this, "داروها", 20f, bold = true, top = 18))
            if (r.meds.isEmpty()) col.addView(Ui.text(this, "دارویی ثبت نشده", 16f, Ui.MUTED))
            for (m in r.meds) {
                col.addView(
                    Ui.text(this, "💊 " + m.label() + "\n     " + m.times.joinToString("، ") { Fa.time(it) }, 17f, top = 6)
                )
            }

            col.addView(Ui.text(this, "روزهای اخیر", 20f, bold = true, top = 20))
            val today = r.today()
            for (k in 0..3) {
                val date = Clock.addDays(today, -k)
                val dayCard = Ui.card(this)
                dayCard.addView(Ui.text(this, if (k == 0) "امروز — " + Fa.day(date, r.zone) else Fa.day(date, r.zone), 17f, Ui.PRIMARY, bold = true, top = 2))
                var any = false
                for (t in r.times()) {
                    val evs = r.events.filter { it.date == date && it.time == t }
                    val sym = r.symbol(date, t)
                    if (sym.isEmpty() && evs.isEmpty()) continue
                    any = true
                    val names = if (evs.isNotEmpty()) evs.joinToString("، ") { it.medName }
                    else r.medsAt(date, t).joinToString("، ") { it.name }
                    val extra = when {
                        evs.isNotEmpty() && evs.all { it.status == TAKEN } ->
                            " (ساعت " + Fa.clock(evs.maxOf { it.takenAt }, r.zone) + ")"
                        else -> ""
                    }
                    dayCard.addView(Ui.text(this, "$sym  ${Fa.time(t)} — $names$extra", 16f, top = 4))
                }
                if (!any) dayCard.addView(Ui.text(this, "—", 16f, Ui.MUTED, top = 4))
                col.addView(dayCard)
            }
            col.addView(
                Ui.text(this, "✅ خورده شد   ❌ تأیید نشد   ⏳ منتظر   ❓ خبری نرسیده   ▫️ هنوز وقتش نشده", 13f, Ui.MUTED, top = 12)
            )
        }

        col.addView(Ui.button(this, "🗑 حذف این گوشی", Ui.GRAY_BTN, Ui.RED, sp = 17f, height = 52) {
            Ui.confirm(this, "حذف", "«${d.label}» از فهرست حذف شود؟", "حذف") {
                Care.removeDevice(this, d)
                finish()
            }
        })
    }
}
