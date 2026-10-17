package com.daroyar.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/** The full-screen "time for your medicine" screen, shown over the lock screen. */
class AlarmActivity : AppCompatActivity() {
    private var date = ""
    private var time = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(i: Intent) {
        date = i.getStringExtra("date") ?: ""
        time = i.getStringExtra("time") ?: ""
        render()
    }

    private fun render() {
        val test = date == "test"
        val lines: List<String> = if (test) {
            listOf("این یک آلارم آزمایشی است")
        } else {
            val evs = Parent.slotEvents(this, date, time)
            val open = evs.filter { it.status == PENDING || it.status == MISSED }
            if (open.isEmpty()) {
                // already answered (e.g. from the notification)
                finish()
                return
            }
            open.map { it.label() }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(Ui.dp(context, 22), Ui.dp(context, 36), Ui.dp(context, 22), Ui.dp(context, 28))
        }
        col.addView(Ui.text(this, "⏰", 64f, Color.WHITE, center = true, top = 0))
        col.addView(Ui.text(this, if (test) "آلارم آزمایشی" else "وقت داروست", 36f, Color.WHITE, bold = true, center = true))
        if (!test) col.addView(Ui.text(this, "ساعت " + Fa.time(time), 26f, Color.parseColor("#B2DFDB"), center = true))

        val card = Ui.card(this, Color.TRANSPARENT).apply {
            background = Ui.rounded(Color.parseColor("#14574A"), Ui.dp(context, 18))
        }
        lines.forEach { card.addView(Ui.text(this, "💊 $it", 24f, Color.WHITE, top = 6)) }
        col.addView(card)

        col.addView(Ui.text(this, "", 10f))
        col.addView(Ui.button(this, "✅ خوردم", Ui.GREEN, sp = 32f, height = 110) {
            Parent.markTaken(this, date, time)
            Ui.toast(this, if (test) "آلارم درست کار می‌کند ✅" else "ثبت شد ✅ سلامت باشید")
            finish()
        })
        col.addView(Ui.button(this, "❌ نخوردم", Color.parseColor("#8E2A2A"), sp = 26f, height = 84) {
            Parent.markSkipped(this, date, time)
            Ui.toast(this, if (test) "آلارم درست کار می‌کند ✅" else "ثبت شد")
            finish()
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0B3D33"))
            addView(col)
        }
        setContentView(scroll)
    }
}
