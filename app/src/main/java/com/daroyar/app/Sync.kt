package com.daroyar.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object Sync {
    /** Every 15 min: self-heal alarms (parent) and talk to the brokers. Runs offline too. */
    fun schedulePeriodic(c: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(c)
            .enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** As soon as there is internet (retries with backoff, e.g. during an outage). */
    fun now(c: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(workDataOf("once" to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork("now", ExistingWorkPolicy.REPLACE, req)
    }
}

class SyncWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val c = applicationContext
        val once = inputData.getBoolean("once", false)
        return try {
            when (Prefs.role(c)) {
                Prefs.PARENT -> {
                    Parent.selfHeal(c, fromBoot = false)
                    if (!Net.online(c)) {
                        if (once) Result.retry() else Result.success()
                    } else {
                        val ok = Parent.publish(c)
                        if (ok || !once) Result.success() else Result.retry()
                    }
                }
                Prefs.CARE -> {
                    Care.sync(c)
                    Result.success()
                }
                else -> Result.success()
            }
        } catch (t: Throwable) {
            if (once) Result.retry() else Result.success()
        }
    }
}
