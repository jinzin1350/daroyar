package com.daroyar.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Live checklist of the settings that make alarms reliable, each with a
 * button that opens the right system screen. On a video call the caregiver
 * can just say "open Diagnostics and make everything green".
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val col = Ui.page(this, "بررسی تنظیمات")
        val parent = Prefs.role(this) == Prefs.PARENT
        val pkgUri = Uri.parse("package:$packageName")

        col.addView(
            Ui.text(
                this,
                if (parent) "همهٔ موارد باید ✅ باشند تا زنگ دارو مطمئن کار کند. فقط یک بار لازم است."
                else "همهٔ موارد باید ✅ باشند تا هشدارها سر وقت برسند.",
                17f, Ui.MUTED, top = 14
            )
        )

        row(col, "اجازهٔ اعلان", Diag.notifOk(this), "بدون این، هیچ زنگ و هشداری نمی‌آید.") { fixNotif() }
        if (parent) {
            row(col, "اعلان «زنگ دارو»", Diag.ringChannelOk(this), "این اعلان باید روشن و با اولویت بالا (پاپ‌آپ) باشد.") {
                if (Build.VERSION.SDK_INT >= 26) {
                    open(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Notify.CH_RING)
                    )
                }
            }
        }
        row(col, "آلارم دقیق", Diag.exactOk(this), "برای اینکه زنگ دقیقاً سر ساعت بخورد.") {
            if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
        }
        if (parent) {
            row(col, "نمایش تمام‌صفحه", Diag.fullScreenOk(this), "تا صفحهٔ دارو روی صفحهٔ قفل هم بیاید.") {
                if (Build.VERSION.SDK_INT >= 34) open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
            }
            row(col, "نمایش روی برنامه‌های دیگر", Diag.overlayOk(this), "تا صفحهٔ دارو حتی وقتی گوشی دستشان است خودش باز شود.") {
                if (Build.VERSION.SDK_INT >= 23) open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
            }
        }
        row(col, "بهینه‌سازی باتری خاموش", Diag.batteryOk(this), "وگرنه گوشی برنامه را می‌خواباند و زنگ نمی‌خورد.") {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
        }
        if (parent) {
            row(col, "ساعت و منطقهٔ زمانی", Diag.timeZoneOk(), "منطقهٔ زمانی تهران باید ‎+۳:۳۰ باشد. گوشی‌های قدیمی گاهی هنوز ساعت تابستانی را اعمال می‌کنند.") {
                open(Intent(Settings.ACTION_DATE_SETTINGS))
            }
        }

        brandHints(col, pkgUri)

        col.addView(Ui.text(this, "آزمایش", 20f, bold = true, top = 24))
        if (parent) {
            col.addView(Ui.button(this, "🔔 آلارم آزمایشی (۱۰ ثانیه دیگر)", Ui.PRIMARY) {
                Parent.scheduleTestAlarm(this)
                Ui.toast(this, "حالا صفحهٔ گوشی را خاموش کنید و ۱۰ ثانیه صبر کنید")
            })
        } else {
            col.addView(Ui.button(this, "🔔 هشدار آزمایشی", Ui.PRIMARY) {
                Notify.care(this, "test" + Clock.now(), "هشدار آزمایشی", "اگر این را می‌بینید، هشدارها درست کار می‌کنند ✅")
            })
        }

        if (parent) {
            col.addView(Ui.text(this, "پیشرفته", 20f, bold = true, top = 24))
            col.addView(
                Ui.text(
                    this,
                    "داروها را معمولاً فرزند از گوشی خودش وارد می‌کند. فقط اگر اینترنت نیست، می‌شود اینجا روی همین گوشی وارد کرد.",
                    15f, Ui.MUTED
                )
            )
            col.addView(Ui.button(this, "💊 ویرایش داروها روی همین گوشی", Ui.GRAY_BTN, Ui.TEXT, sp = 17f, height = 52) {
                startActivity(Intent(this, MedListActivity::class.java))
            })
        }

        col.addView(Ui.text(this, "نسخهٔ برنامه: " + Fa.digits(BuildConfig.VERSION_NAME), 17f, Ui.MUTED, top = 20))
        col.addView(Ui.button(this, "⬇️ بررسی نسخهٔ جدید", Ui.GRAY_BTN, Ui.TEXT, sp = 17f, height = 52) {
            Ui.toast(this, "در حال بررسی…")
            Ui.background(this, { Updater.latest() }) { r ->
                when {
                    r == null -> Ui.toast(this, "نسخه‌ای پیدا نشد یا اینترنت قطع است")
                    r != null && r.versionCode > BuildConfig.VERSION_CODE ->
                        AlertDialog.Builder(this)
                            .setTitle("نسخهٔ جدید آماده است")
                            .setMessage("نسخهٔ ${Fa.digits(r.name)} را دانلود و نصب کنید؟ (اطلاعات داروها می‌ماند)")
                            .setPositiveButton("دانلود") { _, _ -> open(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
                            .setNegativeButton("بعداً", null)
                            .show()
                    else -> Ui.toast(this, "برنامه به‌روز است ✅")
                }
            }
        })

        col.addView(Ui.button(this, "تغییر نقش این گوشی", Ui.GRAY_BTN, Ui.RED, sp = 16f, height = 48) {
            Ui.confirm(
                this, "تغییر نقش",
                "این گوشی به صفحهٔ انتخاب نقش برمی‌گردد. اطلاعات داروها پاک نمی‌شود.",
                "تغییر نقش"
            ) {
                Prefs.setRole(this, null)
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        })
    }

    private fun row(col: LinearLayout, title: String, ok: Boolean, desc: String, fix: () -> Unit) {
        val card = Ui.card(this, if (ok) Ui.GREEN else Ui.RED)
        card.addView(Ui.text(this, (if (ok) "✅ " else "❌ ") + title, 19f, if (ok) Ui.GREEN else Ui.RED, bold = true, top = 2))
        if (!ok) {
            card.addView(Ui.text(this, desc, 16f, Ui.MUTED, top = 4))
            card.addView(Ui.button(this, "درست کردن", Ui.RED, sp = 18f, height = 54) { fix() })
        }
        col.addView(card)
    }

    private fun brandHints(col: LinearLayout, pkgUri: Uri) {
        val brand = (Build.MANUFACTURER ?: "").lowercase()
        val hint = when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "گوشی شیائومی: در «تنظیمات برنامه» گزینهٔ Autostart را روشن و Battery saver را روی No restrictions بگذارید."
            brand.contains("samsung") ->
                "گوشی سامسونگ: در تنظیمات باتری، این برنامه را به «Never sleeping apps» اضافه کنید."
            brand.contains("huawei") || brand.contains("honor") ->
                "گوشی هواوی: در App launch، این برنامه را روی Manage manually بگذارید و هر سه گزینه را روشن کنید."
            brand.contains("oppo") || brand.contains("realme") || brand.contains("vivo") ->
                "در تنظیمات باتری، اجازهٔ اجرای پس‌زمینه و Auto-launch را برای این برنامه روشن کنید."
            else -> null
        } ?: return
        val card = Ui.card(this, Ui.AMBER)
        card.addView(Ui.text(this, "💡 نکتهٔ مخصوص این گوشی", 18f, Ui.AMBER, bold = true, top = 2))
        card.addView(Ui.text(this, hint, 16f, top = 4))
        card.addView(Ui.button(this, "باز کردن تنظیمات برنامه", Ui.GRAY_BTN, Ui.TEXT, sp = 17f, height = 50) {
            val xiaomi = Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            if (!(brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) || !tryOpen(xiaomi)) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            }
        })
        col.addView(card)
    }

    private fun fixNotif() {
        val sp = Prefs.sp(this)
        if (Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("notif_asked", false)) {
            sp.edit().putBoolean("notif_asked", true).apply()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun tryOpen(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (t: Throwable) {
        false
    }

    private fun open(i: Intent) {
        if (!tryOpen(i)) {
            tryOpen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
