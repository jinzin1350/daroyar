package com.daroyar.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/** Parent side: shows the pairing code to send to the caregiver, and sync status. */
class PairActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val col = Ui.page(this, "اتصال به فرزند")
        val code = Crypto.pretty(Prefs.code(this))

        col.addView(
            Ui.text(
                this,
                "این کد را برای فرزندتان بفرستید تا در برنامهٔ خودش وارد کند. فقط برای فرزندتان بفرستید.",
                18f, Ui.MUTED, top = 14
            )
        )
        val card = Ui.card(this, Ui.PRIMARY)
        card.addView(Ui.text(this, "کد اتصال", 16f, Ui.MUTED, center = true, top = 2))
        card.addView(Ui.text(this, code, 24f, Ui.TEXT, bold = true, center = true).apply {
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
        })
        col.addView(card)

        col.addView(Ui.button(this, "📤 فرستادن کد برای فرزند", Ui.GREEN) {
            val msg = "کد اتصال دارویار (${Prefs.deviceName(this)}):\n$code"
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg)
            startActivity(Intent.createChooser(send, "فرستادن کد با…"))
        })
        col.addView(Ui.button(this, "📋 کپی کد", Ui.GRAY_BTN, Ui.TEXT) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("daroyar", code))
            Ui.toast(this, "کپی شد")
        })

        col.addView(Ui.text(this, "وضعیت ارسال گزارش", 20f, bold = true, top = 24))
        val ok = Prefs.syncOkAt(this)
        val tried = Prefs.syncAttemptAt(this)
        col.addView(
            Ui.text(
                this,
                (if (ok > 0) "✅ آخرین ارسال موفق: " + Fa.ago(ok) else "هنوز ارسال موفقی نبوده") +
                    (if (tried > ok) "\n⚠️ آخرین تلاش (" + Fa.ago(tried) + ") ناموفق بود — احتمالاً اینترنت قطع است." else ""),
                17f
            )
        )
        val log = Prefs.syncLog(this)
        if (log.isNotBlank()) col.addView(Ui.text(this, log, 13f, Ui.MUTED).apply {
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        })
        col.addView(Ui.button(this, "📡 ارسال گزارش الان", Ui.PRIMARY) {
            Ui.toast(this, "در حال ارسال…")
            Ui.background(this, { Parent.publish(this) }) { success ->
                Ui.toast(this, if (success) "ارسال شد ✅" else "ارسال نشد — اینترنت را بررسی کنید")
                render()
            }
        })

        col.addView(Ui.text(this, "اسم این گوشی: " + Prefs.deviceName(this), 17f, top = 24))
        col.addView(Ui.button(this, "✏️ تغییر اسم", Ui.GRAY_BTN, Ui.TEXT, sp = 17f, height = 50) {
            val input = Ui.input(this, "مثلاً: مامان", Prefs.deviceName(this))
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("اسم صاحب گوشی")
                .setView(Ui.box(this, input))
                .setPositiveButton("ذخیره") { _, _ ->
                    Prefs.setDeviceName(this, input.text.toString())
                    Sync.now(this)
                    render()
                }
                .setNegativeButton("انصراف", null)
                .show()
        })
        col.addView(Ui.button(this, "🔄 ساختن کد جدید", Ui.GRAY_BTN, Ui.RED, sp = 17f, height = 50) {
            Ui.confirm(
                this, "کد جدید؟",
                "بعد از ساختن کد جدید، فرزندتان باید کد تازه را دوباره وارد کند. ادامه می‌دهید؟",
                "بله، کد جدید"
            ) {
                Prefs.newCode(this)
                Sync.now(this)
                render()
            }
        })
    }
}
