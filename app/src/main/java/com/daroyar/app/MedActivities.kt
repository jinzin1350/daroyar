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

/** Add or edit one medicine and its daily times. */
class MedEditActivity : AppCompatActivity() {
    private var editing: Med? = null
    private val times = mutableListOf<String>()
    private lateinit var timesBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("id", -1L)
        editing = if (id >= 0) Parent.med(this, id) else null
        editing?.let { times.addAll(it.times) }
        if (savedInstanceState != null) {
            times.clear()
            times.addAll(savedInstanceState.getStringArrayList("times") ?: arrayListOf())
        }

        val col = Ui.page(this, if (editing == null) "داروی جدید" else "ویرایش دارو")
        col.addView(Ui.text(this, "اسم دارو", 18f, bold = true, top = 14))
        val name = Ui.input(this, "مثلاً: قرص فشار", editing?.name ?: "")
        col.addView(name)
        col.addView(Ui.text(this, "مقدار (اختیاری)", 18f, bold = true, top = 14))
        val dose = Ui.input(this, "مثلاً: یک عدد", editing?.dose ?: "")
        col.addView(dose)

        col.addView(Ui.text(this, "ساعت‌های خوردن (هر روز)", 18f, bold = true, top = 18))
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
            Parent.upsertMed(this, med)
            Ui.toast(this, "ذخیره شد ✅")
            finish()
        })
        if (editing != null) {
            col.addView(Ui.button(this, "🗑 حذف این دارو", Ui.RED) {
                Ui.confirm(this, "حذف دارو", "«${editing!!.name}» حذف شود؟", "حذف") {
                    Parent.deleteMed(this, editing!!.id)
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
