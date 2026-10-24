package com.example.lianxu.timer

import android.app.*
import android.content.*
import android.os.*
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.example.lianxu.MainActivity
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.RestTimerEntity
import kotlinx.coroutines.*

class RestTimerRepository(private val context: Context, private val db: AppDatabase = AppDatabase.getDatabase(context)) {
    val state = db.collaborationDao().watchTimer()
    private fun boot()=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,0)
    suspend fun start(seconds: Int) {
        require(seconds in 1..3600) { "组间计时请设置1秒至60分钟" }
        val now=System.currentTimeMillis()
        db.collaborationDao().putTimer(RestTimerEntity(deadlineWallMs=now+seconds*1000L,deadlineElapsedMs=SystemClock.elapsedRealtime()+seconds*1000L,bootCount=boot(),status="running",updatedAt=now))
        try { ContextCompat.startForegroundService(context,Intent(context,RestTimerService::class.java)) }
        catch(error: Exception) { db.collaborationDao().timer()?.let { db.collaborationDao().putTimer(it.copy(status="cancelled")) };throw error }
    }
    fun remaining(timer: RestTimerEntity?, wall: Long=System.currentTimeMillis(), elapsed: Long=SystemClock.elapsedRealtime(), currentBoot: Int=boot()): Long =
        if(timer?.status!="running")0 else (if(timer.bootCount==currentBoot)timer.deadlineElapsedMs-elapsed else timer.deadlineWallMs-wall).coerceAtLeast(0)
    suspend fun cancel() {
        db.withTransaction { db.collaborationDao().timer()?.let { db.collaborationDao().putTimer(it.copy(status="cancelled",updatedAt=System.currentTimeMillis())) } }
        context.stopService(Intent(context,RestTimerService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(RestTimerService.ID)
    }
    suspend fun resumeIfNeeded() {
        val timer=db.collaborationDao().timer() ?: return
        if(timer.status!="running")return
        if(remaining(timer)==0L) db.collaborationDao().putTimer(timer.copy(status="completed",updatedAt=System.currentTimeMillis()))
        else ContextCompat.startForegroundService(context,Intent(context,RestTimerService::class.java))
    }
}

/** Countdown is deadline-based; the notification chronometer keeps rendering while the screen is locked. */
class RestTimerService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var timerJob: Job?=null
    private var lock: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"组间休息",NotificationManager.IMPORTANCE_DEFAULT))
        if(intent?.action=="cancel") {
            scope.launch { RestTimerRepository(this@RestTimerService).cancel() };return START_NOT_STICKY
        }
        startForeground(ID,notification(System.currentTimeMillis()+1000))
        timerJob?.cancel(); if(lock?.isHeld==true)lock?.release()
        timerJob=scope.launch {
            val db=AppDatabase.getDatabase(this@RestTimerService);val repo=RestTimerRepository(this@RestTimerService,db)
            val timer=db.collaborationDao().timer()
            if(timer?.status!="running") { stopSelf();return@launch }
            lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Lianxu:RestTimer").also { it.acquire((repo.remaining(timer)+5000).coerceIn(1000,3_605_000)) }
            manager.notify(ID,notification(System.currentTimeMillis()+repo.remaining(timer)))
            while(isActive && repo.remaining(timer)>0) {
                if(db.collaborationDao().timer()?.updatedAt!=timer.updatedAt)return@launch
                delay(minOf(repo.remaining(timer),1000))
            }
            db.withTransaction { val current=db.collaborationDao().timer(); if(current==timer) db.collaborationDao().putTimer(timer.copy(status="completed",updatedAt=System.currentTimeMillis())) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            val done=Notification.Builder(this@RestTimerService,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("组间休息结束")
                .setContentText("可以开始下一组了").setContentIntent(openApp()).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PUBLIC).build()
            if(Build.VERSION.SDK_INT<33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED) manager.notify(ID,done)
            stopSelf()
        }
        return START_STICKY
    }
    private fun openApp()=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(deadline: Long): Notification {
        val cancel=PendingIntent.getService(this,1,Intent(this,RestTimerService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("组间休息倒计时")
            .setContentText("结束时间已保存在本机").setWhen(deadline).setUsesChronometer(true).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null,"结束计时",cancel).build()).build()
    }
    override fun onDestroy() { scope.cancel();if(lock?.isHeld==true)lock?.release();super.onDestroy() }
    companion object { const val ID=4601;const val CHANNEL="rest-timer" }
}
