package com.example.lianxu.ui.screens

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.lianxu.timer.RestTimerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RestTimerControls() {
    val context=LocalContext.current;val repository=remember { RestTimerRepository(context) }
    val timer by repository.state.collectAsState(initial=null)
    val scope=rememberCoroutineScope()
    var seconds by rememberSaveable { mutableStateOf("90") }
    var remaining by remember { mutableLongStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableIntStateOf(90) }
    fun start(n: Int) { scope.launch { try { repository.start(n) } catch(e:Exception){message=e.message ?: "计时未开始"} } }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if(!allowed)message="通知未开启，可在应用内查看倒计时"
        start(pending)
    }
    LaunchedEffect(timer) { while(timer?.status=="running") { remaining=repository.remaining(timer);delay(250) };if(timer?.status!="running")remaining=0 }
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
            if(timer?.status=="running") {
                Text("组间休息 ${remaining/60000}:${((remaining/1000)%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f).padding(top=12.dp))
                TextButton(onClick={scope.launch { repository.cancel() }}) { Text("结束计时") }
            } else {
                OutlinedTextField(value=seconds,onValueChange={seconds=it},label={Text("休息秒数")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f))
                Button(onClick={
                    val n=seconds.toIntOrNull()
                    if(n==null || n !in 1..3600)message="请输入1至3600秒" else {
                        message=null;pending=n
                        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else start(n)
                    }
                },modifier=Modifier.padding(top=6.dp)) { Text(if(timer?.status=="completed")"再计一组" else "开始计时") }
            }
        }
        message?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
    }
}
