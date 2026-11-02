package com.example.lianxu.data.repository

import android.content.Context
import androidx.work.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.prefs.SettingsManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ExternalSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val pending = ExternalSync(AppDatabase.getDatabase(applicationContext), SettingsManager(applicationContext), applicationContext).sync()
        if (pending > 0) Result.retry() else Result.success()
    } catch (error: CancellationException) { throw error } catch (_: Exception) { Result.retry() }
    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val periodic = PeriodicWorkRequestBuilder<ExternalSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("external-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
            now(context)
        }
        fun now(context: Context) {
            val work = OneTimeWorkRequestBuilder<ExternalSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("external-sync-now", ExistingWorkPolicy.REPLACE, work)
        }
    }
}
