package com.daroyar.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Entry point: picks the role on first launch, then routes. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (Prefs.role(this)) {
            Prefs.PARENT -> go(ParentHomeActivity::class.java)
            Prefs.CARE -> go(CareHomeActivity::class.java)
            else -> chooser()
        }
    }

    private fun go(cls: Class<*>) {
        startActivity(Intent(this, cls))
        finish()
    }

    private fun chooser() {
        val col = Ui.page(this, "دارویار", back = false)
        col.addView(Ui.text(this, "خوش آمدید 🌿", 26f, bold = true, top = 20))
        col.addView(
            Ui.text(
                this,
                "فرزندان داروها را از گوشی خودشان وارد می‌کنند. گوشی مامان و بابا سر وقت زنگ می‌زند و آن‌ها فقط «خوردم» یا «نخوردم» را می‌زنند.",
                18f, Ui.MUTED
            )
        )
        col.addView(Ui.text(this, "این گوشی مال کیست؟", 22f, bold = true, top = 28))
        col.addView(
            Ui.button(this, "👵👴  گوشی مامان یا بابا\n(کسی که دارو می‌خورد)", Ui.GREEN, height = 96) {
                becomeParent()
            }
        )
        col.addView(
            Ui.button(this, "🧑  گوشی فرزند\n(برای دیدن گزارش و گرفتن هشدار)", Ui.PRIMARY, height = 96) {
                Prefs.setRole(this, Prefs.CARE)
                Sync.schedulePeriodic(this)
                go(CareHomeActivity::class.java)
            }
        )
    }

    /** Parents type nothing: the children name the phone and send the medicines. */
    private fun becomeParent() {
        Prefs.setRole(this, Prefs.PARENT)
        Prefs.code(this)
        Sync.schedulePeriodic(this)
        Sync.now(this)
        startActivities(
            arrayOf(
                Intent(this, ParentHomeActivity::class.java),
                Intent(this, DiagnosticsActivity::class.java).putExtra("firstRun", true)
            )
        )
        finish()
    }
}
