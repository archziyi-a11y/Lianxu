package com.example.lianxu.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lianxu.data.entity.KnowledgeNote
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun ArrangementScreen(vm:ArchiveViewModel,onBack:()->Unit) {
    val notes by vm.arrangements.collectAsState(); val message by vm.message.collectAsState();val busy by vm.busy.collectAsState()
    var edit by remember { mutableStateOf<KnowledgeNote?>(null) };var create by remember { mutableStateOf(false) }
    var habit by remember { mutableStateOf<KnowledgeNote?>(null) }
    BackHandler { onBack() }
    if(create || edit!=null) {
        var text by remember(edit?.id) { mutableStateOf(edit?.text.orEmpty()) }
        AlertDialog(onDismissRequest={create=false;edit=null},title={Text(if(create)"新建跟进" else "修改跟进安排")},text={Column {
            Text("今天的新进展在今日补充；这里决定长期关注什么。",style=MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text,{text=it},label={Text("想持续关注的事情")},minLines=2)
            message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        }},confirmButton={TextButton(enabled=text.isNotBlank() && !busy,onClick={vm.saveFollowUp(text,edit){create=false;edit=null}}){Text("保存安排")}},dismissButton={TextButton(onClick={create=false;edit=null}){Text("取消")}})
    }
    habit?.let { note ->
        val origin=remember(note){JSONObject(note.origin)}
        var name by remember(note.id){mutableStateOf(note.text)};var format by remember(note.id){mutableStateOf(origin.optString("format","controls"))}
        AlertDialog(onDismissRequest={habit=null},title={Text("记录习惯")},text={Column {
            OutlinedTextField(name,{name=it},label={Text("动作或记录名称")})
            FlowRow { listOf("controls" to "动作与次数","text" to "粗略描述","image" to "图片").forEach { (key,label)->FilterChip(selected=format==key,onClick={format=key},label={Text(label)}) } }
            Text("影响以后准备的输入方式，不修改历史实绩。",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(enabled=!busy && name.isNotBlank(),onClick={vm.saveHabit(note,name,format,true){habit=null}}){Text("保存")}},dismissButton={TextButton(enabled=!busy,onClick={vm.saveHabit(note,name,format,false){habit=null}}){Text("不再准备此组件")}})
    }
    Scaffold(topBar={TopAppBar(title={Text("习惯与跟进")},navigationIcon={TextButton(onClick=onBack){Text("返回")}})}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("保留关注，允许改变。",style=MaterialTheme.typography.headlineMedium);Text("取消跟进只停止主动询问；历史答复、训练与展示归档各自保留。",style=MaterialTheme.typography.bodyMedium) }
            message?.let { item { Text(it,color=MaterialTheme.colorScheme.secondary) } }
            item { Button(onClick={vm.clearMessage();create=true}){Text("新建跟进")};Text("持续跟进",Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleMedium) }
            val followups=notes.filter { it.kind=="follow_up" }.sortedBy { it.status!="current" }
            if(followups.isEmpty())item { Text("尚无跟进安排。也可以直接从今日自由开始。",style=MaterialTheme.typography.bodyMedium) }
            items(followups,key={it.id}) { note ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(note.status=="current")"正在跟进" else "已取消",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)
                    Text(note.text,style=MaterialTheme.typography.titleSmall)
                    val progress=notes.filter { it.kind=="follow_up_progress" && JSONObject(it.origin).optString("followUpId")==note.id }.sortedByDescending { it.createdAt }
                    progress.forEach { p->HorizontalDivider();Text(p.effectiveFrom,style=MaterialTheme.typography.labelSmall);Text(p.text) }
                    FlowRow {
                        if(note.status=="current") {
                            TextButton(onClick={edit=note}){Text("修改安排")}
                            TextButton(enabled=!busy,onClick={vm.saveFollowUp(note.text,note,"cancelled")}){Text("取消跟进")}
                        } else TextButton(enabled=!busy,onClick={vm.saveFollowUp(note.text,note,"current")}){Text("重新开启跟进")}
                    }
                } }
            }
            item { Text("你的记录方式",Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleMedium) }
            val habits=notes.filter { it.kind=="input_habit" && it.status!="learning" }
            if(habits.isEmpty())item { Text("重复使用后，会保留你熟悉的记录结构。一次不同的输入不会重排习惯。",style=MaterialTheme.typography.bodyMedium) }
            items(habits,key={it.id}) { note->Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(note.text,style=MaterialTheme.typography.titleSmall)
                Text(if(note.status=="current")"供本次选择，未填写不记为完成" else "已停止准备",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={habit=note}){Text("调整记录方式")}
            } } }
        }
    }
}
