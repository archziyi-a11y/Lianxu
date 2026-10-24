package com.example.lianxu.archive

import android.app.Application
import android.content.Context
import androidx.room.*
import androidx.work.*
import com.example.lianxu.agent.DeepSeekAgentClient
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.prefs.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit

class LianxuApplication:Application(){override fun onCreate(){super.onCreate();ArchiveRuntime.start(this)}}
object ArchiveRuntime {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private var started=false
 @OptIn(FlowPreview::class)
 fun start(context:Context){
  if(started)return;started=true
  scope.launch {
   val db=AppDatabase.getDatabase(context);val repo=ArchiveRepository(context,db);repo.methods.installBuiltins()
   db.invalidationTracker.createFlow("profiles","goal_phases","measurements","training_sessions","intake_entries","knowledge_notes","calculations","archive_methods",emitInitialState=true)
    .debounce(500).collect { try { repo.refresh();enqueueIfNeeded(context,repo) }catch(e:CancellationException){throw e}catch(_:Exception){ /* Persisted facts remain authoritative; resume retries. */ } }
  }
  WorkManager.getInstance(context).enqueueUniquePeriodicWork("archive-day",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<ArchiveWorker>(1,TimeUnit.DAYS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
 }
 fun resume(context:Context){scope.launch { runCatching { val repo=ArchiveRepository(context,AppDatabase.getDatabase(context));repo.methods.installBuiltins();repo.refresh();enqueueIfNeeded(context,repo) } }}
 suspend fun enqueueIfNeeded(context:Context,repo:ArchiveRepository){
  val job=repo.db.archiveDao().job() ?: return
  if(job.status=="empty" || job.completedFingerprint==job.desiredFingerprint || SettingsManager(context).apiKeyFlow.first().isNullOrBlank())return
  WorkManager.getInstance(context).enqueueUniqueWork("archive-refresh",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<ArchiveWorker>().setInitialDelay(15,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
 }
}
class ArchiveWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
 override suspend fun doWork():Result {
  val db=AppDatabase.getDatabase(applicationContext);val repo=ArchiveRepository(applicationContext,db)
  return try {
   repo.methods.installBuiltins();repo.refresh()
   val key=SettingsManager(applicationContext).apiKeyFlow.first()?.takeIf { it.isNotBlank() } ?: return Result.success()
   val job=db.archiveDao().job() ?: return Result.success()
   if(job.status=="empty" || job.desiredFingerprint==job.completedFingerprint)return Result.success()
   db.archiveDao().putJob(job.copy(status="running",lastError=null,updatedAt=System.currentTimeMillis()))
   ArchiveComposer(repo,DeepSeekAgentClient(key)).run()
   // A change arriving during inference remains dirty; retry reads the latest basis.
   val latest=db.archiveDao().job();if(latest?.desiredFingerprint!=latest?.completedFingerprint)Result.retry() else Result.success()
  }catch(e:CancellationException){throw e}catch(e:Exception){
   db.archiveDao().job()?.let { db.archiveDao().putJob(it.copy(status="failed",lastError=e.message?.take(300),updatedAt=System.currentTimeMillis())) }
   if(runAttemptCount<2)Result.retry() else Result.failure()
  }
 }
}
