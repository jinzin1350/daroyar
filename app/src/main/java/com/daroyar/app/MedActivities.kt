package com.daroyar.app

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** List of the parent's medicines. */
class MedListActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        val col = Ui.page(this, "داروهای من")
        col.addView(Ui.button(this, "➕ افزودن داروی جدید", Ui.GREEN) {
            startActivity(Intent(this, MedEditActivity::class.java))
        })
        val meds = Parent.meds(this)
        if (meds.isEmpty()) {
            col.addView(Ui.text(this, "هنوز دارویی اضافه نشده.", 18f, Ui.MUTED, top = 20))
        }
        for (m in meds) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, "💊 " + m.name, 21f, bold = true, top = 2))
            if (m.dose.isNotBlank()) card.addView(Ui.text(this, "مقدار: " + Fa.digits(m.dose), 17f, Ui.MUTED, top = 2))
            card.addView(
                Ui.text(this, "ساعت‌ها: " + m.times.joinToString("، ") { Fa.time(it) }, 18f, top = 4)
            )
            card.addView(Ui.button(this, "✏️ ویرایش", Ui.GRAY_BTN, Ui.TEXT, sp = 17f, height = 50) {
                startActivity(Intent(this, MedEditActivity::class.java).putExtra("id", m.id))
            })
            col.addView(card)
        }
    }
}

/**
 * Add or edit one medicine and its daily times.
 * With extra "topic" it edits the list of a paired parent phone (caregiver
 * side, sent over the internet); without it, the list on this phone.
 */
class MedEditActivity : AppCompatActivity() {
    private var editing: Med? = null
    private var device: Device? = null
    private val times = mutableListOf<String>()
    private lateinit var timesBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra("topic")?.let { Care.device(this, it) }
        val dev = device
        val id = intent.getLongExtra("id", -1L)
        editing = when {
            id < 0 -> null
            dev != null -> Care.meds(this, dev).firstOrNull { it.id == id }
            else -> Parent.med(this, id)
        }
        editing?.let { times.addAll(it.times) }
        if (savedInstanceState != null) {
            times.clear()
            times.addAll(savedInstanceState.getStringArrayList("times") ?: arrayListOf())
        }

        val who = dev?.label
        val col = Ui.page(
            this,
            when {
                editing != null -> "ویرایش دارو"
                who != null -> "داروی جدید برای $who"
                else -> "داروی جدید"
            }
        )
        col.addView(Ui.text(this, "اسم دارو", 18f, bold = true, top = 14))
        val name = Ui.input(this, "مثلاً: قرص فشار", editing?.name ?: "")
        col.addView(name)
        col.addView(Ui.text(this, "مقدار (اختیاری)", 18f, bold = true, top = 14))
        val dose = Ui.input(this, "مثلاً: یک عدد", editing?.dose ?: "")
        col.addView(dose)

        col.addView(Ui.text(this, "ساعت‌های خوردن (هر روز)", 18f, bold = true, top = 18))
        if (dev != null) {
            val zoneNote = Care.report(this, dev)?.let { "ساعت الان آنجا: " + Fa.clock(Clock.now(), it.zone) } ?: ""
            col.addView(
                Ui.text(this, "به وقت گوشی $who (تهران)، نه وقت شما. $zoneNote", 15f, Ui.AMBER, top = 2)
            )
        }
        timesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(timesBox)
        col.addView(Ui.button(this, "⏰ افزودن ساعت", Ui.PRIMARY) { pickTime() })
        renderTimes()

        col.addView(Ui.text(this, "", 6f))
        col.addView(Ui.button(this, "💾 ذخیره", Ui.GREEN, height = 66) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) {
                Ui.toast(this, "اسم دارو را بنویسید")
                return@button
            }
            if (times.isEmpty()) {
                Ui.toast(this, "حداقل یک ساعت اضافه کنید")
                return@button
            }
            val old = editing
            val sorted = times.distinct().sorted()
            val timesChanged = old == null || old.times != sorted
            val med = Med(
                id = old?.id ?: Clock.now(),
                name = n,
                dose = dose.text.toString().trim(),
                times = sorted,
                since = if (timesChanged) Clock.now() else old!!.since,
                active = true
            )
            if (dev != null) {
                Care.upsertMed(this, dev, med)
                Ui.toast(this, "ذخیره شد ✅ به‌زودی روی گوشی $who اعمال می‌شود")
            } else {
                Parent.upsertMed(this, med)
                Ui.toast(this, "ذخیره شد ✅")
            }
            finish()
        })
        val current = editing
        if (current != null) {
            col.addView(Ui.button(this, "🗑 حذف این دارو", Ui.RED) {
                Ui.confirm(this, "حذف دارو", "«${current.name}» حذف شود؟", "حذف") {
                    if (dev != null) Care.deleteMed(this, dev, current.id) else Parent.deleteMed(this, current.id)
                    finish()
                }
            })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("times", ArrayList(times))
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, h, m ->
            val t = Clock.time(h, m)
            if (t !in times) times.add(t)
            times.sort()
            renderTimes()
        }, 8, 0, true).show()
    }

    private fun renderTimes() {
        timesBox.removeAllViews()
        if (times.isEmpty()) {
            timesBox.addView(Ui.text(this, "هنوز ساعتی اضافه نشده", 17f, Ui.MUTED))
        }
        for (t in times.toList()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.CARD, Ui.dp(this@MedEditActivity, 12), Ui.LINE, Ui.dp(this@MedEditActivity, 1))
                setPadding(Ui.dp(context, 16), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(context, 8) }
            }
            row.addView(TextView(this).apply {
                text = "⏰ " + Fa.time(t)
                textSize = 22f
                setTextColor(Ui.TEXT)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✕ حذف"
                textSize = 17f
                setTextColor(Ui.RED)
                setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
                setOnClickListener {
                    times.remove(t)
                    renderTimes()
                }
            })
            timesBox.addView(row)
        }
    }
}
