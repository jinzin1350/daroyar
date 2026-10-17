package com.daroyar.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Parents' screen. They never type anything: the children send the medicine
 * list. Here they only see today's doses and answer «خوردم» / «نخوردم».
 */
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
        // Opening the app also fetches any new medicine list from the children.
        if (Clock.now() - Prefs.syncAttemptAt(this) > 2 * 60_000L) Sync.now(this)
    }

    private fun render() {
        val col = Ui.page(this, "دارویار", back = false)
        val now = Clock.now()

        if (!Diag.allOkParent(this)) {
            val warn = Ui.card(this, Ui.RED)
            warn.addView(Ui.text(this, "⚠️ چند تنظیم لازم انجام نشده", 19f, Ui.RED, bold = true))
            warn.addView(Ui.text(this, "بدون این تنظیم‌ها ممکن است زنگ دارو نخورد.", 16f, Ui.MUTED))
            warn.addView(Ui.button(this, "درستش کن", Ui.RED) { open(DiagnosticsActivity::class.java) })
            col.addView(warn)
        }

        if (Parent.meds(this).isEmpty()) {
            col.addView(waitingCard())
        } else {
            col.addView(Ui.text(this, "امروز، " + Fa.date(now), 22f, bold = true, top = 16))
            val today = Clock.today()
            var shown = 0
            for (t in Parent.allTimes(this)) {
                val meds = Parent.medsAt(this, today, t)
                val evs = Parent.slotEvents(this, today, t)
                if (meds.isEmpty() && evs.isEmpty()) continue
                shown++
                col.addView(slotCard(today, t, meds, evs, now))
            }
            if (shown == 0) col.addView(Ui.text(this, "امروز دارویی ندارید 🌿", 19f, Ui.MUTED, top = 12))
        }

        col.addView(Ui.text(this, "", 8f, top = 8))
        col.addView(Ui.button(this, "🔗 اتصال به فرزند", Ui.PRIMARY, sp = 18f, height = 54) { open(PairActivity::class.java) })
        col.addView(Ui.button(this, "🛠 بررسی تنظیمات", Ui.GRAY_BTN, Ui.TEXT, sp = 18f, height = 54) {
            open(DiagnosticsActivity::class.java)
        })

        val ok = Prefs.syncOkAt(this)
        col.addView(
            Ui.text(
                this,
                if (ok > 0) "آخرین ارتباط با فرزند: " + Fa.ago(ok) else "هنوز با فرزند ارتباط برقرار نشده",
                15f, Ui.MUTED, center = true, top = 18
            )
        )
    }

    /** First run: nothing to do but send the code to a child. */
    private fun waitingCard(): LinearLayout {
        val code = Crypto.pretty(Prefs.code(this))
        val card = Ui.card(this, Ui.PRIMARY)
        card.addView(Ui.text(this, "💊 هنوز دارویی ثبت نشده", 21f, bold = true, top = 4))
        card.addView(
            Ui.text(
                this,
                "لازم نیست چیزی وارد کنید. فرزندتان داروها را از گوشی خودش وارد می‌کند و اینجا خودکار تنظیم می‌شود.\n" +
                    "اگر هنوز وصل نشده‌اید، این کد را برایش بفرستید:",
                17f, Ui.MUTED
            )
        )
        card.addView(Ui.text(this, code, 22f, Ui.TEXT, bold = true, center = true, top = 12).apply {
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
        })
        card.addView(Ui.button(this, "📤 فرستادن کد برای فرزند", Ui.GREEN) { PairActivity.shareCode(this) })
        return card
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
        val skipped = evs.any { it.status == SKIPPED }
        val missed = evs.any { it.status == MISSED }
        val open = evs.any { it.status == PENDING || it.status == MISSED }
        val border = when {
            allTaken -> Ui.GREEN
            skipped || missed -> Ui.RED
            due <= now -> Ui.AMBER
            else -> Ui.LINE
        }
        val card = Ui.card(this, border)
        card.addView(Ui.text(this, "⏰ ساعت " + Fa.time(t), 24f, bold = true, top = 4))
        val labels = if (evs.isNotEmpty()) evs.map { it.label() } else meds.map { it.label() }
        labels.forEach { card.addView(Ui.text(this, "• $it", 20f, top = 4)) }

        when {
            allTaken -> {
                val at = evs.maxOf { it.takenAt }
                card.addView(Ui.text(this, "✅ خوردید — ساعت " + Fa.clock(at), 18f, Ui.GREEN, bold = true))
            }
            skipped && !open -> {
                card.addView(Ui.text(this, "❌ زدید «نخوردم»", 18f, Ui.RED, bold = true))
                card.addView(Ui.button(this, "✅ الان خوردم", Ui.GREEN, height = 62) { answer(today, t, true) })
            }
            open || due <= now -> {
                card.addView(
                    Ui.text(
                        this,
                        if (missed) "⚠️ جواب داده نشد. خوردید؟" else "⏳ وقتش رسیده",
                        18f, if (missed) Ui.RED else Ui.AMBER, bold = true
                    )
                )
                card.addView(
                    Ui.row(
                        this,
                        Ui.button(this, "✅ خوردم", Ui.GREEN, sp = 22f, height = 70) { answer(today, t, true) },
                        Ui.button(this, "❌ نخوردم", Color.parseColor("#8E2A2A"), sp = 20f, height = 70) {
                            answer(today, t, false)
                        }
                    )
                )
            }
            else -> card.addView(Ui.text(this, "هنوز وقتش نشده — سر ساعت زنگ می‌زند", 16f, Ui.MUTED))
        }
        return card
    }

    private fun answer(date: String, time: String, taken: Boolean) {
        if (taken) Parent.markTaken(this, date, time) else Parent.markSkipped(this, date, time)
        Ui.toast(this, "ثبت شد ✅")
        render()
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))
}
