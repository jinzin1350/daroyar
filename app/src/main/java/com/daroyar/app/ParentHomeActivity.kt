package com.daroyar.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/** Parents' main screen: today's doses, big "I took it" buttons. */
class ParentHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && !Diag.notifOk(this)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val col = Ui.page(this, "دارویار — " + Prefs.deviceName(this), back = false)
        val now = Clock.now()

        if (!Diag.allOkParent(this)) {
            val warn = Ui.card(this, Ui.RED)
            warn.addView(Ui.text(this, "⚠️ چند تنظیم لازم انجام نشده", 19f, Ui.RED, bold = true))
            warn.addView(Ui.text(this, "بدون این تنظیم‌ها ممکن است زنگ دارو نخورد.", 16f, Ui.MUTED))
            warn.addView(Ui.button(this, "درستش کن", Ui.RED) { open(DiagnosticsActivity::class.java) })
            col.addView(warn)
        }

        col.addView(Ui.text(this, "امروز، " + Fa.date(now), 22f, bold = true, top = 16))

        val today = Clock.today()
        val times = Parent.allTimes(this)
        var shown = 0
        for (t in times) {
            val meds = Parent.medsAt(this, today, t)
            val evs = Parent.slotEvents(this, today, t)
            if (meds.isEmpty() && evs.isEmpty()) continue
            shown++
            col.addView(slotCard(today, t, meds, evs, now))
        }
        if (shown == 0) {
            val empty = Ui.card(this)
            empty.addView(Ui.text(this, "هنوز دارویی برای امروز ثبت نشده.", 18f, Ui.MUTED))
            empty.addView(Ui.button(this, "➕ افزودن دارو", Ui.GREEN) {
                startActivity(Intent(this, MedEditActivity::class.java))
            })
            col.addView(empty)
        }

        col.addView(Ui.text(this, "", 8f, top = 8))
        col.addView(Ui.button(this, "💊 داروهای من", Ui.PRIMARY) { open(MedListActivity::class.java) })
        col.addView(Ui.button(this, "🔗 اتصال به فرزند", Ui.PRIMARY) { open(PairActivity::class.java) })
        col.addView(Ui.button(this, "🛠 بررسی تنظیمات", Ui.GRAY_BTN, Ui.TEXT) {
            open(DiagnosticsActivity::class.java)
        })

        val ok = Prefs.syncOkAt(this)
        col.addView(
            Ui.text(
                this,
                if (ok > 0) "آخرین ارسال گزارش به فرزند: " + Fa.ago(ok) else "هنوز گزارشی به فرزند ارسال نشده",
                15f, Ui.MUTED, center = true, top = 18
            )
        )
    }

    private fun slotCard(
        today: String,
        t: String,
        meds: List<Med>,
        evs: List<DoseEvent>,
        now: Long
    ): LinearLayout {
        val due = Clock.epoch(today, t)
        val allTaken = evs.isNotEmpty() && evs.all { it.status == TAKEN }
        val anyMissed = evs.any { it.status == MISSED }
        val border = when {
            allTaken -> Ui.GREEN
            anyMissed -> Ui.RED
            due <= now -> Ui.AMBER
            else -> Ui.LINE
        }
        val card = Ui.card(this, border)
        card.addView(Ui.text(this, "⏰ ساعت " + Fa.time(t), 24f, bold = true, top = 4))
        val labels = if (evs.isNotEmpty()) evs.map { it.label() } else meds.map { it.label() }
        labels.forEach { card.addView(Ui.text(this, "• $it", 19f, top = 4)) }

        when {
            allTaken -> {
                val at = evs.maxOf { it.takenAt }
                card.addView(Ui.text(this, "✅ خورده شد — ساعت " + Fa.clock(at), 18f, Ui.GREEN, bold = true))
            }
            anyMissed -> {
                card.addView(Ui.text(this, "❌ تأیید نشد. اگر خوردید، دکمه را بزنید.", 17f, Ui.RED, bold = true))
                card.addView(Ui.button(this, "✅ خوردم", Ui.GREEN, height = 64) { taken(today, t) })
            }
            due <= now -> {
                card.addView(Ui.text(this, "⏳ وقتش رسیده", 18f, Ui.AMBER, bold = true))
                card.addView(Ui.button(this, "✅ خوردم", Ui.GREEN, height = 64) { taken(today, t) })
            }
            due - now <= 2 * 60 * 60_000L -> {
                card.addView(Ui.text(this, "به‌زودی", 16f, Ui.MUTED))
                card.addView(Ui.button(this, "زودتر خوردم", Ui.GRAY_BTN, Ui.TEXT, sp = 17f, height = 52) {
                    taken(today, t)
                })
            }
            else -> card.addView(Ui.text(this, "هنوز وقتش نشده", 16f, Ui.MUTED))
        }
        return card
    }

    private fun taken(date: String, time: String) {
        Parent.markTaken(this, date, time)
        Ui.toast(this, "ثبت شد ✅")
        render()
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))
}
