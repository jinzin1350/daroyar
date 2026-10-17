package com.daroyar.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.ceil

/**
 * Rings like a real alarm clock: plays the alarm sound itself on the ALARM
 * stream (so silent mode and notification volume don't matter), vibrates,
 * turns the screen on and opens the full-screen dose page. It does not rely
 * on notification-channel sounds, which many phones mute.
 *
 * Only started from exact-alarm callbacks, which Android allows to start a
 * foreground service from the background.
 */
class AlarmService : Service() {

    companion object {
        private const val NOTIF_ID = 4242
        private const val RING_MS = 3 * 60_000L

        /** "date|time" of the slot that is ringing right now. */
        @Volatile
        var ringingSlot: String? = null

        /** Returns false if Android refused; the caller then falls back to a notification. */
        fun start(c: Context, date: String, time: String): Boolean {
            return try {
                val i = Intent(c, AlarmService::class.java)
                    .setAction("start")
                    .putExtra("date", date)
                    .putExtra("time", time)
                ContextCompat.startForegroundService(c, i)
                true
            } catch (t: Throwable) {
                Log.e("Daroyar", "could not start ringing", t)
                false
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, AlarmService::class.java))
            } catch (_: Throwable) {
            }
        }

        /** Stops ringing if it is ringing for this slot (or for nothing in particular). */
        fun stopFor(c: Context, date: String, time: String) {
            val s = ringingSlot
            if (s == null || s == "$date|$time") stop(c)
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var screenLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var date = ""
    private var time = ""

    private val autoStop = Runnable {
        // Stop the noise after a few minutes; leave a quiet reminder in the
        // notification shade. The next re-ring will start it again.
        val pend = pendingLines()
        if (date != "test" && pend.isNotEmpty()) {
            Notify.showReminder(this, date, time, pend, "داروی ساعت ${Fa.time(time)} هنوز تأیید نشده")
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        date = intent?.getStringExtra("date") ?: ""
        time = intent?.getStringExtra("time") ?: ""
        val lines = if (date == "test") listOf("این یک آلارم آزمایشی است") else pendingLines()

        // Must go foreground immediately after startForegroundService().
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID,
                Notify.ringNotification(this, date, time, lines),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        } catch (t: Throwable) {
            Log.e("Daroyar", "startForeground failed", t)
            if (lines.isNotEmpty()) Notify.showDoseAlarm(this, date, time, lines)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action != "start" || date.isEmpty() || lines.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        ringingSlot = "$date|$time"
        Notify.cancelSlot(this, date, time)
        startRinging()
        wakeScreen()
        openDosePage()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, RING_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        stopRinging()
        ringingSlot = null
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun pendingLines(): List<String> {
        if (date.isEmpty() || date == "test") return emptyList()
        return Parent.slotEvents(this, date, time)
            .filter { it.status == PENDING || it.status == MISSED }
            .map { it.label() }
    }

    @Suppress("DEPRECATION")
    private fun startRinging() {
        stopRinging()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // Make sure the alarm volume is not too low (parents rarely change it on purpose).
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val want = ceil(max * 0.85).toInt()
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < want) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0)
            }
        } catch (_: Throwable) {
        }

        for (uri in Notify.soundCandidates(this)) {
            var p: MediaPlayer? = null
            try {
                p = MediaPlayer()
                p.setAudioAttributes(attrs)
                p.setDataSource(this, uri)
                p.isLooping = true
                p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                p.prepare()
                p.start()
                player = p
                break
            } catch (t: Throwable) {
                Log.w("Daroyar", "sound $uri failed", t)
                try {
                    p?.release()
                } catch (_: Throwable) {
                }
            }
        }

        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                getSystemService(Vibrator::class.java)
            }
            vibrator = v
            val pattern = longArrayOf(0, 900, 600)
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            } else {
                v.vibrate(pattern, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopRinging() {
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null
        try {
            screenLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        screenLock = null
    }

    /** Lights up the screen even when it is off, so the alarm is visible. */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            screenLock?.let { if (it.isHeld) it.release() }
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "daroyar:ring"
            ).apply { acquire(60_000L) }
        } catch (_: Throwable) {
        }
    }

    /**
     * Full-screen page. The notification's full-screen intent covers the lock
     * screen; with "display over other apps" allowed we also open it directly,
     * so it appears even while the phone is in use.
     */
    private fun openDosePage() {
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .putExtra("date", date)
                    .putExtra("time", time)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Throwable) {
        }
    }
}
