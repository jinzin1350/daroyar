package com.daroyar.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.createChannels(this)
        when (Prefs.role(this)) {
            Prefs.PARENT -> {
                Sync.schedulePeriodic(this)
                try {
                    Parent.scheduleNext(this)
                } catch (_: Throwable) {
                }
            }
            Prefs.CARE -> {
                Sync.schedulePeriodic(this)
                try {
                    Care.scheduleCheck(this)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
